package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import java.util.Arrays;

public final class VideoPerformanceMonitor {
   private static final int MAX_DECODE_SAMPLES = 512;
   private static final long RESOURCE_SAMPLE_INTERVAL_NANOS = 500000000L;
   private final long[] decodeSamples = new long[512];
   private int decodeSampleCount;
   private int decodeSampleCursor;
   private long decodeNanosTotal;
   private long decodedFrames;
   private long starvationCount;
   private long droppedFrames;
   private long startedNanos;
   private long suspendedStartedNanos;
   private long suspendedNanos;
   private long lastResourceSampleNanos;
   private long nativeFrameBytesPeak;
   private long nativeSurfacePeak;
   private long firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
   private long latestSyncDriftMillis;
   private long previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
   private int consecutiveDriftGrowthSamples;
   private long observationEpoch;
   private int targetFps = 1;
   private String backend = "unknown";
   private boolean started;

   public synchronized void start(long nowNanos, int targetFps, String backend) {
      Arrays.fill(this.decodeSamples, 0L);
      this.decodeSampleCount = 0;
      this.decodeSampleCursor = 0;
      this.decodeNanosTotal = 0L;
      this.decodedFrames = 0L;
      this.starvationCount = 0L;
      this.droppedFrames = 0L;
      this.startedNanos = nowNanos;
      this.suspendedStartedNanos = 0L;
      this.suspendedNanos = 0L;
      this.lastResourceSampleNanos = 0L;
      this.nativeFrameBytesPeak = 0L;
      this.nativeSurfacePeak = 0L;
      this.firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
      this.latestSyncDriftMillis = 0L;
      this.previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
      this.consecutiveDriftGrowthSamples = 0;
      this.targetFps = Math.max(1, targetFps);
      this.backend = backend != null && !backend.isBlank() ? backend : "unknown";
      this.observationEpoch++;
      this.started = true;
   }

   public synchronized void pause(long nowNanos) {
      if (this.started && this.suspendedStartedNanos == 0L) {
         this.suspendedStartedNanos = Math.max(this.startedNanos, nowNanos);
      }
   }

   public synchronized void resume(long nowNanos) {
      if (this.started && this.suspendedStartedNanos != 0L) {
         this.suspendedNanos = this.suspendedNanos + Math.max(0L, nowNanos - this.suspendedStartedNanos);
         this.suspendedStartedNanos = 0L;
      }
   }

   public synchronized void recordDecodedFrame(long decodeNanos) {
      if (this.started) {
         long safe = Math.max(0L, decodeNanos);
         this.decodedFrames++;
         this.decodeNanosTotal += safe;
         this.decodeSamples[this.decodeSampleCursor] = safe;
         this.decodeSampleCursor = (this.decodeSampleCursor + 1) % this.decodeSamples.length;
         this.decodeSampleCount = Math.min(this.decodeSamples.length, this.decodeSampleCount + 1);
      }
   }

   public synchronized void recordStarvation() {
      if (this.started) {
         this.starvationCount++;
      }
   }

   public synchronized void recordDroppedFrames(long count) {
      if (this.started && count > 0L) {
         this.droppedFrames += count;
      }
   }

   public synchronized void recordSyncDriftMillis(long driftMillis) {
      if (this.started) {
         long magnitude = driftMillis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(driftMillis);
         this.latestSyncDriftMillis = driftMillis;
         if (this.firstSyncDriftMagnitudeMillis == Long.MIN_VALUE) {
            this.firstSyncDriftMagnitudeMillis = magnitude;
         }

         if (this.previousSyncDriftMagnitudeMillis != Long.MIN_VALUE && magnitude > this.previousSyncDriftMagnitudeMillis + 5L) {
            this.consecutiveDriftGrowthSamples++;
         } else if (this.previousSyncDriftMagnitudeMillis != Long.MIN_VALUE && magnitude <= this.previousSyncDriftMagnitudeMillis) {
            this.consecutiveDriftGrowthSamples = 0;
         }

         this.previousSyncDriftMagnitudeMillis = magnitude;
      }
   }

   public synchronized void resetSyncDriftWindow() {
      this.firstSyncDriftMagnitudeMillis = Long.MIN_VALUE;
      this.latestSyncDriftMillis = 0L;
      this.previousSyncDriftMagnitudeMillis = Long.MIN_VALUE;
      this.consecutiveDriftGrowthSamples = 0;
   }

   public void sampleNativeResources(long nowNanos) {
      long epoch;
      synchronized (this) {
         if (!this.started || this.lastResourceSampleNanos != 0L && nowNanos - this.lastResourceSampleNanos < 500000000L) {
            return;
         }

         this.lastResourceSampleNanos = nowNanos;
         epoch = this.observationEpoch;
      }

      VideoNativeDecoder.NativeMemoryStats stats = VideoNativeDecoder.nativeMemoryStats();
      synchronized (this) {
         if (this.started && epoch == this.observationEpoch && stats.available()) {
            this.nativeFrameBytesPeak = Math.max(this.nativeFrameBytesPeak, Math.max(stats.ffmpegCurrentBytes(), stats.d3d11LogicalBytesCurrent()));
            this.nativeSurfacePeak = Math.max(this.nativeSurfacePeak, stats.d3d11SurfaceCurrent());
         }
      }
   }

   public synchronized VideoPerformanceFallbackPolicy.Snapshot snapshot(long nowNanos) {
      long observationNanos = this.observationNanos(nowNanos);
      double seconds = observationNanos / 1.0E9;
      double actualFps = seconds > 0.0 ? this.decodedFrames / seconds : 0.0;
      double averageMillis = this.decodedFrames > 0L ? (double)this.decodeNanosTotal / this.decodedFrames / 1000000.0 : 0.0;
      long[] sorted = Arrays.copyOf(this.decodeSamples, this.decodeSampleCount);
      Arrays.sort(sorted);
      double p95Millis = sorted.length == 0 ? 0.0 : sorted[Math.min(sorted.length - 1, (int)Math.ceil(sorted.length * 0.95) - 1)] / 1000000.0;
      long latestMagnitude = this.latestSyncDriftMillis == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(this.latestSyncDriftMillis);
      long driftGrowth = this.firstSyncDriftMagnitudeMillis == Long.MIN_VALUE ? 0L : Math.max(0L, latestMagnitude - this.firstSyncDriftMagnitudeMillis);
      long totalOutcomes = this.decodedFrames + this.droppedFrames;
      double dropRatio = totalOutcomes > 0L ? (double)this.droppedFrames / totalOutcomes : 0.0;
      return new VideoPerformanceFallbackPolicy.Snapshot(
         observationNanos / 1000000L,
         this.targetFps,
         this.decodedFrames,
         actualFps,
         averageMillis,
         p95Millis,
         this.starvationCount,
         this.droppedFrames,
         dropRatio,
         this.latestSyncDriftMillis,
         driftGrowth,
         this.consecutiveDriftGrowthSamples,
         this.backend,
         this.nativeFrameBytesPeak,
         this.nativeSurfacePeak
      );
   }

   public synchronized boolean started() {
      return this.started;
   }

   private long observationNanos(long nowNanos) {
      if (!this.started) {
         return 0L;
      } else {
         long pendingSuspension = this.suspendedStartedNanos != 0L ? Math.max(0L, nowNanos - this.suspendedStartedNanos) : 0L;
         return Math.max(0L, nowNanos - this.startedNanos - this.suspendedNanos - pendingSuspension);
      }
   }
}
