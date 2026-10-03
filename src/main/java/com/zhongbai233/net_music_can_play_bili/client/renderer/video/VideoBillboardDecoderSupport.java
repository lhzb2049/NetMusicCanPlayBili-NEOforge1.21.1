package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.VideoFeatureFlags;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

abstract class VideoBillboardDecoderSupport extends VideoBillboardUploadSupport {
   protected static void decodeLoop(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      long startOffsetMillis,
      long totalMillis,
      long generation,
      boolean catchUpDropsEnabled,
      boolean forceRgbaOutput
   ) {
      if (CPU_BARS) {
         decodeCpuBarsLoop(targetWidth, targetHeight, fps, generation);
      } else {
         long frameIntervalNs = fps > 0 ? Math.max(1L, 1000000000L / fps) : 50000000L;
         long frameIndex = 0L;
         int consecutiveBadFrames = 0;
         AutoCloseable decoder = null;

         try {
            decoder = openDecoder(
               videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, startOffsetMillis, totalMillis, forceRgbaOutput
            );
            if (LEGACY_WORKER.bindDecoder(generation, decoder)) {
               warnNativeOffsetLimitation(decoder, startOffsetMillis);

               while (LEGACY_WORKER.isActive(generation)) {
                  if (isGamePaused()) {
                     waitWhilePaused(generation);
                  } else {
                     if (LEGACY_PREVIEW.requiresProjector() && !isActiveProjectorValid()) {
                        return;
                     }

                     VideoBillboardState.DecodedFrame frame = nextDecodedFrame(decoder);
                     if (frame == null) {
                        return;
                     }

                     frameIndex++;
                     int dropped = 0;
                     long nowNs = System.nanoTime();

                     while (true) {
                        if (catchUpDropsEnabled
                           && LEGACY_WORKER.isActive(generation)
                           && nowNs - expectedFrameTimeNs(frameIndex, frameIntervalNs) > frameIntervalNs * 2L
                           && dropped < MAX_CATCH_UP_DROPS_PER_TICK) {
                           VideoBillboardState.DecodedFrame catchUpDecoded = nextDecodedFrame(decoder);
                           if (catchUpDecoded != null) {
                              frame.close();
                              frame = catchUpDecoded;
                              frameIndex++;
                              dropped++;
                              nowNs = System.nanoTime();
                              continue;
                           }

                           frame.close();
                           frame = null;
                        }

                        if (frame == null) {
                           return;
                        }

                        if (dropped > 0) {
                           LOGGER.debug(
                              "视频播放落后音频时间线，丢弃 {} 帧追赶 (frameIndex={}, lag={}ms)",
                              new Object[]{
                                 dropped, frameIndex, Math.max(0L, (System.nanoTime() - expectedFrameTimeNs(frameIndex, frameIntervalNs)) / 1000000L)
                              }
                           );
                        }

                        long waitNs = expectedFrameTimeNs(frameIndex, frameIntervalNs) - System.nanoTime();
                        if (waitNs > 0L) {
                           try {
                              TimeUnit.NANOSECONDS.sleep(waitNs);
                           } catch (InterruptedException var50) {
                              Thread.currentThread().interrupt();
                              return;
                           }
                        }

                        long uploadNs;
                        try {
                           uploadNs = uploadFrameSync(frame, targetWidth, targetHeight, generation);
                           if (uploadNs < 0L) {
                              LOGGER.warn("视频 billboard 预览上传失败或客户端世界已退出");
                              return;
                           }
                        } finally {
                           frame.close();
                        }

                        if (uploadNs > ADAPTIVE_FRAME_BUDGET_NS) {
                           if (++consecutiveBadFrames >= ADAPTIVE_BAD_FRAME_THRESHOLD && requestAdaptiveDownscale(targetWidth, targetHeight, generation)) {
                              return;
                           }
                        } else {
                           consecutiveBadFrames = Math.max(0, consecutiveBadFrames - 2);
                        }
                        break;
                     }
                  }
               }

               return;
            }
         } catch (IOException var51) {
            if (LEGACY_WORKER.isCurrent(generation)) {
               activeNetworkFailure = MediaNetworkFailureClassifier.isNetworkFailure(var51);
            }

            LOGGER.error("视频 billboard 预览解码失败", var51);
            return;
         } catch (Exception var52) {
            if (LEGACY_WORKER.isCurrent(generation)) {
               activeNetworkFailure = MediaNetworkFailureClassifier.isNetworkFailure(var52);
            }

            LOGGER.error("视频 billboard 预览 native 解码失败", var52);
            return;
         } finally {
            if (decoder != null) {
               try {
                  decoder.close();
               } catch (Exception var48) {
                  LOGGER.warn("视频 billboard 解码器关闭失败", var48);
               }
            }

            LEGACY_WORKER.finish(generation, decoder);
         }
      }
   }

   protected static long expectedFrameTimeNs(long frameIndex, long frameIntervalNs) {
      long startNs = activeStartNanoTime;
      return startNs > 0L ? startNs + frameIndex * frameIntervalNs : System.nanoTime();
   }

