package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.ClientMediaLifecycleHandler;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ClientDisplayProperties;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ClickEvent.Action;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

final class VideoPlaybackInstance {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final VideoPipelineProperties.Timing TIMING = VideoPipelineProperties.timing();
   private static final VideoPipelineProperties.Presentation PRESENTATION = VideoPipelineProperties.presentation();
   private static final long AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS = TIMING.audioLatencyCompensationMillis();
   private static final long CHASE_WINDOW_MILLIS = TIMING.chaseWindowMillis();
   private static final long SLOWDOWN_WINDOW_MILLIS = TIMING.slowdownWindowMillis();
   private static final long RUNTIME_LAG_RESTART_MILLIS = TIMING.runtimeLagRestartMillis();
   private static final long RUNTIME_LAG_CONFIRM_MILLIS = TIMING.runtimeLagConfirmMillis();
   private static final long RUNTIME_LAG_RESTART_COOLDOWN_MILLIS = TIMING.runtimeLagRestartCooldownMillis();
   private static final long DECODER_RESTART_CLOSE_TIMEOUT_MILLIS = TIMING.decoderRestartCloseTimeoutMillis();
   private static final long FIRST_FRAME_TIMEOUT_MILLIS = TIMING.firstFrameTimeoutMillis();
   private static final int MAX_FIRST_FRAME_RECOVERY_ATTEMPTS = TIMING.firstFrameRecoveryAttempts();
   volatile int targetWidth;
   volatile int targetHeight;
   private final int fps;
   final List<BiliVideoStreamResolver.VideoCandidate> candidates;
   final boolean liveSource;
   final PlaybackSessionId playbackSessionId;
   private final long startOffsetMillis;
   final long totalMillis;
   final boolean preferNative;
   final String decoderOverride;
   final VideoPlaybackTextures textures;
   final VideoPlaybackAnchor anchor;
   final VideoPlaybackFrameQueue frameQueue = new VideoPlaybackFrameQueue(PRESENTATION.queueCapacity());
   final AtomicLong generation = new AtomicLong();
   final VideoConsumerRegistry<BlockPos> consumers = new VideoConsumerRegistry<>();
   final VideoPhysicalCloseHandoff physicalCloseHandoff = new VideoPhysicalCloseHandoff();
   final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
   private final VideoCandidateDecodeRunner candidateDecodeRunner = new VideoCandidateDecodeRunner(this);
   private final VideoPlaybackCloser closer = new VideoPlaybackCloser(this);
   private final VideoPlaybackPresentation presentation = new VideoPlaybackPresentation(this);
   volatile boolean running;
   volatile boolean hasFrame;
   volatile long startNanoTime;
   volatile long decoderGenerationStartedNanoTime;
   volatile Thread decodeThread;
   volatile AutoCloseable decoder;
   volatile CompletableFuture<Void> decodeExit = CompletableFuture.completedFuture(null);
   volatile boolean firstFrameLogged;
   volatile boolean firstYuvImmediateLogged;
   volatile long firstDecodedNanoTime;
   private volatile boolean startupBufferReady;
   volatile long lastUploadPumpNanoTime;
   volatile long decoderStartOffsetMillis;
   private volatile long lastUploadedPtsNanos = -1L;
   private volatile long lastUploadedBaseOffsetMillis = -1L;
   volatile long adaptiveRestartOffsetMillis = -1L;
   volatile long lastVisibleNanoTime;
   volatile long offscreenSinceNanoTime;
   private volatile long runtimeLagSinceNanoTime;
   private volatile boolean visualSyncSuspended;
   private volatile long lastRuntimeLagRestartNanoTime;
   volatile boolean restartInProgress;
   volatile VideoDecoderRestartState restartState = VideoDecoderRestartState.ACTIVE;
   volatile boolean prewarmVisible = true;
   volatile boolean decodeAdmissionGranted;
   volatile boolean initialDecodeWorkerStarted;
   volatile boolean loggedOffscreenPause;
   volatile boolean networkFailure;
   volatile boolean terminalFailure;
   private volatile boolean networkFailureNotified;
   volatile boolean stopRequested;
   volatile BiliVideoStreamResolver.VideoCandidate activeCandidate;
   volatile String actualDecoderBackend = "unknown";
   volatile String fallbackReason = "";
   volatile boolean performanceFallbackLocked;
   private volatile boolean performanceNoH264Logged;
   private volatile int firstFrameRecoveryAttempts;
   private int consecutiveBadUploads;

