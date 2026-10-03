package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

final class VideoCandidateDecodeRunner {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final VideoPipelineProperties.Timing TIMING = VideoPipelineProperties.timing();
   private static final VideoPipelineProperties.Offscreen OFFSCREEN = VideoPipelineProperties.offscreen();
   private static final VideoPipelineProperties.Presentation PRESENTATION = VideoPipelineProperties.presentation();
   private static final long CLOSE_TIMEOUT_MILLIS = TIMING.decoderRestartCloseTimeoutMillis();
   private static final long OFFSCREEN_GRACE_NANOS = OFFSCREEN.graceMillis() * 1000000L;
   private final VideoPlaybackInstance owner;

   VideoCandidateDecodeRunner(VideoPlaybackInstance owner) {
      this.owner = owner;
   }

   void start(long generation, String threadName) {
      CompletableFuture<Void> exit = new CompletableFuture<>();
      this.owner.decodeExit = exit;
      this.owner.physicalCloseHandoff.beginDecode(exit);
      Thread thread = NetMusicThreadFactory.daemonThread(threadName, () -> {
         try {
            this.decode(generation);
         } finally {
            exit.complete(null);
         }
      });
      this.owner.decodeThread = thread;

      try {
         thread.start();
      } catch (Error | RuntimeException var7) {
         this.owner.decodeThread = null;
         exit.completeExceptionally(var7);
         throw var7;
      }
   }

   private void decode(long generation) {
      Exception lastStartupFailure = null;
      List<BiliVideoStreamResolver.VideoCandidate> operationalCandidates = VideoStartupFallbackPolicy.operationalCandidates(
         this.owner.candidates, PRESENTATION.maxSourceWidth(), PRESENTATION.maxSourceHeight()
      );
      if (this.owner.performanceFallbackLocked) {
         operationalCandidates = VideoStartupFallbackPolicy.lockedH264Candidates(operationalCandidates);
      }

      for (BiliVideoStreamResolver.VideoCandidate candidate : operationalCandidates) {
         if (!this.owner.running || generation != this.owner.generation.get()) {
            return;
         }

         if (!this.waitForVisualDemand(generation)) {
            return;
         }

         try {
            if (this.decodeCandidate(generation, candidate)) {
               return;
            }
         } catch (Exception var9) {
            if (generation != this.owner.generation.get() || !this.owner.running || isInterruptedWait(var9)) {
               return;
            }

            if (var9 instanceof VideoBillboardDecoderSupport.CandidateResourceCloseException closeFailure) {
               this.failCandidateClose(
                  generation, new VideoCandidateCloseTimeoutException(candidate, closeFailure.nativeTermination, closeFailure.getMessage(), closeFailure)
               );
               return;
            }

            if (var9 instanceof VideoCandidateCloseTimeoutException closeTimeout) {
               this.failCandidateClose(generation, closeTimeout);
               return;
            }

            if (this.owner.firstFrameLogged) {
               this.handleDecodeFailure(generation, var9);
               return;
            }

            lastStartupFailure = var9;
            if (candidate.codecId() == 13) {
               this.owner.fallbackReason = VideoFallbackReason.classifyAv1StartupFailure(
                  var9, operationalCandidates.stream().anyMatch(next -> next.codecId() == 7)
               );
            }

            LOGGER.warn(
               "视频实例候选首帧失败，尝试下一候选: session={} quality={} codec={} source={}x{} reason={}",
               new Object[]{
                  this.owner.sessionId(), candidate.quality(), candidate.codecId(), candidate.sourceWidth(), candidate.sourceHeight(), var9.toString()
               }
            );
         }
      }

      if (lastStartupFailure != null) {
         this.handleDecodeFailure(generation, lastStartupFailure);
      }

      if (generation == this.owner.generation.get()) {
         this.owner.running = false;
         this.owner.decoder = null;
      }
   }

   private boolean waitForVisualDemand(long generation) {
      while (this.owner.running && generation == this.owner.generation.get() && !this.owner.prewarmVisible) {
         try {
            TimeUnit.MILLISECONDS.sleep(25L);
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
            return false;
         }
      }

      if (this.owner.running && generation == this.owner.generation.get()) {
         this.owner.grantDecodeAdmission();
         return true;
      } else {
         return false;
      }
   }