   protected static boolean isGamePaused() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft != null && minecraft.isPaused();
   }

   protected static void waitWhilePaused(long generation) {
      long pauseStartNs = System.nanoTime();

      while (LEGACY_WORKER.isActive(generation) && isGamePaused()) {
         try {
            Thread.sleep(25L);
         } catch (InterruptedException var5) {
            Thread.currentThread().interrupt();
            return;
         }
      }

      activeStartNanoTime = activeStartNanoTime + Math.max(0L, System.nanoTime() - pauseStartNs);
   }

   protected static void closeActiveDecoderAsync(AutoCloseable decoder) {
      if (decoder != null) {
         MediaCloseExecutor.closeAsync(decoder, "billboard video decoder");
      }
   }

   static AutoCloseable openDecoder(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      long startOffsetMillis,
      long totalMillis
   ) throws IOException {
      return openDecoder(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, startOffsetMillis, totalMillis, false);
   }

   static AutoCloseable openDecoder(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      long startOffsetMillis,
      long totalMillis,
      boolean forceRgbaOutput
   ) throws IOException {
      return openDecoder(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         preferNative,
         decoderOverride,
         startOffsetMillis,
         totalMillis,
         forceRgbaOutput,
         BiliVideoStreamResolver.DecodeMode.AUTO
      );
   }

   static AutoCloseable openDecoder(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      long startOffsetMillis,
      long totalMillis,
      boolean forceRgbaOutput,
      BiliVideoStreamResolver.DecodeMode decodeMode
   ) throws IOException {
      if (LiveVideoSampleBus.isBusUrl(videoUrl)) {
         String busKey = LiveVideoSampleBus.keyFromBusUrl(videoUrl);
         IOException last = null;
         String[] requested = decodeMode == BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY
            ? new String[]{"none"}
            : VideoFeatureFlags.requestedHwaccelCandidates();

         for (String hwaccel : requested) {
            if (decodeMode != BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED || !"none".equalsIgnoreCase(hwaccel)) {
               try {
                  Fmp4NativeVideoDecoder opened = Fmp4NativeVideoDecoder.forLiveBus(
                     busKey, targetWidth, targetHeight, forceRgbaOutput ? Fmp4NativeVideoDecoder.OutputFormat.RGBA : yuvDecodeFormat(), hwaccel, fps
                  );
                  return requireHardwareIfRequested(opened, decodeMode, hwaccel);
               } catch (IOException var21) {
                  last = var21;
                  LOGGER.warn("直播视频解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccel, var21.toString());
               }
            }
         }

         throw last != null ? last : new IOException("直播视频解码器不可用");
      } else if (!preferNative) {
         throw new IOException("视频投影仪不允许使用系统 ffmpeg；请启用/修复内置 native 解码器");
      } else {
         IOException last = null;
         String[] requested = decodeMode == BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY
            ? new String[]{"none"}
            : VideoFeatureFlags.requestedHwaccelCandidates();

         for (String hwaccelx : requested) {
            if (decodeMode != BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED || !"none".equalsIgnoreCase(hwaccelx)) {
               try {
                  Fmp4NativeVideoDecoder opened = new Fmp4NativeVideoDecoder(
                     videoUrl,
                     codecId,
                     targetWidth,
                     targetHeight,
                     Integer.MAX_VALUE,
                     true,
                     forceRgbaOutput ? Fmp4NativeVideoDecoder.OutputFormat.RGBA : yuvDecodeFormat(),
                     hwaccelx,
                     startOffsetMillis,
                     totalMillis,
                     fps
                  );
                  return requireHardwareIfRequested(opened, decodeMode, hwaccelx);
               } catch (IOException var22) {
                  last = var22;
                  LOGGER.warn("Native 视频解码器启动失败 hwaccel={}，尝试下一个候选: {}", hwaccelx, var22.toString());
               }
            }
         }

         throw last != null ? last : new IOException("Native video decoder unavailable");
      }
   }

   protected static Fmp4NativeVideoDecoder requireHardwareIfRequested(
      Fmp4NativeVideoDecoder decoder, BiliVideoStreamResolver.DecodeMode decodeMode, String requestedHwaccel
   ) throws IOException {
      if (decodeMode != BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED) {
         return decoder;
      } else {
         String actualHwaccel;
         try {
            if (decoder.isHardwareAccelerated()) {
               return decoder;
            }

            actualHwaccel = decoder.actualHwaccel();
         } catch (RuntimeException var5) {
            closeRejectedHardwareDecoder(decoder, "hardware backend validation failed", var5);
            throw var5;
         }

         closeRejectedHardwareDecoder(decoder, "rejected hardware backend close failed", null);
         throw new IOException("候选要求硬件解码但 native backend 未启用硬解: requested=" + requestedHwaccel + ", actual=" + actualHwaccel);
      }
   }

   protected static void closeRejectedHardwareDecoder(Fmp4NativeVideoDecoder decoder, String reason, Throwable validationFailure) {
      CompletableFuture<Void> nativeTermination = decoder.terminationFuture();

      try {
         decoder.close();
      } catch (RuntimeException var8) {
         throw new VideoBillboardDecoderSupport.CandidateResourceCloseException(nativeTermination, reason, var8);
      }

      try {
         nativeTermination.get(3000L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException var5) {
         Thread.currentThread().interrupt();
         throw new VideoBillboardDecoderSupport.CandidateResourceCloseException(nativeTermination, "rejected hardware backend close interrupted", var5);
      } catch (ExecutionException var6) {
         throw new VideoBillboardDecoderSupport.CandidateResourceCloseException(nativeTermination, "rejected hardware backend close failed", var6.getCause());
      } catch (TimeoutException var7) {
         throw new VideoBillboardDecoderSupport.CandidateResourceCloseException(nativeTermination, "rejected hardware backend close timed out", var7);
      }

      if (validationFailure == null) {
         ;
      }
   }

   protected static boolean requestAdaptiveDownscale(int currentWidth, int currentHeight, long generation) {
      VideoBillboardState.PlaybackRequest req = LEGACY_PREVIEW.request();
      if (req != null && LEGACY_WORKER.isCurrent(generation) && currentWidth > MIN_ADAPTIVE_WIDTH) {
         int nextWidth = Math.max(MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
         int nextHeight = Math.max(1, Math.round(currentHeight * ((float)nextWidth / currentWidth)));
         long elapsed = req.startOffsetMillis() + Math.max(0L, (System.nanoTime() - req.startedNanoTime()) / 1000000L);
         LOGGER.warn("视频上传持续超预算，优先保证游戏流畅：{}x{} -> {}x{}，允许丢帧并重启较低分辨率", new Object[]{currentWidth, currentHeight, nextWidth, nextHeight});
         Minecraft.getInstance()
            .execute(
               () -> {
                  VideoBillboardPreview.stopForReplace();
                  VideoBillboardPreview.startInternal(
                     req.videoUrl(),
                     nextWidth,
                     nextHeight,
                     req.fps(),
                     req.codecId(),
                     req.preferNative(),
                     req.decoderOverride(),
                     req.sessionId(),
                     elapsed,
                     req.totalMillis(),
                     req.anchorPositions(),
                     true,
                     req.forceRgbaOutput()
                  );
               }
            );
         return true;
      } else {
         return false;
      }
   }

   protected static void warnNativeOffsetLimitation(AutoCloseable decoder, long startOffsetMillis) {
      if (decoder instanceof Fmp4NativeVideoDecoder && startOffsetMillis > 0L) {
         LOGGER.debug("Native 视频投影使用内置 fMP4 Range seek 起播: offset={}ms", startOffsetMillis);
      }
   }

   static byte[] nextFrame(AutoCloseable decoder) throws Exception {
      byte[] var2;
      try (VideoBillboardState.DecodedFrame frame = nextDecodedFrame(decoder)) {
         if (frame == null) {
            return null;
         }

         if (frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            throw new IllegalStateException("decoded frame is " + frame.format() + ", not RGBA");
         }

         var2 = frame.data();
      }

      return var2;
   }

   static VideoBillboardState.DecodedFrame nextDecodedFrame(AutoCloseable decoder) throws Exception {
      if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
         return VideoBillboardState.DecodedFrame.wrap(nativeDecoder.getNextDecodedFrame());
      } else {
         throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
      }
   }

   static VideoBillboardState.DecodedFrame nextDecodedFrameWithAv1FirstFrameProbe(AutoCloseable decoder) throws Exception {
      if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
         return VideoBillboardState.DecodedFrame.wrap(nativeDecoder.getNextDecodedFrameWithAv1FirstFrameProbe());
      } else {
         throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
      }
   }

   static void commitAv1FirstFrameProbe(AutoCloseable decoder, VideoBillboardState.DecodedFrame frame) throws IOException {
      if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder && frame != null && frame.delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame
         )
       {
         nativeDecoder.commitAv1FirstFrameProbe(nativeFrame);
      } else {
         throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
      }
   }

   static void rejectAv1FirstFrameProbeFrame(AutoCloseable decoder, VideoBillboardState.DecodedFrame frame) throws IOException {
      if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder && frame != null && frame.delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame nativeFrame
         )
       {
         nativeDecoder.rejectAv1FirstFrameProbeFrame(nativeFrame);
      } else {
         throw new IOException("unsupported video decoder: " + decoder.getClass().getName());
      }
   }

   static List<BlockPos> immutablePositions(Collection<BlockPos> positions) {
      return positions != null && !positions.isEmpty() ? positions.stream().filter(pos -> pos != null).map(pos -> pos.immutable()).toList() : List.of();
   }

   static final class CandidateResourceCloseException extends RuntimeException {
      final CompletableFuture<Void> nativeTermination;

      private CandidateResourceCloseException(CompletableFuture<Void> nativeTermination, String message, Throwable cause) {
         super(message, cause);
         this.nativeTermination = nativeTermination;
      }
   }
}
