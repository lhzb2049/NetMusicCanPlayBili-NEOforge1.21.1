package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmStartupSeekPolicy;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioPipelineFactory;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.DolbyEc3Pipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.FlacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.OpenALTappedAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.OneShotRequestRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.AudioFormat.Encoding;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

public class HttpAudioStreamHandler implements IAudioStreamHandler {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final AudioStreamProperties.Http PROPERTIES = AudioStreamProperties.http();
   private static final int PIPE_BUFFER_SIZE = 4194304;
   private static final int FORMAT_WAIT_SECONDS = PROPERTIES.formatWaitSeconds();
   private static final long WORKER_JOIN_TIMEOUT_MILLIS = 2000L;
   private static final int MP3_SYNC_SCAN_BYTES = 524288;
   private static final int MAX_HTTP_REDIRECTS = 5;
   private static final int MP3_SEEK_FADE_MILLIS = 80;
   private static final long REQUEST_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10L);
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(Redirect.NEVER).connectTimeout(Duration.ofSeconds(10L)).build();
   private static final OneShotRequestRegistry<PlaybackRequest> REQUESTS = new OneShotRequestRegistry<>();
   private static final Set<HttpAudioStreamHandler.ActiveStreamControl> ACTIVE_MODERN_STREAMS = ConcurrentHashMap.newKeySet();

   public static void registerSegmentBase(String audioUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
      Fmp4AudioStreamSeeker.registerSegmentBase(audioUrl, initStart, initEnd, indexStart, indexEnd);
   }

   public static HttpAudioStreamHandler.RegisteredRequest registerRequest(PlaybackRequest request) {
      if (request != null && !request.mediaUrl().isBlank()) {
         closeStaleModernStreams(request.pos(), request.playbackSessionId(), request.minecartUuid());
         long expiresAt = System.currentTimeMillis() + REQUEST_TTL_MILLIS;
         MediaRequestToken token = REQUESTS.registerToken(request, expiresAt);
         return new HttpAudioStreamHandler.RegisteredRequest(PlaybackSync.withRequestToken(request.mediaUrl(), token), Optional.of(token));
      } else {
         return new HttpAudioStreamHandler.RegisteredRequest("", Optional.empty());
      }
   }

   public static void cancelRequest(String requestToken) {
      MediaRequestToken.parse(requestToken).ifPresent(HttpAudioStreamHandler::cancelRequest);
   }

   public static void cancelRequest(MediaRequestToken requestToken) {
      REQUESTS.cancelToken(requestToken);
   }

   public static PlaybackRequest consumeRegisteredRequest(String url) {
      return PlaybackSync.parseMediaRequestToken(url).map(REQUESTS::consumeToken).orElse(null);
   }

   public static void closeModernStreams() {
      for (HttpAudioStreamHandler.ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
         control.close();
      }

      ACTIVE_MODERN_STREAMS.clear();
      REQUESTS.clear();
      Fmp4AudioStreamSeeker.clearSegmentBases();
   }

   public boolean canHandle(URL url) {
      String protocol = url.getProtocol();
      if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
         return false;
      } else if (url.getHost() != null && (url.getPath() == null || !url.getPath().endsWith(".m3u8"))) {
         if (hasRequestContext(url)) {
            return true;
         } else {
            return isNativeNetMusicHost(url) ? false : isBiliCdnHost(url);
         }
      } else {
         return false;
      }
   }

   private static boolean isNativeNetMusicHost(URL url) {
      String host = url.getHost();
      if (host == null) {
         return false;
      } else {
         String lower = host.toLowerCase(Locale.ROOT);
         return lower.contains("music.163.com") || lower.contains("music.126.net");
      }
   }

   public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
      PlaybackRequest request = consumeRequest(url);
      URL requestUrl = request != null ? URI.create(request.mediaUrl()).toURL() : PlaybackSync.strip(url);
      if (request != null && isNativeNetMusicHost(requestUrl)) {
         return fallbackHttpStream(requestUrl, request, null);
      } else {
         try {
            return this.handleWithPipeline(requestUrl, request);
         } catch (UnsupportedAudioFileException var5) {
            if (var5 instanceof HttpAudioStreamHandler.NonPipelineAudioException) {
               return fallbackHttpStream(requestUrl, request, var5);
            } else {
               BiliPlaybackDiagnostics.markFailed(requestUrl, var5);
               throw var5;
            }
         } catch (IOException var6) {
            BiliPlaybackDiagnostics.markFailed(requestUrl, var6);
            throw var6;
         }
      }
   }

   private AudioInputStream handleWithPipeline(URL url, PlaybackRequest request) throws UnsupportedAudioFileException, IOException {
      long started = System.currentTimeMillis();
      boolean modernTurntable = request != null;
      float startOffsetSeconds = startOffsetSeconds(request);
      BlockingAudioPipe fallbackPipe = new BlockingAudioPipe(4194304);
      AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
      AtomicReference<Exception> errorRef = new AtomicReference<>();
      AtomicReference<InputStream> bodyRef = new AtomicReference<>();
      AtomicBoolean closed = new AtomicBoolean(false);
      CountDownLatch formatReady = new CountDownLatch(1);
      Thread worker = NetMusicThreadFactory.daemonThread(
         modernTurntable ? "AudioStreamWorker" : "BiliCompatAudioStreamWorker",
         () -> streamDecode(url, fallbackPipe, pipelineRef, errorRef, bodyRef, closed, formatReady, request, startOffsetSeconds)
      );
      worker.start();
      HttpAudioStreamHandler.ActiveStreamControl streamControl = null;
      if (modernTurntable && request != null) {
         streamControl = new HttpAudioStreamHandler.ActiveStreamControl(
            url, request.pos(), request.playbackSessionId(), request.minecartUuid(), closed, bodyRef, worker, fallbackPipe, pipelineRef, formatReady
         );
      }

      if (streamControl != null) {
         ACTIVE_MODERN_STREAMS.add(streamControl);
      }

      try {
         awaitFormat(url, closed, bodyRef, worker, formatReady, errorRef);
         LOGGER.debug(
            "HTTP 音频格式就绪: cost={}ms session={} offset={}s host={}",
            new Object[]{System.currentTimeMillis() - started, request != null ? request.sessionId() : "", startOffsetSeconds, url.getHost()}
         );
      } catch (UnsupportedAudioFileException | IOException var22) {
         closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get(), streamControl);
         throw var22;
      }

      try {
         throwIfFailed(errorRef);
      } catch (UnsupportedAudioFileException | IOException var21) {
         closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipelineRef.get(), streamControl);
         throw var21;
      }

      AudioDecodePipeline pipeline = pipelineRef.get();
      if (pipeline == null) {
         closeWorker(url, closed, bodyRef, worker, fallbackPipe, null, streamControl);
         throw new IOException("unable to detect audio format");
      } else {
         AudioFormat format = pipeline.format();
         LOGGER.debug(
            "HTTP 音频管线摘要: mode={} session={} pos={} offset={}s total={}ms container={} codec={} format={}Hz/{}ch/{}bit detail={} host={}",
            new Object[]{
               modernTurntable ? "modern-turntable" : "compat",
               request != null ? request.sessionId() : "",
               request != null ? request.pos() : null,
               startOffsetSeconds,
               request != null ? request.totalMillis() : 0L,
               pipeline.container(),
               pipeline.codec(),
               format.getSampleRate(),
               format.getChannels(),
               format.getSampleSizeInBits(),
               pipeline.detail(),
               url.getHost()
            }
         );
         if (pipeline instanceof FlacPcmPipeline flacPipeline) {
            AudioInputStream decoded = flacPipeline.openDecodedStream();
            AudioFormat decodedFormat = decoded.getFormat();
            if (decodedFormat.getSampleSizeInBits() > 16) {
               AudioFormat fmt16 = new AudioFormat(decodedFormat.getSampleRate(), 16, decodedFormat.getChannels(), true, false);
               LOGGER.debug(
                  "FLAC Hi-Res enabled TPDF dither {}bit -> 16bit: {}Hz/{}ch",
                  new Object[]{decodedFormat.getSampleSizeInBits(), decodedFormat.getSampleRate(), decodedFormat.getChannels()}
               );
               decoded = new AudioInputStream(new PcmDitheringStream(decoded, decodedFormat, fmt16), fmt16, -1L);
            }

            return managedStream(decoded, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
         } else if (pipeline instanceof FlacOpenALPipeline flacPipeline) {
            AudioInputStream tapped = flacPipeline.openTappedStream();
            return managedStream(tapped, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
         } else {
            return pipeline.usesOpenAlOutput()
               ? silentStream(format, closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl)
               : managedStream(new AudioInputStream(fallbackPipe, format, -1L), closed, worker, url, bodyRef, fallbackPipe, pipeline, streamControl);
         }
      }
   }

   private static PlaybackRequest consumeRequest(URL url) {
      return PlaybackSync.parseMediaRequestToken(url.toString()).map(REQUESTS::consumeToken).orElse(null);
   }

   private static float startOffsetSeconds(PlaybackRequest request) {
      return request != null ? request.startOffsetSeconds() : 0.0F;
   }

   private static AudioInputStream fallbackHttpStream(URL url, PlaybackRequest request, UnsupportedAudioFileException probeError) throws UnsupportedAudioFileException, IOException {
      try {
         LOGGER.debug("Falling back to NetMusic direct HTTP handler for non-fMP4 URL: {}", url);
         float startOffsetSeconds = request != null ? request.startOffsetSeconds() : 0.0F;
         if (request != null && startOffsetSeconds > 1.0F && !isNativeNetMusicHost(url)) {
            AudioInputStream strict = tryOpenSafeCustomMp3Stream(url, request, startOffsetSeconds);
            if (strict != null) {
               return strict;
            }
         }

         NetEaseHttpHandler netEase = new NetEaseHttpHandler();
         AudioInputStream stream;
         if (netEase.canHandle(url)) {
            stream = netEase.handle(url);
         } else {
            stream = new DirectHttpHandler().handle(url);
         }

         return request != null ? openModernFallbackStream(stream, request, startOffsetSeconds) : applyStartOffset(stream, startOffsetSeconds);
      } catch (IOException | UnsupportedAudioFileException var6) {
         if (probeError != null) {
            var6.addSuppressed(probeError);
         }

         BiliPlaybackDiagnostics.markFailed(url, var6);
         throw var6;
      }
   }

   private static AudioInputStream tryOpenSafeCustomMp3Stream(URL url, PlaybackRequest request, float startOffsetSeconds) throws IOException, UnsupportedAudioFileException {
      InputStream body = null;
      InputStream aligned = null;
      AudioInputStream encoded = null;

      try {
         body = openHttpRangeStream(url, 0L);
         byte[] probe = body.readNBytes(524288);
         int sync = Mp3FrameSync.findFrameSync(probe, probe.length);
         if (sync < 0) {
            closeQuietly(body);
            body = null;
            if (isLikelyMp3Url(url)) {
               throw new UnsupportedAudioFileException("custom MP3 did not contain consecutive valid frames near the file head");
            } else {
               return null;
            }
         } else {
            aligned = new SequenceInputStream(new ByteArrayInputStream(probe, sync, probe.length - sync), body);
            body = null;
            encoded = AudioSystem.getAudioInputStream(new BufferedInputStream(aligned, 524288));
            aligned = null;
            LOGGER.debug("Custom MP3 safe seek: decodeFromHead=true target={}s frameOffset={} host={}", new Object[]{startOffsetSeconds, sync, url.getHost()});
            AudioInputStream result = openModernFallbackStream(encoded, request, startOffsetSeconds);
            encoded = null;
            return result;
         }
      } catch (IOException | UnsupportedAudioFileException var9) {
         closeQuietly(encoded);
         closeQuietly(aligned);
         closeQuietly(body);
         throw var9;
      } catch (RuntimeException var10) {
         closeQuietly(encoded);
         closeQuietly(aligned);
         closeQuietly(body);
         throw new IOException("custom MP3 safe seek failed", var10);
      }
   }

   private static boolean isLikelyMp3Url(URL url) {
      String path = url != null ? url.getPath() : null;
      return path != null && path.toLowerCase(Locale.ROOT).endsWith(".mp3");
   }

   private static InputStream openHttpRangeStream(URL url, long rangeOffset) throws IOException {
      try {
         HttpResponse<InputStream> response = sendHttpRequest(url, rangeOffset, false, 0);
         int status = response.statusCode();
         boolean validPartialResponse = status == 206
            && response.headers()
               .firstValue("Content-Range")
               .map(HttpRangeHeaders::parseContentRange)
               .map(range -> range.isKnown() && range.start() == rangeOffset)
               .orElse(false);
         if (!validPartialResponse && (rangeOffset != 0L || status != 200)) {
            InputStream body = response.body();

            try {
               throw new IOException("HTTP range request ignored or failed: status=" + status + " offset=" + rangeOffset);
            } finally {
               if (body != null) {
                  body.close();
               }
            }
         } else {
            InputStream body = response.body();
            if (body == null) {
               throw new IOException("empty audio response body");
            } else {
               return body;
            }
         }
      } catch (InterruptedException var11) {
         Thread.currentThread().interrupt();
         throw new IOException("interrupted while opening audio range", var11);
      }
   }

   private static HttpResponse<InputStream> sendHttpRequest(URL url, long rangeOffset, boolean probe, int redirects) throws IOException, InterruptedException {
      HttpResponse<InputStream> response = HTTP_CLIENT.send(requestBuilder(url, rangeOffset, probe).build(), BodyHandlers.ofInputStream());
      int status = response.statusCode();
      BiliRequestHeaders.recordBiliCdnResponse(url, status);
      if (HttpRangeHeaders.isRedirectStatus(status)) {
         InputStream body = response.body();
         if (body != null) {
            body.close();
         }

         if (redirects >= 5) {
            throw new IOException("too many HTTP redirects while opening audio");
         } else {
            String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("HTTP " + status + " redirect without Location"));
            URL redirected = URI.create(url.toString()).resolve(location).toURL();
            LOGGER.debug("HTTP audio redirect: {} -> {}", url.getHost(), redirected.getHost());
            return sendHttpRequest(redirected, rangeOffset, probe, redirects + 1);
         }
      } else {
         if (status == 200 || status == 206) {
            BiliCdnSelector.recordSuccess(response.uri().toString());
         }

         return response;
      }
   }

   private static Builder requestBuilder(URL url, long rangeOffset, boolean probe) {
      URL requestUrl;
      try {
         requestUrl = PlaybackSync.strip(url);
      } catch (MalformedURLException var6) {
         requestUrl = url;
      }

      Builder builder = HttpRangeHeaders.rangeRequest(requestUrl, rangeOffset, probe, Duration.ofSeconds(20L));
      BiliRequestHeaders.applyBiliCdnHeaders(builder, requestUrl);
      return builder;
   }

   private static AudioInputStream openModernFallbackStream(AudioInputStream stream, PlaybackRequest request, float startOffsetSeconds) throws UnsupportedAudioFileException, IOException {
      AudioInputStream pcm = toPcmStream(stream);
      PcmStartupSeekPolicy.Result seek = PcmStartupSeekPolicy.seekToCurrentPlayback(pcm, pcm.getFormat(), request, startOffsetSeconds);
      pcm = requireReadablePcm(pcm, "no decoded PCM after HTTP seek");
      if (startOffsetSeconds > 0.0F) {
         pcm = PcmFadeInAudioInputStream.wrap(pcm, 80);
      }

      StereoOpenALHandler stereo = new StereoOpenALHandler();
      stereo.setSampleRate((int)pcm.getFormat().getSampleRate());
      ClientAudioOutputRegistry.registerStereo(stereo, request.pos(), seek.timelineOffsetSeconds(), request.sessionId(), request.ownerId());
      LOGGER.debug(
         "HTTP 音频起播追赶完成: session={} captured={}ms effective={}ms setup={}ms passes={} offset={}s skippedBytes={} frameSize={} aligned={}",
         new Object[]{
            request.sessionId(),
            request.elapsedMillis(),
            seek.timelineOffsetMillis(),
            AudioStartupSync.elapsedSinceCaptureMillis(request.capturedNanos(), System.nanoTime()),
            seek.passes(),
            startOffsetSeconds,
            seek.skippedBytes(),
            seek.frameSize(),
            seek.isFrameAligned()
         }
      );
      return new OpenALTappedAudioInputStream(pcm, stereo, () -> {
         ClientAudioOutputRegistry.unregisterStereo(stereo);
         stereo.cleanup();
      });
   }

   private static AudioInputStream applyStartOffset(AudioInputStream stream, float startOffsetSeconds) throws UnsupportedAudioFileException, IOException {
      if (startOffsetSeconds <= 0.0F) {
         return stream;
      } else {
         AudioInputStream pcm = toPcmStream(stream);
         PcmStartupSeekPolicy.skipFixedOffset(pcm, pcm.getFormat(), startOffsetSeconds);
         return pcm;
      }
   }

   private static AudioInputStream requireReadablePcm(AudioInputStream stream, String message) throws IOException {
      byte[] first = new byte[32768];

      int read;
      do {
         read = stream.read(first);
      } while (read == 0);

      if (read < 0) {
         throw new EOFException(message);
      } else {
         return new AudioInputStream(new SequenceInputStream(new ByteArrayInputStream(first, 0, read), stream), stream.getFormat(), -1L);
      }
   }

   private static AudioInputStream toPcmStream(AudioInputStream stream) throws UnsupportedAudioFileException, IOException {
      AudioFormat sourceFormat = stream.getFormat();
      float sampleRate = sourceFormat.getSampleRate();
      int channels = Math.max(1, sourceFormat.getChannels());
      if (sampleRate <= 0.0F) {
         return stream;
      } else {
         AudioFormat pcmFormat = new AudioFormat(Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2, sampleRate, false);
         return AudioSystem.getAudioInputStream(pcmFormat, stream);
      }
   }

   private static Fmp4AudioStreamSeeker.StreamStart openFmp4StreamStart(URL url, PlaybackRequest request, float startOffsetSeconds) throws IOException {
      return Fmp4AudioStreamSeeker.open(url, request, startOffsetSeconds);
   }

   private static void closeQuietly(InputStream stream) {
      LifecycleClose.closeQuietly(stream);
   }

   private static void streamDecode(
      final URL url,
      final BlockingAudioPipe fallbackPipe,
      final AtomicReference<AudioDecodePipeline> pipelineRef,
      AtomicReference<Exception> errorRef,
      AtomicReference<InputStream> bodyRef,
      final AtomicBoolean closed,
      final CountDownLatch formatReady,
      final PlaybackRequest request,
      float startOffsetSeconds
   ) {
      final long[] decoded = new long[]{0L};
      final long[] mdatBytes = new long[]{0L};

      try {
         Fmp4AudioStreamSeeker.StreamStart streamStart = openFmp4StreamStart(url, request, startOffsetSeconds);
         final float effectiveStartOffsetSeconds = streamStart.startOffsetSeconds();

         try (InputStream body = streamStart.stream()) {
            bodyRef.set(body);
            if (!closed.get()) {
               Fmp4StreamParser parser = new Fmp4StreamParser();
               Fmp4StreamParser.ContainerKind containerKind = parser.parse(
                  body,
                  closed,
                  new Fmp4StreamParser.Callback() {
                     @Override
                     public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData) throws IOException, UnsupportedAudioFileException {
                        if (pipelineRef.get() == null) {
                           AudioPipelineFactory.Selection selection = AudioPipelineFactory.selectFmp4(
                              parseResult, Fmp4ToMp4Converter.listAudioCodecs(moovData), fallbackPipe, closed, request, effectiveStartOffsetSeconds
                           );
                           if (selection instanceof AudioPipelineFactory.Supported supported) {
                              HttpAudioStreamHandler.activatePipeline(url, pipelineRef, supported.pipeline(), formatReady);
                           } else if (selection instanceof AudioPipelineFactory.Unsupported unsupported) {
                              throw new UnsupportedAudioFileException(unsupported.reason());
                           }
                        }
                     }

                     @Override
                     public void onMoof(int[] sampleSizes, byte[] moofData) throws IOException {
                        AudioDecodePipeline pipeline = pipelineRef.get();
                        if (pipeline != null) {
                           pipeline.onMoof(sampleSizes);
                        }
                     }

                     @Override
                     public void onMdat(InputStream payload, long size) throws IOException {
                        AudioDecodePipeline pipeline = pipelineRef.get();
                        if (pipeline == null) {
                           Fmp4StreamParser.skipFully(payload, size);
                        } else {
                           decoded[0] = decoded[0] + pipeline.onMdat(payload, size);
                           mdatBytes[0] = mdatBytes[0] + Math.max(0L, size);
                        }
                     }

                     @Override
                     public void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException {
                        DolbyEc3Pipeline pipeline = HttpAudioStreamHandler.createRawDolbyPipeline(closed, request, effectiveStartOffsetSeconds);
                        HttpAudioStreamHandler.activatePipeline(url, pipelineRef, pipeline, formatReady);
                        decoded[0] = decoded[0] + pipeline.onRawStream(payload);
                     }
                  }
               );
               if (containerKind == Fmp4StreamParser.ContainerKind.OTHER_AUDIO) {
                  throw new HttpAudioStreamHandler.NonPipelineAudioException();
               }

               AudioDecodePipeline pipeline = pipelineRef.get();
               LOGGER.debug(
                  "Audio stream finished: decoded={} mdatBytes={} {}", new Object[]{decoded[0], mdatBytes[0], pipeline != null ? pipeline.statsSummary() : ""}
               );
               return;
            }
         }
      } catch (EOFException var37) {
         LOGGER.debug("Audio stream EOF: decoded={} mdatBytes={}", decoded[0], mdatBytes[0]);
         if (!closed.get() && decoded[0] == 0L && mdatBytes[0] == 0L) {
            BiliPlaybackDiagnostics.markFailed(url, var37);
            errorRef.set(new IOException("audio stream ended before any media bytes", var37));
         } else if (!closed.get()) {
            reportRecoverableStreamFailure(url, request, var37, decoded[0], mdatBytes[0]);
            return;
         }

         return;
      } catch (IOException var38) {
         if (!closed.get() && !isStreamEndException(var38)) {
            LOGGER.error("Audio stream IO failed", var38);
            BiliPlaybackDiagnostics.markFailed(url, var38);
            if (decoded[0] > 0L || mdatBytes[0] > 0L) {
               reportRecoverableStreamFailure(url, request, var38, decoded[0], mdatBytes[0]);
            }

            errorRef.set(var38);
         } else {
            LOGGER.debug(
               "Audio stream stopped: closed={} msg={} decoded={} mdatBytes={}", new Object[]{closed.get(), var38.getMessage(), decoded[0], mdatBytes[0]}
            );
            LOGGER.trace("Audio stream stop stack", var38);
            if (!closed.get() && (decoded[0] > 0L || mdatBytes[0] > 0L)) {
               reportRecoverableStreamFailure(url, request, var38, decoded[0], mdatBytes[0]);
               return;
            }
         }

         return;
      } catch (UnsupportedAudioFileException var39) {
         if (!closed.get()) {
            if (!(var39 instanceof HttpAudioStreamHandler.NonPipelineAudioException)) {
               LOGGER.warn("Audio stream unsupported: {}", var39.getMessage());
               BiliPlaybackDiagnostics.markFailed(url, var39);
            }

            errorRef.set(var39);
         }

         return;
      } catch (Exception var40) {
         if (!closed.get()) {
            LOGGER.error("Audio stream decode failed", var40);
            BiliPlaybackDiagnostics.markFailed(url, var40);
            errorRef.set(var40);
         }

         return;
      } finally {
         formatReady.countDown();
         AudioDecodePipeline pipeline = pipelineRef.get();
         if (pipeline != null) {
            try {
               pipeline.finish();
            } catch (IOException var35) {
               if (!closed.get()) {
                  LOGGER.debug("Audio pipeline finish failed: {}", var35.getMessage());
               }
            }
         }

         closeBody(bodyRef);
         fallbackPipe.closeWriter();
      }
   }

   private static boolean reportRecoverableStreamFailure(URL url, PlaybackRequest request, Throwable error, long decoded, long mdatBytes) {
      if (request != null && !request.playbackSessionId().isEmpty()) {
         boolean scheduled = SyncedStreamRecoveryRegistry.reportFailure(request.playbackSessionId().orElseThrow(), url, error);
         if (scheduled) {
            LOGGER.warn(
               "音频流播放中断，已安排自动续播: session={} decoded={} mdatBytes={} host={} reason={}",
               new Object[]{
                  request.sessionId(),
                  decoded,
                  mdatBytes,
                  url.getHost(),
                  error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "unknown"
               }
            );
         }

         return scheduled;
      } else {
         return false;
      }
   }

   private static DolbyEc3Pipeline createRawDolbyPipeline(AtomicBoolean closed, PlaybackRequest request, float startOffsetSeconds) throws UnsupportedAudioFileException {
      if (request != null && Eac3NativeDecoder.isNativeAvailable()) {
         BlockPos sourcePos = request.pos();
         return new DolbyEc3Pipeline("raw", closed, sourcePos, startOffsetSeconds, request.startOffsetSeconds(), request.sessionId(), request.ownerId());
      } else {
         throw new UnsupportedAudioFileException("raw E-AC-3 requires Dolby playback and native decoder support");
      }
   }

   private static void activatePipeline(URL url, AtomicReference<AudioDecodePipeline> pipelineRef, AudioDecodePipeline pipeline, CountDownLatch formatReady) {
      if (!pipelineRef.compareAndSet(null, pipeline)) {
         pipeline.close();
      } else {
         BiliPlaybackDiagnostics.updateFormat(url, pipeline.container(), pipeline.codec(), pipeline.format(), pipeline.detail());
         formatReady.countDown();
      }
   }

   private static AudioInputStream silentStream(
      AudioFormat format,
      final AtomicBoolean closed,
      final Thread worker,
      final URL url,
      final AtomicReference<InputStream> bodyRef,
      final BlockingAudioPipe fallbackPipe,
      final AudioDecodePipeline pipeline,
      final HttpAudioStreamHandler.ActiveStreamControl streamControl
   ) {
      return new AudioInputStream(fallbackPipe, format, -1L) {
         @Override
         public int read() {
            return closed.get() ? -1 : 0;
         }

         @Override
         public int read(byte[] b, int off, int len) {
            if (closed.get()) {
               return -1;
            } else if (len <= 0) {
               return 0;
            } else {
               int fill = Math.min(len, b.length - off);
               Arrays.fill(b, off, off + fill, (byte)0);
               return fill;
            }
         }

         @Override
         public void close() throws IOException {
            HttpAudioStreamHandler.closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline, streamControl);
            super.close();
         }
      };
   }

   private static AudioInputStream managedStream(
      final AudioInputStream delegate,
      final AtomicBoolean closed,
      final Thread worker,
      final URL url,
      final AtomicReference<InputStream> bodyRef,
      final BlockingAudioPipe fallbackPipe,
      final AudioDecodePipeline pipeline,
      final HttpAudioStreamHandler.ActiveStreamControl streamControl
   ) {
      return new AudioInputStream(delegate, delegate.getFormat(), -1L) {
         @Override
         public void close() throws IOException {
            IOException error = null;

            try {
               HttpAudioStreamHandler.closeWorker(url, closed, bodyRef, worker, fallbackPipe, pipeline, streamControl);
            } catch (IOException var3) {
               error = var3;
            }

            try {
               delegate.close();
            } catch (IOException var4) {
               if (error != null) {
                  error.addSuppressed(var4);
               } else {
                  error = var4;
               }
            }

            if (error != null) {
               throw error;
            }
         }
      };
   }

   private static void awaitFormat(
      URL url, AtomicBoolean closed, AtomicReference<InputStream> bodyRef, Thread worker, CountDownLatch formatReady, AtomicReference<Exception> errorRef
   ) throws IOException, UnsupportedAudioFileException {
      long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(FORMAT_WAIT_SECONDS);

      try {
         while (!formatReady.await(100L, TimeUnit.MILLISECONDS)) {
            Exception failure = errorRef.get();
            if (failure instanceof UnsupportedAudioFileException unsupported) {
               throw unsupported;
            }

            if (failure instanceof IOException io) {
               throw io;
            }

            if (failure != null) {
               throw new IOException("Audio stream handling failed", failure);
            }

            if (System.nanoTime() >= deadlineNanos) {
               closed.set(true);
               closeBody(bodyRef);
               worker.interrupt();
               BiliPlaybackDiagnostics.markClosed(url);
               throw new IOException("timed out waiting for audio format");
            }
         }

         throwIfFailed(errorRef);
      } catch (InterruptedException var10) {
         closed.set(true);
         closeBody(bodyRef);
         worker.interrupt();
         Thread.currentThread().interrupt();
         BiliPlaybackDiagnostics.markClosed(url);
         throw new IOException("interrupted while loading audio stream", var10);
      }
   }

   private static void throwIfFailed(AtomicReference<Exception> errorRef) throws IOException, UnsupportedAudioFileException {
      Exception err = errorRef.get();
      if (err != null) {
         if (err instanceof IOException io) {
            throw io;
         } else if (err instanceof UnsupportedAudioFileException unsupported) {
            throw unsupported;
         } else {
            throw new IOException("Audio stream handling failed", err);
         }
      }
   }

   private static void closeWorker(
      URL url,
      AtomicBoolean closed,
      AtomicReference<InputStream> bodyRef,
      Thread worker,
      BlockingAudioPipe fallbackPipe,
      AudioDecodePipeline pipeline,
      HttpAudioStreamHandler.ActiveStreamControl streamControl
   ) throws IOException {
      if (closed.compareAndSet(false, true)) {
         BiliPlaybackDiagnostics.markClosed(url);
      }

      closeBody(bodyRef);
      fallbackPipe.closeWriter();
      fallbackPipe.close();
      worker.interrupt();
      if (pipeline != null) {
         pipeline.close();
      }

      LifecycleClose.join(worker, 2000L);
      if (streamControl != null) {
         streamControl.unregister();
      }
   }

   private static void closeBody(AtomicReference<InputStream> bodyRef) {
      InputStream body = bodyRef.getAndSet(null);
      LifecycleClose.closeQuietly(body);
   }

   private static boolean isStreamEndException(IOException e) {
      String msg = e.getMessage();
      if (msg == null || !msg.contains("closed") && !msg.contains("EOF") && !msg.contains("Stream Closed")) {
         for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof EOFException) {
               return true;
            }
         }

         return false;
      } else {
         return true;
      }
   }

   private static boolean hasRequestContext(URL url) {
      return PlaybackSync.parseMediaRequestToken(url.toString()).map(REQUESTS::containsToken).orElse(false);
   }

   private static boolean isBiliCdnHost(URL url) {
      String host = url.getHost();
      if (host == null) {
         return false;
      } else {
         String lower = host.toLowerCase(Locale.ROOT);
         return lower.contains("bilibili") || lower.contains("bilivideo") || lower.contains("hdslb") || lower.contains("mcdn");
      }
   }

   private static void closeStaleModernStreams(BlockPos pos, Optional<PlaybackSessionId> playbackSessionId, UUID minecartUuid) {
      if ((pos != null || minecartUuid != null) && playbackSessionId != null && !playbackSessionId.isEmpty()) {
         PlaybackSessionId currentSessionId = playbackSessionId.orElseThrow();

         for (HttpAudioStreamHandler.ActiveStreamControl control : ACTIVE_MODERN_STREAMS) {
            boolean sameSource = minecartUuid != null
               ? minecartUuid.equals(control.minecartUuid)
               : control.minecartUuid == null && control.pos != null && control.pos.equals(pos);
            if (sameSource && !playbackSessionId.equals(control.playbackSessionId)) {
               LOGGER.debug(
                  "关闭旧现代音频流: pos={} oldSession={} newSession={}",
                  new Object[]{pos, control.playbackSessionId.<String>map(session -> session.value()).orElse(""), currentSessionId}
               );
               control.close();
            }
         }
      }
   }

   public int getPriority() {
      return 100;
   }

   private static final class ActiveStreamControl {
      private final URL url;
      private final BlockPos pos;
      private final Optional<PlaybackSessionId> playbackSessionId;
      private final UUID minecartUuid;
      private final AtomicBoolean closed;
      private final AtomicReference<InputStream> bodyRef;
      private final Thread worker;
      private final BlockingAudioPipe fallbackPipe;
      private final AtomicReference<AudioDecodePipeline> pipelineRef;
      private final CountDownLatch formatReady;

      private ActiveStreamControl(
         URL url,
         BlockPos pos,
         Optional<PlaybackSessionId> playbackSessionId,
         UUID minecartUuid,
         AtomicBoolean closed,
         AtomicReference<InputStream> bodyRef,
         Thread worker,
         BlockingAudioPipe fallbackPipe,
         AtomicReference<AudioDecodePipeline> pipelineRef,
         CountDownLatch formatReady
      ) {
         this.url = url;
         this.pos = AudioUtils.copyPos(pos);
         this.playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.minecartUuid = minecartUuid;
         this.closed = closed;
         this.bodyRef = bodyRef;
         this.worker = worker;
         this.fallbackPipe = fallbackPipe;
         this.pipelineRef = pipelineRef;
         this.formatReady = formatReady;
      }

      private void close() {
         try {
            this.formatReady.countDown();
            HttpAudioStreamHandler.closeWorker(this.url, this.closed, this.bodyRef, this.worker, this.fallbackPipe, this.pipelineRef.get(), this);
         } catch (IOException var2) {
            HttpAudioStreamHandler.LOGGER.debug("Failed to close modern audio stream during client cleanup: {}", var2.getMessage());
         }
      }

      private void unregister() {
         HttpAudioStreamHandler.ACTIVE_MODERN_STREAMS.remove(this);
      }
   }

   private static final class NonPipelineAudioException extends UnsupportedAudioFileException {
      private NonPipelineAudioException() {
         super("audio container is not handled by the fMP4/raw E-AC-3 pipeline");
      }
   }

   public record RegisteredRequest(String url, Optional<MediaRequestToken> requestToken) {
      public RegisteredRequest(String url, Optional<MediaRequestToken> requestToken) {
         url = url == null ? "" : url;
         requestToken = requestToken == null ? Optional.empty() : requestToken;
         this.url = url;
         this.requestToken = requestToken;
      }
   }
}