   private boolean decodeCandidate(long generation, BiliVideoStreamResolver.VideoCandidate candidate) throws Exception {
      int candidateFps = Math.max(1, candidate.fps());
      long frameIntervalNs = Math.max(1L, 1000000000L / candidateFps);
      long frameIndex = 0L;
      long effectiveStartOffsetMillis = this.owner.effectiveDecoderStartOffsetMillis();
      this.owner.decoderStartOffsetMillis = effectiveStartOffsetMillis;
      this.owner.adaptiveRestartOffsetMillis = -1L;
      boolean candidateCommitted = false;
      VideoStartupFallbackPolicy.DecodeSize decodeSize = VideoStartupFallbackPolicy.candidateDecodeSize(
         this.owner.targetWidth, this.owner.targetHeight, candidate.sourceWidth(), candidate.sourceHeight()
      );
      AutoCloseable decoder = VideoBillboardPreview.openDecoder(
         candidate.url(),
         decodeSize.width(),
         decodeSize.height(),
         candidateFps,
         candidate.codecId(),
         this.owner.preferNative,
         this.owner.decoderOverride,
         effectiveStartOffsetMillis,
         this.owner.totalMillis,
         this.owner.consumers.hasGuiConsumer(),
         candidate.decodeMode()
      );
      if (decoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
         this.owner.physicalCloseHandoff.attachDecoder(nativeDecoder.terminationFuture());
      }

      try {
         this.owner.decoder = decoder;

         while (
            this.owner.running
               && generation == this.owner.generation.get()
               && this.waitWhilePaused(generation)
               && this.owner.hasVideoConsumer()
               && this.waitWhileOffscreen(generation, candidateCommitted)
         ) {
            if (frameIndex > 0L) {
               this.waitForDecodeLead(frameIntervalNs, generation);
            }

            if (frameIndex <= 0L || this.owner.visualSyncActive()) {
               long waitStartNs = System.nanoTime();
               boolean boundedAv1Probe = !candidateCommitted && VideoStartupFallbackPolicy.requiresBoundedFirstFrameProbe(candidate);
               VideoBillboardState.DecodedFrame frame = boundedAv1Probe
                  ? VideoBillboardPreview.nextDecodedFrameWithAv1FirstFrameProbe(decoder)
                  : VideoBillboardPreview.nextDecodedFrame(decoder);
               long waitNs = System.nanoTime() - waitStartNs;
               if (frame == null) {
                  if (!this.owner.firstFrameLogged) {
                     throw new IOException("候选在输出首帧前结束");
                  }

                  return true;
               }

               frameIndex++;
               long ptsNanos = frame.ptsNanos() >= 0L ? frame.ptsNanos() : frameIndex * frameIntervalNs;
               if (!this.owner.firstFrameLogged && this.shouldDropStaleStartupFrame(ptsNanos)) {
                  rejectAndClose(decoder, frame, boundedAv1Probe);
               } else {
                  boolean offered;
                  try {
                     offered = this.owner
                        .frameQueue
                        .offer(new DecodedVideoFrame(frameIndex, ptsNanos, frame), () -> this.owner.running && generation == this.owner.generation.get());
                  } catch (InterruptedException var52) {
                     rejectAndClose(decoder, frame, boundedAv1Probe);
                     throw var52;
                  }

                  if (!offered) {
                     rejectAndClose(decoder, frame, boundedAv1Probe);
                     break;
                  }

                  if (!candidateCommitted) {
                     if (boundedAv1Probe) {
                        try {
                           VideoBillboardPreview.commitAv1FirstFrameProbe(decoder, frame);
                        } catch (IOException var51) {
                           this.owner.frameQueue.clear();
                           throw var51;
                        }
                     }

                     candidateCommitted = true;
                     this.owner.targetWidth = decodeSize.width();
                     this.owner.targetHeight = decodeSize.height();
                     this.owner.firstFrameLogged = true;
                     this.owner.firstDecodedNanoTime = System.nanoTime();
                     this.owner.activeCandidate = candidate;
                     this.owner.actualDecoderBackend = actualBackend(decoder);
                     this.owner.performanceMonitor.start(this.owner.firstDecodedNanoTime, candidateFps, this.owner.actualDecoderBackend);
                     this.owner.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                     LOGGER.debug(
                        "视频实例首个解码帧已提交: session={}, pts={}ms, wait={}ms, startOffset={}ms",
                        new Object[]{this.owner.sessionId(), ptsNanos / 1000000L, waitNs / 1000000L, effectiveStartOffsetMillis}
                     );
                  } else {
                     this.owner.performanceMonitor.recordDecodedFrame(preferredDecodeSampleNanos(frame, waitNs));
                  }

                  this.warnIfUploadPumpStalled();
               }
            }
         }

         return candidateCommitted;
      } finally {
         try {
            this.closeCandidateBeforeFallback(decoder, candidateCommitted, generation, candidate);
         } finally {
            if (this.owner.decoder == decoder) {
               this.owner.decoder = null;
            }
         }
      }
   }

