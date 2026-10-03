package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.api.implement.M3u8Handler;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacOpenALPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AacPcmPipeline;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioDecodePipeline;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.stream.CancellableHttpTransport;
import com.zhongbai233.net_music_can_play_bili.media.stream.FlvStreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRequestCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveReconnectPolicy;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public final class BiliLiveAudioStreamHandler implements IAudioStreamHandler {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int PRIORITY = 300;
   private static final int PIPE_INITIAL_BYTES = 524288;
   private static final int PIPE_MAX_BYTES = 4194304;
   private static final int READ_BUFFER_BYTES = 65536;
   private static final int FORMAT_WAIT_SECONDS = Math.max(5, NcpbSystemProperties.intValue("ncpb.bili.live.format_wait_seconds", 20));
   private static final long WORKER_JOIN_TIMEOUT_MILLIS = 2000L;

   public boolean canHandle(URL url) {
      return url != null && !BiliLiveRoomInput.roomIdFromPlaceholder(PlaybackSync.strip(url.toString())).isEmpty();
   }

   public int getPriority() {
      return 300;
   }

   public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
      String roomId = BiliLiveRoomInput.roomIdFromPlaceholder(PlaybackSync.strip(url.toString()));
      if (roomId.isEmpty()) {
         throw new UnsupportedAudioFileException("不是 B站直播占位地址: " + url);
      } else {
         PlaybackRequest request = HttpAudioStreamHandler.consumeRegisteredRequest(url.toString());
         if (request == null && LiveOfflineBackoff.isBlocked(roomId)) {
            throw new IOException("直播间 " + roomId + " 未开播（退避重试中）");
         } else {
            BiliLiveStreamResolver.LiveRoom room = BiliLiveStreamResolver.resolve(roomId);
            publishLiveMetadata(request, room);
            if (!room.isLive()) {
               LiveOfflineBackoff.recordOffline(roomId);
               LiveOfflineBackoff.recordOffline(room.roomId());
               showOfflineOverlay(roomId);
               throw new IOException("直播间 " + roomId + " " + BiliLiveStreamResolver.describeLiveStatus(room.liveStatus()));
            } else {
               LiveOfflineBackoff.clear(roomId);
               LiveOfflineBackoff.clear(room.roomId());
               if (room.flvUrls().isEmpty()) {
                  List<String> hlsUrls = room.hlsUrls();
                  if (hlsUrls.isEmpty()) {
                     throw new IOException("直播间 " + roomId + " 没有可用的直播流地址");
                  } else {
                     LOGGER.info("直播间 {} 没有 FLV 地址，回退到 NetMusic 的 m3u8 播放路径{}", roomId, request != null ? "（音响/耳机中继在该模式下不可用）" : "");
                     return new M3u8Handler().handle(URI.create(hlsUrls.get(0)).toURL());
                  }
               } else {
                  LOGGER.info(
                     "开始播放 B站直播: room={} realRoom={} status={} mode={} flvCandidates={}",
                     new Object[]{
                        roomId,
                        room.roomId(),
                        BiliLiveStreamResolver.describeLiveStatus(room.liveStatus()),
                        request != null ? "openal" : "pcm",
                        room.flvUrls().size()
                     }
                  );
                  return openFlvStream(room.roomId(), room, request);
               }
            }
         }
      }
   }

   private static void showOfflineOverlay(String roomId) {
      try {
         Minecraft minecraft = Minecraft.getInstance();
         minecraft.execute(
            () -> {
               if (minecraft.gui != null) {
                  minecraft.gui
                     .setOverlayMessage(
                        Component.translatable(
                           "message.net_music_can_play_bili.live_streamer.room_offline_waiting", new Object[]{roomId, LiveOfflineBackoff.retryMillis() / 1000L}
                        ),
                        false
                     );
               }
            }
         );
      } catch (RuntimeException var2) {
      }
   }

   private static AudioInputStream openFlvStream(String roomId, BiliLiveStreamResolver.LiveRoom initialRoom, PlaybackRequest request) throws IOException {
      final BiliLiveAudioStreamHandler.LiveSession session = new BiliLiveAudioStreamHandler.LiveSession(roomId, initialRoom, request);
      session.start();

      try {
         AudioFormat format = session.awaitFormat();
         LOGGER.debug(
            "B站直播音频格式就绪: room={} format={}Hz/{}ch/{}bit", new Object[]{roomId, format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits()}
         );
         return session.usesOpenAlOutput() ? silentStream(session, format) : new AudioInputStream(session.pipe, format, -1L) {
            @Override
            public void close() throws IOException {
               session.close();
               super.close();
            }
         };
      } catch (IOException var5) {
         session.close();
         throw var5;
      }
   }

   private static AudioInputStream silentStream(final BiliLiveAudioStreamHandler.LiveSession session, AudioFormat format) {
      return new AudioInputStream(session.pipe, format, -1L) {
         @Override
         public int read() {
            return session.closed.get() ? -1 : 0;
         }

         @Override
         public int read(byte[] b, int off, int len) {
            if (session.closed.get()) {
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
            session.close();
            super.close();
         }
      };
   }

   private static InputStream openLiveBody(URI uri) throws IOException {
      URL target = uri.toURL();
      Builder builder = HttpRequest.newBuilder(uri).GET();
      BiliRequestHeaders.applyLiveHeaders(builder, target);
      HttpRequestCloseDiagnostics diagnostics = HttpRequestCloseDiagnostics.global();
      long operation = diagnostics.begin("live-flv", target.getHost(), -1L, -1L, System.nanoTime());
      CancellableHttpTransport.Response response = CancellableHttpTransport.send(BiliWbiSigner.HTTP, builder.build(), diagnostics, operation);
      BiliRequestHeaders.recordBiliCdnResponse(target, response.statusCode());
      InputStream body = response.body();
      if (response.statusCode() != 200) {
         LifecycleClose.closeQuietly(body);
         throw new IOException("直播流 HTTP " + response.statusCode() + " host=" + target.getHost());
      } else if (body == null) {
         throw new IOException("直播流响应为空: host=" + target.getHost());
      } else {
         return new BufferedInputStream(body, 65536);
      }
   }

   private static void publishLiveMetadata(PlaybackRequest request, BiliLiveStreamResolver.LiveRoom room) {
      if (request != null && request.pos() != null && room != null && !request.playbackSessionId().isEmpty()) {
         BlockPos pos = request.pos();
         BiliLiveStreamResolver.LiveMetadata metadata = room.metadata();
         LiveRoomMetadataRegistry.publish(
            new LiveRoomMetadataRegistry.SourceKey(pos.getX(), pos.getY(), pos.getZ()),
            request.playbackSessionId().orElseThrow(),
            room.roomId(),
            metadata.title(),
            metadata.parentAreaName(),
            metadata.areaName(),
            room.liveStatus()
         );
      }
   }

   private static void removeLiveMetadata(PlaybackRequest request) {
      if (request != null && request.pos() != null) {
         PlaybackSessionId sessionId = request.playbackSessionId().orElse(null);
         if (sessionId != null) {
            BlockPos pos = request.pos();
            LiveRoomMetadataRegistry.remove(new LiveRoomMetadataRegistry.SourceKey(pos.getX(), pos.getY(), pos.getZ()), sessionId);
         }
      }
   }

   private static final class LiveSession {
      private final String roomId;
      private final PlaybackRequest request;
      private final LiveVideoSampleBus videoBus;
      private final BlockingAudioPipe pipe = new BlockingAudioPipe(524288, 4194304);
      private final AtomicBoolean closed = new AtomicBoolean();
      private final AtomicReference<AudioDecodePipeline> pipelineRef = new AtomicReference<>();
      private final AtomicReference<byte[]> ascRef = new AtomicReference<>();
      private final AtomicReference<InputStream> bodyRef = new AtomicReference<>();
      private final AtomicReference<Exception> errorRef = new AtomicReference<>();
      private final CountDownLatch formatReady = new CountDownLatch(1);
      private final Thread worker;
      private volatile BiliLiveStreamResolver.LiveRoom room;

      private LiveSession(String roomId, BiliLiveStreamResolver.LiveRoom initialRoom, PlaybackRequest request) {
         this.roomId = roomId;
         this.room = initialRoom;
         this.request = request;
         this.videoBus = request != null && !request.sessionId().isBlank() ? LiveVideoSampleBus.register(request.sessionId()) : null;
         if (this.videoBus != null) {
            BiliLiveAudioStreamHandler.LOGGER.debug("直播视频样本总线注册: session={}", this.videoBus.key());
         }

         this.worker = NetMusicThreadFactory.daemonThread("BiliLiveAudio-" + roomId, this::runLiveLoop);
      }

      private boolean usesOpenAlOutput() {
         AudioDecodePipeline pipeline = this.pipelineRef.get();
         return pipeline != null && pipeline.usesOpenAlOutput();
      }

      private void start() {
         this.worker.start();
      }

      private AudioFormat awaitFormat() throws IOException {
         try {
            if (!this.formatReady.await(BiliLiveAudioStreamHandler.FORMAT_WAIT_SECONDS, TimeUnit.SECONDS)) {
               throw new IOException("等待直播音频格式超时: room=" + this.roomId);
            }
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
            throw new IOException("等待直播音频格式时被中断", var4);
         }

         AudioDecodePipeline pipeline = this.pipelineRef.get();
         if (pipeline != null) {
            return pipeline.format();
         } else {
            Exception failure = this.errorRef.get();
            if (failure instanceof IOException io) {
               throw io;
            } else {
               throw new IOException("无法打开直播音频流: room=" + this.roomId, failure);
            }
         }
      }

      private void runLiveLoop() {
         LiveReconnectPolicy policy = new LiveReconnectPolicy();

         try {
            while (!this.closed.get()) {
               long startedAt = System.nanoTime();
               boolean fatal = this.connectOnce();
               if (fatal || this.closed.get()) {
                  break;
               }

               this.room = null;
               long elapsedMillis = (System.nanoTime() - startedAt) / 1000000L;
               long delay = policy.onStreamEnded(elapsedMillis);
               if (delay == -1L) {
                  BiliLiveAudioStreamHandler.LOGGER.warn("B站直播连续 {} 次连接失败，停止重连: room={}", policy.consecutiveFailures(), this.roomId);
                  break;
               }

               BiliLiveAudioStreamHandler.LOGGER.info("B站直播流中断，{}ms 后重连: room={} 本次连接时长={}ms", new Object[]{delay, this.roomId, elapsedMillis});
               if (!this.sleepQuietly(delay)) {
                  break;
               }
            }
         } finally {
            this.formatReady.countDown();
            this.pipe.closeWriter();
            if (this.videoBus != null) {
               this.videoBus.close();
            }

            this.finishPipeline();
            BiliLiveAudioStreamHandler.removeLiveMetadata(this.request);
         }
      }

      private void finishPipeline() {
         AudioDecodePipeline pipeline = this.pipelineRef.get();
         if (pipeline != null) {
            try {
               pipeline.finish();
            } catch (IOException var3) {
               BiliLiveAudioStreamHandler.LOGGER.debug("直播音频管线冲刷失败: room={} reason={}", this.roomId, var3.getMessage());
            }

            pipeline.close();
         }
      }

      private boolean connectOnce() {
         try {
            BiliLiveStreamResolver.LiveRoom current = this.room;
            if (current == null) {
               current = BiliLiveStreamResolver.resolve(this.roomId);
               this.room = current;
               BiliLiveAudioStreamHandler.publishLiveMetadata(this.request, current);
            }

            if (!current.isLive()) {
               BiliLiveAudioStreamHandler.LOGGER
                  .info("B站直播已结束: room={} status={}", this.roomId, BiliLiveStreamResolver.describeLiveStatus(current.liveStatus()));
               LiveOfflineBackoff.recordOffline(this.roomId);
               BiliLiveAudioStreamHandler.showOfflineOverlay(this.roomId);
               return true;
            } else {
               List<String> candidates = current.flvUrls();
               if (candidates.isEmpty()) {
                  this.recordFailure(new IOException("直播间 " + this.roomId + " 没有可用的 FLV 地址"));
                  return this.pipelineRef.get() == null;
               } else {
                  this.streamCandidates(candidates);
                  return false;
               }
            }
         } catch (UnsupportedAudioFileException var3) {
            this.recordFailure(var3);
            return true;
         } catch (IOException var4) {
            this.recordFailure(var4);
            return false;
         } catch (RuntimeException var5) {
            this.recordFailure(var5);
            return true;
         }
      }

      private void streamCandidates(List<String> candidates) throws IOException, UnsupportedAudioFileException {
         IOException lastError = null;

         for (String candidate : candidates) {
            if (this.closed.get()) {
               return;
            }

            URI uri;
            try {
               uri = URI.create(candidate);
            } catch (IllegalArgumentException var9) {
               lastError = new IOException("直播流地址无法解析: " + candidate, var9);
               continue;
            }

            try {
               long frames = this.streamOnce(uri);
               if (frames > 0L || this.closed.get()) {
                  return;
               }

               lastError = new IOException("直播流没有产生任何音频帧: host=" + uri.getHost());
            } catch (IOException var8) {
               lastError = var8;
               BiliLiveAudioStreamHandler.LOGGER
                  .debug("B站直播 CDN 连接失败，尝试下一个: room={} host={} reason={}", new Object[]{this.roomId, uri.getHost(), var8.getMessage()});
            }
         }

         if (lastError != null) {
            throw lastError;
         }
      }

      private long streamOnce(URI uri) throws IOException, UnsupportedAudioFileException {
         InputStream body = BiliLiveAudioStreamHandler.openLiveBody(uri);
         this.bodyRef.set(body);
         if (this.videoBus != null) {
            this.videoBus.beginConnection();
         }

         long var3;
         try {
            var3 = new FlvStreamParser()
               .parse(
                  body,
                  this.closed::get,
                  new FlvStreamParser.Callback() {
                     @Override
                     public void onAacSequenceHeader(byte[] audioSpecificConfig) throws UnsupportedAudioFileException {
                        LiveSession.this.acceptSequenceHeader(audioSpecificConfig);
                     }

                     @Override
                     public void onAacFrame(byte[] frame, long timestampMillis) throws IOException {
                        AudioDecodePipeline pipeline = LiveSession.this.pipelineRef.get();
                        if (pipeline != null) {
                           if (LiveSession.this.videoBus != null && !LiveSession.this.videoBus.hasAudioAnchor()) {
                              long fedMillis = ClientAudioOutputRegistry.getAudioTimeline(LiveSession.this.request.pos()).fedMillis();
                              LiveSession.this.videoBus.setAudioAnchor(timestampMillis, Math.max(0L, fedMillis));
                              BiliLiveAudioStreamHandler.LOGGER
                                 .debug(
                                    "直播视频时间锚已建立: session={} flvTs={}ms fed={}ms",
                                    new Object[]{LiveSession.this.videoBus.key(), timestampMillis, Math.max(0L, fedMillis)}
                                 );
                           }

                           pipeline.onAudioFrame(frame);
                        }
                     }

                     @Override
                     public boolean wantsVideo() {
                        return LiveSession.this.videoBus != null;
                     }

                     @Override
                     public void onAvcSequenceHeader(byte[] avcConfig) {
                        LiveSession.this.videoBus.publishConfig(avcConfig);
                     }

                     @Override
                     public void onAvcSample(byte[] sample, long dtsMillis, int compositionTimeMillis, boolean keyframe) {
                        LiveSession.this.videoBus.pushSample(sample, dtsMillis, compositionTimeMillis, keyframe);
                     }
                  }
               );
         } finally {
            this.bodyRef.compareAndSet(body, null);
            LifecycleClose.closeQuietly(body);
         }

         return var3;
      }

      private void acceptSequenceHeader(byte[] audioSpecificConfig) throws UnsupportedAudioFileException {
         byte[] asc = (byte[])audioSpecificConfig.clone();
         byte[] known = this.ascRef.get();
         if (known != null) {
            if (!Arrays.equals(known, asc)) {
               throw new UnsupportedAudioFileException("直播音频参数发生变化，需要重新开始播放");
            }
         } else {
            AudioDecodePipeline pipeline = (AudioDecodePipeline)(this.request != null
               ? new AacOpenALPipeline(asc, this.closed, this.request.pos(), 0.0F, 0.0F, this.request.sessionId(), this.request.ownerId())
               : new AacPcmPipeline(asc, this.pipe));
            this.ascRef.set(asc);
            this.pipelineRef.set(pipeline);
            this.formatReady.countDown();
         }
      }

      private void recordFailure(Exception error) {
         if (!this.closed.get()) {
            if (this.pipelineRef.get() == null) {
               this.errorRef.compareAndSet(null, error);
               BiliLiveAudioStreamHandler.LOGGER.warn("B站直播音频打开失败: room={} reason={}", this.roomId, error.toString());
            } else {
               BiliLiveAudioStreamHandler.LOGGER.debug("B站直播音频流中断: room={} reason={}", this.roomId, error.toString());
            }
         }
      }

      private boolean sleepQuietly(long delayMillis) {
         if (delayMillis <= 0L) {
            return true;
         } else {
            try {
               Thread.sleep(delayMillis);
               return !this.closed.get();
            } catch (InterruptedException var4) {
               Thread.currentThread().interrupt();
               return false;
            }
         }
      }

      private void close() {
         this.closed.set(true);
         this.formatReady.countDown();
         if (this.videoBus != null) {
            this.videoBus.close();
         }

         LifecycleClose.closeQuietly(this.bodyRef.getAndSet(null));
         this.pipe.closeWriter();
         this.pipe.close();
         LifecycleClose.interruptAndJoin(this.worker, 2000L);
         AudioDecodePipeline pipeline = this.pipelineRef.get();
         if (pipeline != null) {
            pipeline.close();
         }
      }
   }
}
