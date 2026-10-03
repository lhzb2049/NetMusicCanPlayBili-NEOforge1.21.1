package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceFallbackPolicy;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPerformanceMonitor;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

final class HandheldVideoSession implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   final HandheldDeviceVideoState owner;
   final HandheldPlaybackKey key;
   final long decoderStartOffsetMillis;
   final AtomicBoolean closed = new AtomicBoolean(false);
   final AtomicReference<Fmp4NativeVideoDecoder> decoder = new AtomicReference<>();
   final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
   final AtomicReference<CompletableFuture<Void>> latestNativeTermination = new AtomicReference<>(CompletableFuture.completedFuture(null));
   final CompletableFuture<Void> physicalTermination = new CompletableFuture<>();
   final VideoPerformanceMonitor performanceMonitor = new VideoPerformanceMonitor();
   final boolean h264CandidateAvailable;
   final AtomicBoolean performanceFallbackRequested = new AtomicBoolean(false);
   volatile boolean performanceFallbackLocked;
   volatile int activeCodecId;
   volatile int actualQuality;
   volatile String actualBackend = "unknown";
   volatile String fallbackReason = "";
   volatile String pendingFallbackReason = "performance";
   volatile boolean performanceNoH264Notified;

   HandheldVideoSession(
      HandheldDeviceVideoState owner, HandheldPlaybackKey key, long decoderStartOffsetMillis, List<BiliVideoStreamResolver.VideoCandidate> candidates
   ) {
      this.owner = Objects.requireNonNull(owner);
      this.key = Objects.requireNonNull(key);
      this.decoderStartOffsetMillis = Math.max(0L, decoderStartOffsetMillis);
      List<BiliVideoStreamResolver.VideoCandidate> safeCandidates = candidates != null ? candidates : List.of();
      this.h264CandidateAvailable = safeCandidates.stream().anyMatch(candidate -> candidate.codecId() == 7);
      if (this.h264CandidateAvailable && safeCandidates.stream().noneMatch(candidate -> candidate.codecId() == 13)) {
         this.fallbackReason = "no-av1-stream";
      }
   }

   void startPerformanceObservation(BiliVideoStreamResolver.ResolvedVideoStream stream, Fmp4NativeVideoDecoder decoder, long nowNanos) {
      this.activeCodecId = stream.codecId();
      this.actualQuality = stream.quality();
      this.actualBackend = decoder != null && decoder.actualHwaccel() != null ? decoder.actualHwaccel() : "unknown";
      this.performanceMonitor.start(nowNanos, stream.fps(), this.actualBackend);
   }

   boolean evaluatePerformance(HandheldDeviceVideoState state, long nowNanos) {
      this.performanceMonitor.sampleNativeResources(nowNanos);
      VideoPerformanceFallbackPolicy.Snapshot snapshot = this.performanceMonitor.snapshot(nowNanos);
      VideoPerformanceFallbackPolicy.Decision decision = VideoPerformanceFallbackPolicy.decide(
         snapshot, this.activeCodecId == 13, this.h264CandidateAvailable, this.performanceFallbackLocked
      );
      if (decision == VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264 && !this.performanceNoH264Notified) {
         this.performanceNoH264Notified = true;
         this.fallbackReason = "no-h264-candidate";
         state.statusText = MP4HandheldVideoClient.playingStatus(this, this.actualQuality, this.activeCodecId, this.actualBackend);
         LOGGER.warn("MP4 横屏 AV1 性能超预算但同次 playurl 无 H.264 候选: session={} backend={}", this.key.sessionId(), this.actualBackend);
      }

      if (decision.shouldFallback() && this.performanceFallbackRequested.compareAndSet(false, true)) {
         this.pendingFallbackReason = decision.reason();
         state.statusText = "AV1 性能不足，切换 H.264...";
         Fmp4NativeVideoDecoder attached = this.decoder();
         if (attached != null) {
            attached.requestClose();
         }

         LOGGER.warn(
            "MP4 横屏 AV1 性能预算触发: session={} reason={} backend={} actualFps={}/{} avg={}ms p95={}ms starvation={} dropped={} driftGrowth={}ms nativePeak={} surfaces={}",
            new Object[]{
               this.key.sessionId(),
               this.pendingFallbackReason,
               snapshot.backend(),
               String.format(Locale.ROOT, "%.2f", snapshot.actualDecodeFps()),
               snapshot.targetFps(),
               String.format(Locale.ROOT, "%.2f", snapshot.averageDecodeMillis()),
               String.format(Locale.ROOT, "%.2f", snapshot.p95DecodeMillis()),
               snapshot.starvationCount(),
               snapshot.droppedFrames(),
               snapshot.syncDriftGrowthMillis(),
               snapshot.nativeFrameBytesPeak(),
               snapshot.nativeSurfacePeak()
            }
         );
         return true;
      } else {
         return false;
      }
   }

   void observeTimelineAndEvaluate(HandheldDeviceVideoState state, long visualMillis) {
      long latestMillis = HandheldVideoFrameTimeline.latestFrameMillis(state, this);
      if (visualMillis >= 0L && latestMillis >= 0L) {
         this.performanceMonitor.recordSyncDriftMillis(visualMillis - latestMillis);
      }

      this.evaluatePerformance(state, System.nanoTime());
   }

   void lockPerformanceFallback(String reason) {
      this.performanceFallbackLocked = true;
      this.performanceFallbackRequested.set(false);
      this.fallbackReason = reason != null && !reason.isBlank() ? reason : "performance";
   }

   boolean attachDecoder(Fmp4NativeVideoDecoder value) {
      Objects.requireNonNull(value);
      synchronized (this.owner.lifecycleLock) {
         if (!this.closed.get() && this.owner.activeSession == this && this.key.equals(this.owner.activeKey)) {
            this.decoder.set(value);
            return true;
         } else {
            value.requestClose();
            return false;
         }
      }
   }

   void trackNative(CompletableFuture<Void> nativeTermination) {
      this.latestNativeTermination.set(Objects.requireNonNull(nativeTermination));
   }

   void detachDecoder(Fmp4NativeVideoDecoder value) {
      this.decoder.compareAndSet(value, null);
   }

   Fmp4NativeVideoDecoder decoder() {
      return this.decoder.get();
   }

   void completeDecodeTaskExit() {
      CompletableFuture<Void> lastNativeTermination = this.latestNativeTermination.get();
      this.decodeExit.complete(null);
      lastNativeTermination.whenComplete((ignored, error) -> {
         if (error == null) {
            this.physicalTermination.complete(null);
         } else {
            this.physicalTermination.completeExceptionally(error);
         }
      });
   }

   @Override
   public void close() {
      synchronized (this.owner.lifecycleLock) {
         this.closed.set(true);
         Fmp4NativeVideoDecoder attached = this.decoder.get();
         if (attached != null) {
            attached.requestClose();
         }
      }
   }
}