   private static void rejectAndClose(AutoCloseable decoder, VideoBillboardState.DecodedFrame frame, boolean boundedAv1Probe) throws IOException {
      try {
         if (boundedAv1Probe) {
            VideoBillboardPreview.rejectAv1FirstFrameProbeFrame(decoder, frame);
         }
      } finally {
         frame.close();
      }
   }

   private static long preferredDecodeSampleNanos(VideoBillboardState.DecodedFrame frame, long waitNanos) {
      long nativeGet = frame != null ? frame.nativeGetNanos() : -1L;
      return nativeGet >= 0L ? nativeGet : Math.max(0L, waitNanos);
   }

   private static String actualBackend(AutoCloseable decoder) {
      if (!(decoder instanceof Fmp4NativeVideoDecoder nativeDecoder)) {
         return decoder != null ? decoder.getClass().getSimpleName() : "unknown";
      } else {
         String actual = nativeDecoder.actualHwaccel();
         return actual != null && !actual.isBlank() ? actual : "unknown";
      }
   }

   private void closeCandidateBeforeFallback(
      AutoCloseable candidateDecoder, boolean candidateCommitted, long generation, BiliVideoStreamResolver.VideoCandidate candidate
   ) throws Exception {
      if (candidateDecoder != null) {
         if (!candidateCommitted && candidateDecoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            long var34 = System.nanoTime();
            CompletableFuture var35 = nativeDecoder.terminationFuture();
            long closeOperation = VideoCloseDiagnostics.global()
               .begin(
                  this.owner.playbackSessionId,
                  EnumSet.of(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED),
                  var34
               );
            // 【反编译伪影修复】var35 被反编译成 raw CompletableFuture，whenComplete 的 lambda 参数随之擦除成
            // (Object, Object)，而 complete(...) 第 3 参要 Throwable。whenComplete 的第二参本就是 Throwable
            // （成功时为 null），所以这里补一个转换即可。
            var35.whenComplete(
               (ignored, error) -> VideoCloseDiagnostics.global()
                  .complete(closeOperation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, (Throwable)error, System.nanoTime())
            );
            nativeDecoder.requestClose();
            CompletableFuture candidateCloseReturned = new CompletableFuture();
            this.owner.physicalCloseHandoff.attachClose(candidateCloseReturned, var35, this.owner.decodeExit);
            Exception closeFailure = null;

            try {
               candidateDecoder.close();
               candidateCloseReturned.complete(null);
            } catch (Exception var31) {
               closeFailure = var31;
               candidateCloseReturned.completeExceptionally(var31);
            } finally {
               VideoCloseDiagnostics.global().complete(closeOperation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, System.nanoTime());
            }

            long timeoutMillis = Math.max(1L, CLOSE_TIMEOUT_MILLIS);
            long closeElapsedNanos = Math.max(0L, System.nanoTime() - var34);
            VideoCandidateClosePolicy.Decision closeDecision = VideoCandidateClosePolicy.decide(
               true, VideoCandidateClosePolicy.completedNormally(var35), closeElapsedNanos, timeoutMillis
            );
            if (var35.isDone() && !VideoCandidateClosePolicy.completedNormally(var35)) {
               throw new VideoCandidateCloseTimeoutException(candidate, var35, "native termination completed exceptionally");
            } else if (closeDecision == VideoCandidateClosePolicy.Decision.OPEN_NEXT) {
               if (closeFailure != null) {
                  throw new VideoCandidateCloseTimeoutException(candidate, var35, "decoder close returned exceptionally", closeFailure);
               }
            } else if (closeDecision == VideoCandidateClosePolicy.Decision.FAIL_CLOSED) {
               throw new VideoCandidateCloseTimeoutException(candidate, var35);
            } else {
               long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - closeElapsedNanos;

               try {
                  var35.get(remainingNanos, TimeUnit.NANOSECONDS);
               } catch (TimeoutException var28) {
                  throw new VideoCandidateCloseTimeoutException(candidate, var35);
               } catch (InterruptedException var29) {
                  Thread.currentThread().interrupt();
                  throw new IOException("等待旧视频候选关闭时被中断", var29);
               } catch (ExecutionException var30) {
                  throw new VideoCandidateCloseTimeoutException(candidate, var35, "native termination completed exceptionally", var30.getCause());
               }

               if (closeFailure != null) {
                  throw new VideoCandidateCloseTimeoutException(candidate, var35, "decoder close returned exceptionally", closeFailure);
               }
            }
         } else {
            CompletableFuture<Void> closeReturned = new CompletableFuture<>();
            CompletableFuture<Void> nativeTermination = candidateDecoder instanceof Fmp4NativeVideoDecoder nativeCandidate
               ? nativeCandidate.terminationFuture()
               : CompletableFuture.completedFuture(null);
            this.owner.physicalCloseHandoff.attachClose(closeReturned, nativeTermination, this.owner.decodeExit);

            try {
               candidateDecoder.close();
               closeReturned.complete(null);
            } catch (Error | Exception var33) {
               closeReturned.completeExceptionally(var33);
               throw var33;
            }
         }
      }
   }