   VideoPlaybackInstance(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> projectorPositions,
      BlockPos turntablePos,
      boolean preferNative,
      String decoderOverride
   ) {
      this(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         sessionId,
         startOffsetMillis,
         totalMillis,
         projectorPositions,
         VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis)),
         preferNative,
         decoderOverride,
         List.of(new BiliVideoStreamResolver.VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0))
      );
   }

   VideoPlaybackInstance(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> projectorPositions,
      VideoPlaybackAnchor anchor,
      boolean preferNative,
      String decoderOverride
   ) {
      this(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         sessionId,
         startOffsetMillis,
         totalMillis,
         projectorPositions,
         anchor,
         preferNative,
         decoderOverride,
         List.of(new BiliVideoStreamResolver.VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0))
      );
   }

   VideoPlaybackInstance(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> projectorPositions,
      VideoPlaybackAnchor anchor,
      boolean preferNative,
      String decoderOverride,
      List<BiliVideoStreamResolver.VideoCandidate> candidates
   ) {
      this.targetWidth = Math.max(1, targetWidth);
      this.targetHeight = Math.max(1, targetHeight);
      this.fps = Math.max(1, fps);
      this.candidates = candidates != null && !candidates.isEmpty()
         ? List.copyOf(candidates)
         : List.of(new BiliVideoStreamResolver.VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0));
      this.liveSource = LiveVideoSampleBus.isBusUrl(this.candidates.get(0).url());
      this.playbackSessionId = PlaybackSessionId.of(sessionId);
      this.startOffsetMillis = Math.max(0L, startOffsetMillis);
      this.totalMillis = Math.max(0L, totalMillis);
      this.preferNative = preferNative;
      this.decoderOverride = decoderOverride;
      this.anchor = anchor != null ? anchor : VideoPlaybackAnchor.turntable(null, this.sessionId(), this.totalMillis);
      this.textures = new VideoPlaybackTextures(this.sessionId());
      this.replaceProjectors(projectorPositions);
      LOGGER.debug(
         "视频会话创建: session={}, {}x{} @ {}fps, renderBackend={}, decodeFormat={}",
         new Object[]{
            this.sessionId(),
            this.targetWidth,
            this.targetHeight,
            this.fps,
            VideoBillboardPreview.RENDER_BACKEND,
            VideoBillboardPreview.YUV_DECODE_BACKEND
               ? (
                  VideoBillboardPreview.isCustomYuvShaderAvailable()
                     ? VideoBillboardPreview.yuvDecodeFormat().name() + "→RGB(shader)"
                     : VideoBillboardPreview.yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)"
               )
               : "RGBA"
         }
      );
   }

   synchronized void start() {
      if (!this.stopRequested) {
         this.running = true;
         this.hasFrame = false;
         this.firstFrameLogged = false;
         this.firstYuvImmediateLogged = false;
         this.firstDecodedNanoTime = 0L;
         this.startupBufferReady = false;
         this.lastUploadPumpNanoTime = System.nanoTime();
         this.decoderStartOffsetMillis = this.startOffsetMillis;
         this.lastUploadedPtsNanos = -1L;
         this.lastUploadedBaseOffsetMillis = -1L;
         this.adaptiveRestartOffsetMillis = -1L;
         boolean immediatelyVisible = this.consumers.hasGuiConsumer() || this.hasHolographicTurntableConsumer();
         this.lastVisibleNanoTime = immediatelyVisible ? System.nanoTime() : 0L;
         this.offscreenSinceNanoTime = 0L;
         this.prewarmVisible = immediatelyVisible;
         this.decodeAdmissionGranted = immediatelyVisible;
         this.initialDecodeWorkerStarted = false;
         this.loggedOffscreenPause = false;
         this.visualSyncSuspended = !immediatelyVisible;
         this.networkFailure = false;
         this.terminalFailure = false;
         this.networkFailureNotified = false;
         this.firstFrameRecoveryAttempts = 0;
         this.activeCandidate = null;
         this.actualDecoderBackend = "unknown";
         this.fallbackReason = this.candidates.stream().noneMatch(candidate -> candidate.codecId() == 13)
               && this.candidates.stream().anyMatch(candidate -> candidate.codecId() == 7)
            ? "no-av1-stream"
            : "";
         this.performanceFallbackLocked = false;
         this.performanceNoH264Logged = false;
         this.consecutiveBadUploads = 0;
         this.frameQueue.clear();
         this.startNanoTime = immediatelyVisible ? System.nanoTime() : 0L;
         this.decoderGenerationStartedNanoTime = this.startNanoTime;
         this.generation.incrementAndGet();
         if (immediatelyVisible) {
            this.grantDecodeAdmission();
         }
      }
   }

   synchronized void grantDecodeAdmission() {
      if (this.running && !this.stopRequested) {
         if (!this.decodeAdmissionGranted) {
            long now = System.nanoTime();
            this.decodeAdmissionGranted = true;
            this.startNanoTime = now;
            this.decoderGenerationStartedNanoTime = now;
         }

         if (!this.initialDecodeWorkerStarted) {
            this.initialDecodeWorkerStarted = true;
            long gen = this.generation.get();
            this.candidateDecodeRunner.start(gen, "bili-video-" + this.sessionId());
         }
      }
   }

   void resetFirstFrameWatchdogAfterVisibilityResume(long nowNanoTime) {
      if (this.decodeAdmissionGranted && !this.hasFrame) {
         this.startNanoTime = nowNanoTime;
         this.decoderGenerationStartedNanoTime = nowNanoTime;
      }
   }

   long playbackNanos() {
      long synced = this.syncedPlaybackNanos();
      if (synced >= 0L) {
         return synced;
      } else {
         long startNs = this.startNanoTime;
         return startNs > 0L ? Math.max(0L, System.nanoTime() - startNs) : 0L;
      }
   }

   private long syncedPlaybackNanos() {
      long restartOffset = this.adaptiveRestartOffsetMillis;
      long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : this.startOffsetMillis;
      long baseOffsetMillis = Math.max(requestedOffsetMillis, this.decoderStartOffsetMillis);
      return this.anchor.timeline().relativeNanos(baseOffsetMillis + AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS);
   }

   long effectiveDecoderStartOffsetMillis() {
      if (this.liveSource) {
         return 0L;
      } else {
         long restartOffset = this.adaptiveRestartOffsetMillis;
         long requestedOffsetMillis = restartOffset >= 0L ? restartOffset : this.startOffsetMillis;
         long synced = this.anchor.timeline().mediaMillis();
         long offset = synced >= 0L ? Math.max(requestedOffsetMillis, synced) : requestedOffsetMillis;
         return this.totalMillis > 0L ? Math.min(this.totalMillis, offset) : offset;
      }
   }

   void pumpUploadOnRenderThread() {
      this.lastUploadPumpNanoTime = System.nanoTime();
      if (!this.visualSyncActive()) {
         this.suspendVisualSync();
      } else {
         this.visualSyncSuspended = false;
         if (this.running || !this.frameQueue.isEmpty()) {
            this.maybeRestartForRuntimeLag();
            if (this.startupBufferReady || !this.shouldWaitForStartupBuffer()) {
               this.startupBufferReady = true;
               long playbackNs = this.playbackNanos();
               DecodedVideoFrame frame = this.frameQueue.pollBestFrame(playbackNs, VideoPipelineProperties.earlyToleranceMillis() * 1000000L);
               this.performanceMonitor.recordDroppedFrames(this.frameQueue.drainDroppedFrames());
               if (frame == null) {
                  if (this.firstFrameLogged && this.frameQueue.isEmpty()) {
                     this.performanceMonitor.recordStarvation();
                  }

                  this.observeSustainedPerformance();
               } else {
                  long maxVisibleLagNs = VideoPipelineProperties.maxVisibleLagMillis() * 1000000L;
                  if (maxVisibleLagNs > 0L && playbackNs - frame.ptsNanos() > maxVisibleLagNs) {
                     DecodedVideoFrame chase = this.frameQueue.pollBestFrame(playbackNs, 0L);
                     if (chase != null) {
                        frame.close();
                        this.performanceMonitor.recordDroppedFrames(1L);
                        frame = chase;
                     }
                  }

                  boolean uploaded = false;
                  long uploadNs = 0L;

                  try {
                     long uploadStartNs = System.nanoTime();
                     uploaded = this.uploadDecodedFrameOnRenderThread(frame.frame());
                     uploadNs = System.nanoTime() - uploadStartNs;
                     if (uploaded) {
                        this.lastUploadedBaseOffsetMillis = Math.max(this.startOffsetMillis, this.decoderStartOffsetMillis);
                        this.lastUploadedPtsNanos = frame.ptsNanos();
                     }
                  } catch (OutOfMemoryError var14) {
                     ClientMediaLifecycleHandler.tripMemoryProtection("video texture allocation failed: " + var14.getMessage());
                     LOGGER.error("视频纹理内存分配失败并触发熔断: session={}", this.sessionId(), var14);
                  } finally {
                     frame.close();
                  }

                  if (uploaded) {
                     this.recordAdaptiveUploadCost(uploadNs);
                  }

                  this.observeSustainedPerformance();
               }
            }
         }
      }
   }

   private void observeSustainedPerformance() {
      BiliVideoStreamResolver.VideoCandidate candidate = this.activeCandidate;
      if (candidate != null && this.performanceMonitor.started()) {
         long masterMillis = this.anchor.timeline().mediaMillis();
         long displayedMillis = this.mediaMillis();
         long queuedMillis = this.queuedMediaMillis();
         long bestVideoMillis = Math.max(displayedMillis, queuedMillis);
         if (masterMillis >= 0L && bestVideoMillis >= 0L) {
            this.performanceMonitor.recordSyncDriftMillis(masterMillis - bestVideoMillis);
         }

         long now = System.nanoTime();
         this.performanceMonitor.sampleNativeResources(now);
         VideoPerformanceFallbackPolicy.Snapshot snapshot = this.performanceMonitor.snapshot(now);
         boolean h264Available = this.candidates.stream().anyMatch(next -> next.codecId() == 7);
         VideoPerformanceFallbackPolicy.Decision decision = VideoPerformanceFallbackPolicy.decide(
            snapshot, candidate.codecId() == 13 && !this.liveSource, h264Available, this.performanceFallbackLocked
         );
         if (decision.shouldFallback()) {
            this.requestSustainedPerformanceFallback(decision, snapshot);
         } else if (decision == VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264 && !this.performanceNoH264Logged) {
            this.performanceNoH264Logged = true;
            this.fallbackReason = decision.reason();
            LOGGER.warn("AV1 持续性能超预算但同次 playurl 无 H.264 候选，保持当前后端: session={}, backend={}", this.sessionId(), this.actualDecoderBackend);
         }
      }
   }

   private synchronized void requestSustainedPerformanceFallback(
      VideoPerformanceFallbackPolicy.Decision decision, VideoPerformanceFallbackPolicy.Snapshot snapshot
   ) {
      if (!this.performanceFallbackLocked
         && !this.restartInProgress
         && !this.stopRequested
         && this.running
         && this.activeCandidate != null
         && this.activeCandidate.codecId() == 13
         && !this.candidates.stream().noneMatch(candidate -> candidate.codecId() == 7)) {
         this.performanceFallbackLocked = true;
         this.fallbackReason = decision.reason();
         long offsetMillis = this.currentRestartOffsetMillis();
         LOGGER.warn(
            "AV1 持续性能回退并锁定 H.264: session={}, reason={}, backend={}, actualFps={}/{}, avg={}ms, p95={}ms, starvation={}, dropped={} ({}), drift={}ms, driftGrowth={}ms, nativePeak={} bytes, surfaces={}, offset={}ms",
            new Object[]{
               this.sessionId(),
               this.fallbackReason,
               snapshot.backend(),
               String.format(Locale.ROOT, "%.2f", snapshot.actualDecodeFps()),
               snapshot.targetFps(),
               String.format(Locale.ROOT, "%.2f", snapshot.averageDecodeMillis()),
               String.format(Locale.ROOT, "%.2f", snapshot.p95DecodeMillis()),
               snapshot.starvationCount(),
               snapshot.droppedFrames(),
               String.format(Locale.ROOT, "%.2f%%", snapshot.droppedFrameRatio() * 100.0),
               snapshot.latestSyncDriftMillis(),
               snapshot.syncDriftGrowthMillis(),
               snapshot.nativeFrameBytesPeak(),
               snapshot.nativeSurfacePeak(),
               offsetMillis
            }
         );
         this.restartDecoder(this.targetWidth, this.targetHeight, offsetMillis, true);
      }
   }

   private void maybeRestartForRuntimeLag() {
      if (this.visualSyncActive() && this.presentation.restartAllowed() && this.hasFrame && this.hasVideoConsumer() && RUNTIME_LAG_RESTART_MILLIS > 0L) {
         long masterMillis = this.anchor.timeline().mediaMillis();
         if (masterMillis >= 0L) {
            long displayedMillis = this.mediaMillis();
            long queuedMillis = this.queuedMediaMillis();
            long bestVideoMillis = Math.max(displayedMillis, queuedMillis);
            long lagMillis = bestVideoMillis >= 0L ? masterMillis - bestVideoMillis : 0L;
            long now = System.nanoTime();
            if (lagMillis < RUNTIME_LAG_RESTART_MILLIS) {
               this.runtimeLagSinceNanoTime = 0L;
            } else if (this.runtimeLagSinceNanoTime == 0L) {
               this.runtimeLagSinceNanoTime = now;
            } else if (RUNTIME_LAG_CONFIRM_MILLIS <= 0L || now - this.runtimeLagSinceNanoTime >= RUNTIME_LAG_CONFIRM_MILLIS * 1000000L) {
               if (this.lastRuntimeLagRestartNanoTime == 0L || now - this.lastRuntimeLagRestartNanoTime >= RUNTIME_LAG_RESTART_COOLDOWN_MILLIS * 1000000L) {
                  long restartOffsetMillis = this.totalMillis > 0L ? Math.min(this.totalMillis, masterMillis) : masterMillis;
                  LOGGER.debug(
                     "视频运行期持续落后，主动重定位: session={}, lag={}ms, master={}ms, video={}ms, offset={}ms",
                     new Object[]{this.sessionId(), lagMillis, masterMillis, bestVideoMillis, restartOffsetMillis}
                  );
                  this.lastRuntimeLagRestartNanoTime = now;
                  this.runtimeLagSinceNanoTime = 0L;
                  this.restartDecoder(this.targetWidth, this.targetHeight, restartOffsetMillis, true);
               }
            }
         }
      } else {
         this.runtimeLagSinceNanoTime = 0L;
      }
   }

   private void recordAdaptiveUploadCost(long uploadNs) {
      if (uploadNs > VideoBillboardPreview.ADAPTIVE_FRAME_BUDGET_NS) {
         this.consecutiveBadUploads++;
         if (this.consecutiveBadUploads >= VideoBillboardPreview.ADAPTIVE_BAD_FRAME_THRESHOLD) {
            this.requestAdaptiveDownscale();
         }
      } else {
         this.consecutiveBadUploads = Math.max(0, this.consecutiveBadUploads - 2);
      }
   }

   private synchronized boolean requestAdaptiveDownscale() {
      int currentWidth = this.targetWidth;
      int currentHeight = this.targetHeight;
      if (currentWidth <= VideoBillboardPreview.MIN_ADAPTIVE_WIDTH) {
         return false;
      } else {
         int nextWidth = Math.max(VideoBillboardPreview.MIN_ADAPTIVE_WIDTH, Math.round(currentWidth * 0.75F));
         int nextHeight = Math.max(1, Math.round(currentHeight * ((float)nextWidth / currentWidth)));
         if (nextWidth < currentWidth && nextHeight < currentHeight) {
            long restartOffsetMillis = this.currentRestartOffsetMillis();
            LOGGER.warn(
               "视频会话上传持续超预算，优先保证游戏流畅: session={}, {}x{} -> {}x{}，offset={}ms",
               new Object[]{this.sessionId(), currentWidth, currentHeight, nextWidth, nextHeight, restartOffsetMillis}
            );
            this.restartDecoder(nextWidth, nextHeight, restartOffsetMillis);
            return true;
         } else {
            return false;
         }
      }
   }

   private long currentRestartOffsetMillis() {
      if (this.liveSource) {
         return 0L;
      } else {
         long displayed = this.mediaMillis();
         if (displayed >= 0L) {
            return this.totalMillis > 0L ? Math.min(this.totalMillis, displayed) : displayed;
         } else {
            long synced = this.anchor.timeline().mediaMillis();
            if (synced >= 0L) {
               return this.totalMillis > 0L ? Math.min(this.totalMillis, synced) : synced;
            } else {
               long base = Math.max(this.startOffsetMillis, this.decoderStartOffsetMillis);
               long elapsed = Math.max(0L, this.playbackNanos() / 1000000L);
               long value = Math.max(0L, base + elapsed);
               return this.totalMillis > 0L ? Math.min(this.totalMillis, value) : value;
            }
         }
      }
   }

   private void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis) {
      this.restartDecoder(nextWidth, nextHeight, restartOffsetMillis, false);
   }

   void restartDecoder(int nextWidth, int nextHeight, long restartOffsetMillis, boolean keepVisibleFrame) {
      if (this.restartInProgress) {
         LOGGER.debug("视频解码器重启正在等待旧 worker 退出，忽略重复请求: session={}", this.sessionId());
      } else {
         this.restartInProgress = true;
         this.restartState = VideoDecoderRestartState.CLOSING;
         long gen = this.generation.incrementAndGet();
         boolean preserveVisibleFrame = keepVisibleFrame
            && nextWidth == this.targetWidth
            && nextHeight == this.targetHeight
            && this.hasFrame
            && (this.textures.hasRgbaTexture() || this.textures.hasYuvTexture());
         AutoCloseable oldDecoder = this.decoder;
         this.decoder = null;
         CompletableFuture<Void> oldDecodeExit = this.decodeExit;
         CompletableFuture<Void> nativeTermination = CompletableFuture.completedFuture(null);
         if (oldDecoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
            nativeDecoder.requestClose();
            nativeTermination = nativeDecoder.terminationFuture();
         }

         CompletableFuture<Void> capturedNativeTermination = nativeTermination;
         Thread oldThread = this.decodeThread;
         if (oldThread != null) {
            oldThread.interrupt();
         }

         CompletableFuture<Void> closeCompletion = MediaCloseExecutor.closeAsyncStrict(oldDecoder, "adaptive video decoder " + this.sessionId());
         this.physicalCloseHandoff.attachClose(closeCompletion, nativeTermination, oldDecodeExit);
         this.frameQueue.clear();
         if (!preserveVisibleFrame) {
            this.textures.release();
         }

         this.targetWidth = nextWidth;
         this.targetHeight = nextHeight;
         this.hasFrame = preserveVisibleFrame;
         this.firstFrameLogged = false;
         this.firstDecodedNanoTime = 0L;
         this.startupBufferReady = false;
         this.networkFailure = false;
         this.terminalFailure = false;
         this.networkFailureNotified = false;
         this.startNanoTime = System.nanoTime();
         this.lastUploadPumpNanoTime = System.nanoTime();
         this.decoderStartOffsetMillis = Math.max(0L, restartOffsetMillis);
         this.adaptiveRestartOffsetMillis = this.decoderStartOffsetMillis;
         if (!preserveVisibleFrame) {
            this.lastUploadedPtsNanos = -1L;
            this.lastUploadedBaseOffsetMillis = -1L;
         }

         this.consecutiveBadUploads = 0;
         this.running = true;
         CompletableFuture<Void> closeBarrier = CompletableFuture.allOf(closeCompletion, capturedNativeTermination, oldDecodeExit);
         CompletableFuture.delayedExecutor(Math.max(1L, DECODER_RESTART_CLOSE_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS).execute(() -> {
            if (!closeBarrier.isDone() && gen == this.generation.get() && this.running && !this.stopRequested) {
               this.failRestartClose(gen, closeCompletion, capturedNativeTermination, oldDecodeExit);
            }
         });
         closeBarrier.whenComplete((ignored, error) -> {
            if (error == null) {
               this.completeRestartClose(gen, preserveVisibleFrame);
            } else {
               this.failRestartClose(gen, closeCompletion, capturedNativeTermination, oldDecodeExit);
            }
         });
      }
   }

   private synchronized void completeRestartClose(long gen, boolean preserveVisibleFrame) {
      if (this.restartState == VideoDecoderRestartState.CLOSING
         && gen == this.generation.get()
         && this.running
         && !this.stopRequested
         && this.hasVideoConsumer()) {
         this.restartInProgress = false;
         this.restartState = VideoDecoderRestartState.ACTIVE;
         this.decoderGenerationStartedNanoTime = System.nanoTime();
         this.candidateDecodeRunner
            .start(gen, preserveVisibleFrame ? "bili-video-" + this.sessionId() + "-resume" : "bili-video-" + this.sessionId() + "-adaptive");
      } else {
         if (gen == this.generation.get() && this.restartState == VideoDecoderRestartState.CLOSING) {
            this.restartInProgress = false;
            this.restartState = this.stopRequested ? VideoDecoderRestartState.STOPPED : VideoDecoderRestartState.ACTIVE;
         }
      }
   }

   private synchronized void failRestartClose(
      long gen, CompletableFuture<Void> closeCompletion, CompletableFuture<Void> nativeTermination, CompletableFuture<Void> oldDecodeExit
   ) {
      if (this.restartState == VideoDecoderRestartState.CLOSING && gen == this.generation.get() && !this.stopRequested && this.running) {
         this.generation.incrementAndGet();
         this.restartInProgress = false;
         this.restartState = VideoDecoderRestartState.FAILED_CLOSE;
         this.networkFailure = false;
         this.terminalFailure = true;
         this.frameQueue.clear();
         VideoZombieCloseSupervisor.global().track(this.sessionId(), gen, closeCompletion, nativeTermination, oldDecodeExit);
         LOGGER.error("旧视频解码器关闭超时，会话进入 FAILED_CLOSE，禁止迟到 generation 复活: session={}, timeout={}ms", this.sessionId(), DECODER_RESTART_CLOSE_TIMEOUT_MILLIS);
      }
   }

   private boolean shouldWaitForStartupBuffer() {
      int requiredFrames = VideoPipelineProperties.startupPrebufferFrames();
      if (requiredFrames > 1 && !this.hasFrame) {
         if (this.frameQueue.size() >= requiredFrames) {
            return false;
         } else {
            long firstDecodedNs = this.firstDecodedNanoTime;
            if (firstDecodedNs <= 0L) {
               return false;
            } else {
               long maxWaitNs = VideoPipelineProperties.startupPrebufferMaxWaitMillis() * 1000000L;
               boolean wait = System.nanoTime() - firstDecodedNs < maxWaitNs;
               return !wait ? false : wait;
            }
         }
      } else {
         return false;
      }
   }

   private boolean uploadDecodedFrameOnRenderThread(VideoBillboardState.DecodedFrame frame) {
      boolean uploaded = this.textures.uploadDecodedFrame(frame, this.targetWidth, this.targetHeight);
      this.hasFrame |= uploaded;
      return uploaded;
   }

   VideoBillboardState.ProjectorFrameSnapshot frameSnapshot(BlockPos projectorPos) {
      return this.presentation.frameSnapshot(projectorPos);
   }

   VideoBillboardState.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
      return this.presentation.displayFrameSnapshot(projectorPos);
   }

   VideoBillboardState.ProjectorFrameSnapshot realFrameSnapshot(BlockPos projectorPos) {
      return this.presentation.realFrameSnapshot(projectorPos);
   }

   VideoBillboardState.ProjectorFrameSnapshot failurePlaceholderSnapshot() {
      return this.presentation.failurePlaceholderSnapshot();
   }

   static VideoBillboardState.ProjectorFrameSnapshot loadingPlaceholderSnapshot(long startedNanoTime) {
      return VideoPlaceholderFrames.loading(startedNanoTime);
   }

   static VideoBillboardState.ProjectorFrameSnapshot idlePlaceholderSnapshot() {
      return VideoPlaceholderFrames.idle();
   }

   VideoBillboardState.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
      return this.presentation.turntableFrameSnapshot(turntablePos);
   }

   VideoBillboardState.ProjectorFrameSnapshot previewFrameSnapshot() {
      return this.presentation.previewFrameSnapshot();
   }

   void submit(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera) {
      this.presentation.submit(collector, minecraft, camera);
   }

   void renderYuvImmediate(RenderLevelStageEvent event, String route) {
      this.presentation.renderYuvImmediate(event, route);
   }

   boolean isWithinAudioRange(Minecraft minecraft) {
      return this.presentation.isWithinAudioRange(minecraft);
   }

   boolean isRunningAtOffset(long requestedOffsetMillis) {
      return this.isRunningAtOffset(requestedOffsetMillis, 1500L);
   }

   boolean isRunningAtOffset(long requestedOffsetMillis, long toleranceMillis) {
      if (!this.running) {
         return false;
      } else if (this.restartState.pinsRegistryEntry()) {
         return true;
      } else {
         long tolerance = Math.max(0L, toleranceMillis);
         if (this.hasFrame) {
            long elapsedMillis = Math.max(0L, (System.nanoTime() - this.startNanoTime) / 1000000L);
            long estimatedDisplayedMillis = this.totalMillis > 0L
               ? Math.min(this.totalMillis, this.startOffsetMillis + elapsedMillis)
               : this.startOffsetMillis + elapsedMillis;
            if (Math.abs(estimatedDisplayedMillis - Math.max(0L, requestedOffsetMillis)) < tolerance) {
               return true;
            }
         }

         if (!this.hasFrame) {
            return true;
         } else {
            long expectedOffset = Math.max(this.startOffsetMillis, this.decoderStartOffsetMillis);
            return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < tolerance;
         }
      }
   }

   boolean canChaseToOffset(long requestedOffsetMillis) {
      if (!this.running) {
         return false;
      } else if (this.restartState.pinsRegistryEntry()) {
         return true;
      } else if (!this.hasFrame) {
         return true;
      } else {
         long target = this.totalMillis > 0L ? Math.min(this.totalMillis, Math.max(0L, requestedOffsetMillis)) : Math.max(0L, requestedOffsetMillis);
         long current = this.mediaMillis();
         if (current < 0L) {
            return true;
         } else {
            long delta = target - current;
            if (delta >= 0L) {
               long queuedTargetMillis = Math.max(this.startOffsetMillis, this.decoderStartOffsetMillis)
                  + Math.max(0L, this.frameQueue.latestPtsNanos() / 1000000L);
               return target <= queuedTargetMillis + Math.max(0L, CHASE_WINDOW_MILLIS);
            } else {
               return -delta <= Math.max(0L, SLOWDOWN_WINDOW_MILLIS);
            }
         }
      }
   }

   synchronized boolean requestSyncedReseek(long requestedOffsetMillis) {
      if (this.running
         && !this.stopRequested
         && !this.terminalFailure
         && !this.networkFailure
         && this.restartState == VideoDecoderRestartState.ACTIVE
         && !this.restartInProgress
         && this.hasVideoConsumer()) {
         long target = this.totalMillis > 0L ? Math.min(this.totalMillis, Math.max(0L, requestedOffsetMillis)) : Math.max(0L, requestedOffsetMillis);
         this.restartDecoder(this.targetWidth, this.targetHeight, target, this.hasFrame);
         return true;
      } else {
         return false;
      }
   }

   Object replacementOwnerKey() {
      return this.anchor.replacementOwnerKey();
   }

   ProjectionReplacementGate.CloseHandoff closeHandoff() {
      return this.physicalCloseHandoff.snapshot();
   }

   void replaceProjectors(Collection<BlockPos> positions) {
      this.consumers.replaceProjectors(VideoBillboardPreview.immutablePositions(positions));
   }

   List<BlockPos> projectorPositions() {
      return this.consumers.projectors();
   }

   void addProjector(BlockPos pos) {
      if (pos != null) {
         this.consumers.addProjector(pos.immutable());
      }
   }

   void removeProjector(BlockPos pos) {
      this.consumers.removeProjector(pos);
   }

   boolean isForTurntable(BlockPos pos) {
      return this.anchor.isForTurntable(pos);
   }

   boolean isSession(String candidateSessionId) {
      return this.sessionId().equals(candidateSessionId != null ? candidateSessionId : "");
   }

   boolean hasProjector(BlockPos pos) {
      return this.consumers.containsProjector(pos);
   }

   String sessionId() {
      return this.playbackSessionId.value();
   }

   PlaybackSessionId playbackSessionId() {
      return this.playbackSessionId;
   }

   long startNanoTime() {
      return this.startNanoTime;
   }

   boolean hasProjectors() {
      return this.consumers.hasProjectors();
   }

   int projectorCount() {
      return this.consumers.projectorCount();
   }

   boolean hasVideoConsumer() {
      return this.consumers.hasDirectConsumer() || this.hasHolographicTurntableConsumer();
   }

   void setGuiConsumer(boolean value) {
      this.consumers.setGuiConsumer(value);
      if (value) {
         this.prewarmVisible = true;
         this.offscreenSinceNanoTime = 0L;
         this.lastVisibleNanoTime = System.nanoTime();
         this.grantDecodeAdmission();
      }
   }

   boolean hasGuiConsumer() {
      return this.consumers.hasGuiConsumer();
   }

   boolean hasHolographicTurntableConsumer() {
      Minecraft minecraft = Minecraft.getInstance();
      if (ClientDisplayProperties.holographicWorldScreenEnabled() && minecraft != null && minecraft.level != null) {
         for (HolographicGlassesItem.ScreenBinding binding : HolographicGlassesClient.screenBindings()) {
            if (binding.source() != null
               && binding.source().isTurntable()
               && minecraft.level.dimension().equals(binding.source().dimension())
               && this.anchor.isForTurntable(binding.source().pos())) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   boolean containsProjector(BlockPos pos) {
      return this.consumers.containsProjector(pos);
   }

   boolean isRunning() {
      return this.running && this.restartState != VideoDecoderRestartState.FAILED_CLOSE;
   }

   boolean hasFrame() {
      return this.hasFrame;
   }

   synchronized boolean ensureFirstFrameProgress() {
      if (!this.running || this.hasFrame || this.terminalFailure) {
         return false;
      } else if (this.decodeAdmissionGranted && this.prewarmVisible) {
         long waitingMillis = this.startNanoTime > 0L ? Math.max(0L, (System.nanoTime() - this.startNanoTime) / 1000000L) : 0L;
         VideoFirstFrameRecoveryPolicy.Decision decision = VideoFirstFrameRecoveryPolicy.decide(
            this.running,
            this.hasFrame,
            this.restartInProgress,
            waitingMillis,
            FIRST_FRAME_TIMEOUT_MILLIS,
            this.firstFrameRecoveryAttempts,
            MAX_FIRST_FRAME_RECOVERY_ATTEMPTS
         );
         if (decision == VideoFirstFrameRecoveryPolicy.Decision.RESTART) {
            this.firstFrameRecoveryAttempts++;
            long restartOffsetMillis = this.currentRestartOffsetMillis();
            LOGGER.warn(
               "视频首帧等待超时，执行受控恢复: session={}, wait={}ms, attempt={}/{}, offset={}ms",
               new Object[]{this.sessionId(), waitingMillis, this.firstFrameRecoveryAttempts, MAX_FIRST_FRAME_RECOVERY_ATTEMPTS, restartOffsetMillis}
            );
            this.restartDecoder(this.targetWidth, this.targetHeight, restartOffsetMillis, false);
         } else if (decision == VideoFirstFrameRecoveryPolicy.Decision.FAIL) {
            this.failStalledStartup(waitingMillis);
         }

         return true;
      } else {
         return true;
      }
   }

   private void failStalledStartup(long waitingMillis) {
      long gen = this.generation.incrementAndGet();
      AutoCloseable stalledDecoder = this.decoder;
      this.decoder = null;
      Thread stalledThread = this.decodeThread;
      if (stalledThread != null) {
         stalledThread.interrupt();
      }

      this.frameQueue.clear();
      this.restartInProgress = false;
      this.networkFailure = false;
      this.terminalFailure = true;
      this.running = true;
      if (stalledDecoder instanceof Fmp4NativeVideoDecoder nativeDecoder) {
         nativeDecoder.requestClose();
      }

      MediaCloseExecutor.closeAsync(stalledDecoder, "stalled startup video decoder " + this.sessionId());
      LOGGER.error(
         "视频首帧恢复次数耗尽，结束永久 Loading: session={}, generation={}, wait={}ms, attempts={}",
         new Object[]{this.sessionId(), gen, waitingMillis, this.firstFrameRecoveryAttempts}
      );
   }

   boolean hasNetworkFailure() {
      return this.networkFailure;
   }

   boolean hasTerminalFailure() {
      return this.terminalFailure || this.restartState.isTerminalFailure();
   }

   synchronized boolean retryNetworkFailure() {
      if (this.networkFailure && this.hasVideoConsumer()) {
         long retryOffsetMillis = this.currentRestartOffsetMillis();
         long syncedOffsetMillis = this.anchor.timeline().mediaMillis();
         if (syncedOffsetMillis >= 0L) {
            retryOffsetMillis = Math.max(retryOffsetMillis, syncedOffsetMillis);
            if (this.totalMillis > 0L) {
               retryOffsetMillis = Math.min(this.totalMillis, retryOffsetMillis);
            }
         }

         LOGGER.info("手动重试视频网络连接: session={}, offset={}ms", this.sessionId(), retryOffsetMillis);
         this.restartDecoder(this.targetWidth, this.targetHeight, retryOffsetMillis, this.hasFrame);
         return true;
      } else {
         return false;
      }
   }

   void notifyNetworkFailure() {
      if (!this.networkFailureNotified) {
         this.networkFailureNotified = true;
         Minecraft minecraft = Minecraft.getInstance();
         minecraft.execute(
            () -> {
               if (this.networkFailure && minecraft.player != null) {
                  Component retry = Component.literal("[重试视频]")
                     .withStyle(
                        style -> style.withColor(ChatFormatting.GOLD)
                           .withUnderlined(true)
                           .withClickEvent(new ClickEvent(Action.RUN_COMMAND, "/ncpbc video retry"))
                           .withHoverEvent(new HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("点击重新连接视频流")))
                     );
                  minecraft.player.sendSystemMessage(Component.literal("视频网络连接失败 ").withStyle(ChatFormatting.RED).append(retry));
               }
            }
         );
      }
   }

   long mediaMillis() {
      long uploadedPts = this.lastUploadedPtsNanos;
      return this.hasFrame
         ? VideoMediaTimestampPolicy.absoluteMillis(this.lastUploadedBaseOffsetMillis, uploadedPts, AUDIO_OUTPUT_LATENCY_COMPENSATION_MILLIS, this.totalMillis)
         : -1L;
   }

   long queuedMediaMillis() {
      long latestPts = this.frameQueue.latestPtsNanos();
      if (latestPts <= 0L) {
         return -1L;
      } else {
         long baseOffsetMillis = Math.max(this.startOffsetMillis, this.decoderStartOffsetMillis);
         long value = Math.max(0L, baseOffsetMillis + latestPts / 1000000L);
         return this.totalMillis > 0L ? Math.min(this.totalMillis, value) : value;
      }
   }

   boolean visualSyncActive() {
      return VideoVisualSyncPolicy.active(this.running, this.terminalFailure, this.prewarmVisible, this.candidateDecodeRunner.isOffscreenPauseActive());
   }

   private void suspendVisualSync() {
      this.runtimeLagSinceNanoTime = 0L;
      if (!this.visualSyncSuspended) {
         this.visualSyncSuspended = true;
         this.performanceMonitor.resetSyncDriftWindow();
      }
   }

   boolean visualSyncActiveForBench() {
      return this.visualSyncActive();
   }

   long generationForBench() {
      return this.generation.get();
   }

   long decoderStartOffsetMillisForBench() {
      return this.decoderStartOffsetMillis;
   }

   String restartStateForBench() {
      return this.restartState.name();
   }

   boolean prewarmVisibleForBench() {
      return this.prewarmVisible;
   }

   boolean offscreenPauseActiveForBench() {
      return this.candidateDecodeRunner.isOffscreenPauseActive();
   }

   VideoBillboardState.VideoStatus status() {
      BiliVideoStreamResolver.VideoCandidate candidate = this.activeCandidate;
      int requestedQuality = this.candidates.stream().mapToInt(option -> option.quality()).max().orElse(0);
      return new VideoBillboardState.VideoStatus(
         this.targetWidth,
         this.targetHeight,
         candidate != null ? candidate.fps() : this.fps,
         this.hasFrame,
         true,
         requestedQuality,
         candidate != null ? candidate.quality() : 0,
         candidate != null ? candidate.codecId() : 0,
         this.actualDecoderBackend,
         this.fallbackReason
      );
   }

   synchronized void stop() {
      this.closer.stop();
   }

   synchronized void abandonBeforeStart() {
      this.closer.abandonBeforeStart();
   }
}
