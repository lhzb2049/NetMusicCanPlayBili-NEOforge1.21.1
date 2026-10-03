package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpTransport;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpClient.Redirect;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public final class AudioDurationProbe {
   private static final int PROBE_BYTES = 131072;
   private static final int MAX_REDIRECTS = 5;
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(Redirect.NEVER).connectTimeout(Duration.ofSeconds(8L)).build();
   private static final ExecutorService PROBE_EXECUTOR = Executors.newFixedThreadPool(2, NetMusicThreadFactory.daemon("audio-duration-probe"));
   private static final int[] MP3_MPEG1_LAYER1_BITRATES = new int[]{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0};
   private static final int[] MP3_MPEG1_LAYER2_BITRATES = new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0};
   private static final int[] MP3_MPEG1_LAYER3_BITRATES = new int[]{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
   private static final int[] MP3_MPEG2_LAYER1_BITRATES = new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0};
   private static final int[] MP3_MPEG2_LAYER23_BITRATES = new int[]{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};

   private AudioDurationProbe() {
   }

   public static CancellableTaskFuture<OptionalLong> probeMillisAsync(String rawUrl) {
      return probeMillisAsync(rawUrl, HTTP_CLIENT, HttpRequestCloseDiagnostics.global(), PROBE_EXECUTOR, true);
   }

   static CancellableTaskFuture<OptionalLong> probeMillisAsync(
      String rawUrl, HttpClient client, HttpRequestCloseDiagnostics diagnostics, ExecutorService executor
   ) {
      return probeMillisAsync(rawUrl, client, diagnostics, executor, false);
   }

   private static CancellableTaskFuture<OptionalLong> probeMillisAsync(
      String rawUrl, HttpClient client, HttpRequestCloseDiagnostics diagnostics, ExecutorService executor, boolean logResult
   ) {
      AudioDurationProbe.ProbeResources resources = new AudioDurationProbe.ProbeResources();
      return CancellableTaskFuture.submit(executor, () -> {
         OptionalLong var5x;
         try {
            var5x = probeMillis(rawUrl, client, diagnostics, resources, logResult);
         } finally {
            resources.closeActiveBody();
         }

         return var5x;
      }, resources::cancel);
   }

   private static OptionalLong probeMillis(
      String rawUrl, HttpClient client, HttpRequestCloseDiagnostics diagnostics, AudioDurationProbe.ProbeResources resources, boolean logResult
   ) {
      String url = PlaybackSync.strip(rawUrl);
      if (url != null && !url.isBlank()) {
         try {
            AudioDurationProbe.ProbeResponse response = probe(url, client, diagnostics, resources);
            long totalBytes = response.totalBytes();
            AudioDurationProbe.Mp3Frame frame = findMp3Frame(response.body());
            if (totalBytes > 0L && frame != null && frame.bitrateKbps() > 0) {
               long audioBytes = Math.max(1L, totalBytes - Math.max(0, frame.offset()));
               long millis = Math.round(audioBytes * 8000.0 / (frame.bitrateKbps() * 1000.0));
               if (millis <= 0L) {
                  return OptionalLong.empty();
               } else {
                  if (logResult) {
                     logger()
                        .debug(
                           "纯音频预览本地估算时长: {}ms bitrate={}kbps bytes={} host={}", new Object[]{millis, frame.bitrateKbps(), totalBytes, response.uri().getHost()}
                        );
                  }

                  return OptionalLong.of(millis);
               }
            } else {
               return OptionalLong.empty();
            }
         } catch (CancellationException var14) {
            throw var14;
         } catch (Exception var15) {
            if (!resources.isCancelled() && !Thread.currentThread().isInterrupted()) {
               if (logResult) {
                  logger().debug("纯音频预览本地时长探测失败: {} reason={}", url, var15.toString());
               }

               return OptionalLong.empty();
            } else {
               Thread.currentThread().interrupt();
               throw new CancellationException("audio duration probe cancelled");
            }
         }
      } else {
         return OptionalLong.empty();
      }
   }

   private static AudioDurationProbe.ProbeResponse probe(
      String url, HttpClient client, HttpRequestCloseDiagnostics diagnostics, AudioDurationProbe.ProbeResources resources
   ) throws IOException {
      URI current = URI.create(url);

      for (int redirects = 0; redirects <= 5; redirects++) {
         resources.throwIfCancelled();
         HttpRequest request = request(current.toString());
         long operationId = diagnostics.begin("audio-duration-probe", safeHost(current), 0L, 131071L, System.nanoTime());
         CancellableHttpTransport.Response response = CancellableHttpTransport.send(client, request, diagnostics, operationId);
         InputStream body = response.body();
         resources.attachBody(body);
         int status = response.statusCode();
         URI next = null;
         AudioDurationProbe.ProbeResponse result = null;

         try {
            resources.throwIfCancelled();
            if (HttpRangeHeaders.isRedirectStatus(status)) {
               if (redirects >= 5) {
                  throw new IOException("too many redirects while probing audio duration");
               }

               String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("HTTP " + status + " redirect without Location"));
               next = current.resolve(location);
            } else {
               if (status != 200 && status != 206) {
                  throw new IOException("HTTP " + status + " while probing audio duration");
               }

               long totalBytes = response.headers()
                  .firstValue("Content-Range")
                  .flatMap(HttpRangeHeaders::parseContentRangeTotal)
                  .orElseGet(() -> response.headers().firstValueAsLong("Content-Length").orElse(-1L));
               byte[] bytes = body == null ? new byte[0] : body.readNBytes(131072);
               resources.throwIfCancelled();
               result = new AudioDurationProbe.ProbeResponse(current, bytes, totalBytes);
            }
         } finally {
            resources.releaseBody(body);
         }

         if (result != null) {
            return result;
         }

         current = Objects.requireNonNull(next, "redirect target");
      }

      throw new IOException("too many redirects while probing audio duration");
   }

   private static HttpRequest request(String url) {
      return HttpRequest.newBuilder(URI.create(url))
         .timeout(Duration.ofSeconds(12L))
         .GET()
         .header("Range", "bytes=0-131071")
         .header("User-Agent", "Mozilla/5.0 NetMusicCanPlayBili")
         .build();
   }

   private static AudioDurationProbe.Mp3Frame findMp3Frame(byte[] bytes) {
      int start = id3v2Size(bytes);

      for (int i = Math.max(0, start); i + 3 < bytes.length; i++) {
         AudioDurationProbe.Mp3Frame frame = parseMp3Frame(bytes, i);
         if (frame != null) {
            return frame;
         }
      }

      return null;
   }

   private static int id3v2Size(byte[] bytes) {
      return bytes.length >= 10 && bytes[0] == 73 && bytes[1] == 68 && bytes[2] == 51
         ? 10 + ((bytes[6] & 127) << 21) + ((bytes[7] & 127) << 14) + ((bytes[8] & 127) << 7) + (bytes[9] & 127)
         : 0;
   }

   private static AudioDurationProbe.Mp3Frame parseMp3Frame(byte[] bytes, int offset) {
      int b0 = bytes[offset] & 255;
      int b1 = bytes[offset + 1] & 255;
      int b2 = bytes[offset + 2] & 255;
      if (b0 == 255 && (b1 & 224) == 224) {
         int version = b1 >> 3 & 3;
         int layer = b1 >> 1 & 3;
         int bitrateIndex = b2 >> 4 & 15;
         if (version != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 15) {
            int bitrateKbps = mp3BitrateKbps(version, layer, bitrateIndex);
            return bitrateKbps > 0 ? new AudioDurationProbe.Mp3Frame(offset, bitrateKbps) : null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static int mp3BitrateKbps(int version, int layer, int index) {
      if (version == 3) {
         if (layer == 3) {
            return MP3_MPEG1_LAYER1_BITRATES[index];
         } else {
            return layer == 2 ? MP3_MPEG1_LAYER2_BITRATES[index] : MP3_MPEG1_LAYER3_BITRATES[index];
         }
      } else {
         return layer == 3 ? MP3_MPEG2_LAYER1_BITRATES[index] : MP3_MPEG2_LAYER23_BITRATES[index];
      }
   }

   private static String safeHost(URI uri) {
      String host = uri != null ? uri.getHost() : null;
      return host != null && !host.isBlank() ? host : "<unknown>";
   }

   private static Logger logger() {
      return AudioDurationProbe.LoggerHolder.INSTANCE;
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }

   private record Mp3Frame(int offset, int bitrateKbps) {
   }

   private static final class ProbeResources {
      private final AtomicBoolean cancelled = new AtomicBoolean();
      private final AtomicReference<InputStream> activeBody = new AtomicReference<>();

      private void attachBody(InputStream body) {
         if (body != null) {
            if (!this.activeBody.compareAndSet(null, body)) {
               MediaCloseExecutor.closeAsyncStrict(body, "unclaimed audio duration probe body");
               throw new IllegalStateException("audio duration probe already owns a response body");
            } else if (this.cancelled.get() && this.activeBody.compareAndSet(body, null)) {
               MediaCloseExecutor.closeAsyncStrict(body, "cancelled audio duration probe body");
               throw new CancellationException("audio duration probe cancelled");
            }
         }
      }

      private void releaseBody(InputStream body) throws IOException {
         if (body != null && this.activeBody.compareAndSet(body, null)) {
            body.close();
         }
      }

      private void closeActiveBody() {
         InputStream body = this.activeBody.getAndSet(null);
         if (body != null) {
            MediaCloseExecutor.closeAsyncStrict(body, "audio duration probe response body");
         }
      }

      private void cancel() {
         this.cancelled.set(true);
         this.closeActiveBody();
      }

      private boolean isCancelled() {
         return this.cancelled.get();
      }

      private void throwIfCancelled() {
         if (this.cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("audio duration probe cancelled");
         }
      }
   }

   private record ProbeResponse(URI uri, byte[] body, long totalBytes) {
   }
}