   private void failCandidateClose(long generation, VideoCandidateCloseTimeoutException error) {
      synchronized (this.owner) {
         VideoZombieCloseSupervisor.global()
            .track(this.owner.sessionId(), generation, CompletableFuture.completedFuture(null), error.nativeTermination, this.owner.decodeExit);
         if (generation == this.owner.generation.get() && this.owner.running && !this.owner.stopRequested) {
            long failedGeneration = this.owner.generation.incrementAndGet();
            this.owner.restartInProgress = false;
            this.owner.restartState = VideoDecoderRestartState.FAILED_CLOSE;
            this.owner.networkFailure = false;
            this.owner.terminalFailure = true;
            this.owner.frameQueue.clear();
            LOGGER.error(
               "旧视频候选未正常收敛，禁止打开下一视频候选: session={} generation={} quality={} codec={} timeout={}ms",
               new Object[]{this.owner.sessionId(), failedGeneration, error.candidate.quality(), error.candidate.codecId(), CLOSE_TIMEOUT_MILLIS}
            );
         }
      }
   }

   private void handleDecodeFailure(long generation, Throwable error) {
      if (error instanceof OutOfMemoryError) {
         ClientMediaLifecycleHandler.tripMemoryProtection("video decoder allocation failed: " + error.getMessage());
         LOGGER.error("视频会话内存分配失败并触发熔断: session={}", this.owner.sessionId(), error);
      } else if (generation == this.owner.generation.get() && (this.owner.running || !isInterruptedWait(error))) {
         this.owner.networkFailure = MediaNetworkFailureClassifier.isNetworkFailure(error);
         this.owner.terminalFailure = true;
         if (this.owner.networkFailure) {
            this.owner.notifyNetworkFailure();
         }

         LOGGER.error("视频会话解码失败: session={}", this.owner.sessionId(), error);
      }
   }

   private boolean shouldDropStaleStartupFrame(long ptsNanos) {
      long maxStartupLagNs = VideoPipelineProperties.startupDropLagMillis() * 1000000L;
      return maxStartupLagNs > 0L && this.owner.playbackNanos() - ptsNanos > maxStartupLagNs && this.owner.frameQueue.isEmpty();
   }

   private void waitForDecodeLead(long frameIntervalNs, long generation) throws InterruptedException {
      long maxLeadNs = Math.max(frameIntervalNs * this.owner.frameQueue.capacity(), VideoPipelineProperties.maxDecodeLeadMillis() * 1000000L);

      while (
         this.owner.running
            && generation == this.owner.generation.get()
            && this.owner.visualSyncActive()
            && this.owner.frameQueue.isFull()
            && this.owner.frameQueue.latestPtsNanos() - this.owner.playbackNanos() > maxLeadNs
      ) {
         this.warnIfUploadPumpStalled();
         TimeUnit.MILLISECONDS.sleep(5L);
      }
   }

   private void warnIfUploadPumpStalled() {
      long thresholdNs = VideoPipelineProperties.uploadPumpWarnMillis() * 1000000L;
      if (this.owner.visualSyncActive()) {
         long idleNs = System.nanoTime() - this.owner.lastUploadPumpNanoTime;
         if (thresholdNs > 0L && this.owner.frameQueue.isFull() && idleNs > thresholdNs) {
            this.owner.lastUploadPumpNanoTime = System.nanoTime();
            LOGGER.warn(
               "视频流水线上传泵疑似停滞: session={}, queue={}, latestPts={}ms, clock={}ms, idle={}ms",
               new Object[]{
                  this.owner.sessionId(),
                  this.owner.frameQueue.size(),
                  this.owner.frameQueue.latestPtsNanos() / 1000000L,
                  this.owner.playbackNanos() / 1000000L,
                  idleNs / 1000000L
               }
            );
         }
      }
   }

   private boolean waitWhilePaused(long generation) {
      if (!isGamePaused()) {
         return this.owner.running && generation == this.owner.generation.get();
      } else {
         long pauseStartNs = System.nanoTime();
         this.owner.performanceMonitor.pause(pauseStartNs);

         while (this.owner.running && generation == this.owner.generation.get() && isGamePaused()) {
            try {
               TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException var6) {
               Thread.currentThread().interrupt();
               return false;
            }
         }

         this.owner.startNanoTime = this.owner.startNanoTime + Math.max(0L, System.nanoTime() - pauseStartNs);
         this.owner.performanceMonitor.resume(System.nanoTime());
         return this.owner.running && generation == this.owner.generation.get();
      }
   }

   private boolean waitWhileOffscreen(long generation, boolean candidateCommitted) {
      if (VideoRestartSuppressionPolicy.shouldPauseDecodeOffscreen(candidateCommitted, this.owner.liveSource, OFFSCREEN.pauseDecode())
         && this.isOffscreenPauseActive()) {
         long pauseStartNs = System.nanoTime();
         this.owner.performanceMonitor.pause(pauseStartNs);
         if (!this.owner.loggedOffscreenPause) {
            this.owner.loggedOffscreenPause = true;
            LOGGER.debug(
               "视频会话离屏暂停取帧: session={}, queue={}, media={}ms, master={}ms",
               new Object[]{this.owner.sessionId(), this.owner.frameQueue.size(), this.owner.mediaMillis(), this.owner.anchor.timeline().mediaMillis()}
            );
         }

         while (this.owner.running && generation == this.owner.generation.get() && this.isOffscreenPauseActive()) {
            try {
               TimeUnit.MILLISECONDS.sleep(25L);
            } catch (InterruptedException var8) {
               Thread.currentThread().interrupt();
               return false;
            }
         }

         long pausedNs = System.nanoTime() - pauseStartNs;
         this.owner.performanceMonitor.resume(System.nanoTime());
         if (pausedNs > 0L) {
            LOGGER.debug(
               "视频会话离屏恢复取帧: session={}, paused={}ms, media={}ms, master={}ms",
               new Object[]{this.owner.sessionId(), pausedNs / 1000000L, this.owner.mediaMillis(), this.owner.anchor.timeline().mediaMillis()}
            );
         }

         return this.owner.running && generation == this.owner.generation.get();
      } else {
         return this.owner.running && generation == this.owner.generation.get();
      }
   }

   boolean isOffscreenPauseActive() {
      if (this.owner.prewarmVisible) {
         return false;
      } else {
         long lastVisible = this.owner.lastVisibleNanoTime;
         return lastVisible > 0L && System.nanoTime() - lastVisible > Math.max(0L, OFFSCREEN_GRACE_NANOS);
      }
   }

   private static boolean isGamePaused() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft != null && minecraft.isPaused();
   }

   private static boolean isInterruptedWait(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof InterruptedException) {
            return true;
         }

         if (current instanceof IOException && current.getMessage() != null && current.getMessage().contains("等待 native 视频帧时被中断")) {
            return true;
         }
      }

      return false;
   }
}
