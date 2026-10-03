package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

import java.util.Locale;

final class MemoryCircuitBreaker {
   private static final long MIN_BYTE_GROWTH = 8388608L;
   private final MemoryCircuitBreaker.Limits limits;
   private int pressureSamples;
   private boolean open;
   private long reopenAfterNanos;
   private String reason = "";
   private MemoryCircuitBreaker.Sample previousSample;

   MemoryCircuitBreaker(MemoryCircuitBreaker.Limits limits) {
      this.limits = limits;
   }

   synchronized MemoryCircuitBreaker.Evaluation evaluate(long nowNanos, MemoryCircuitBreaker.Sample sample) {
      if (this.open) {
         if (nowNanos >= this.reopenAfterNanos && this.belowRecoveryWatermark(sample)) {
            this.open = false;
            this.pressureSamples = 0;
            this.reason = "";
            this.previousSample = sample;
            return new MemoryCircuitBreaker.Evaluation(false, true, "resources returned below recovery watermark");
         } else {
            return MemoryCircuitBreaker.Evaluation.none();
         }
      } else {
         MemoryCircuitBreaker.Sample previous = this.previousSample;
         this.previousSample = sample;
         String growing = previous != null ? this.growingExceededLimit(previous, sample) : "";
         if (growing.isEmpty()) {
            this.pressureSamples = 0;
            return MemoryCircuitBreaker.Evaluation.none();
         } else {
            this.pressureSamples++;
            return this.pressureSamples < this.limits.consecutiveSamples() ? MemoryCircuitBreaker.Evaluation.none() : this.open(nowNanos, growing);
         }
      }
   }

   synchronized MemoryCircuitBreaker.Evaluation forceOpen(long nowNanos, String forcedReason) {
      return this.open(nowNanos, forcedReason != null && !forcedReason.isBlank() ? forcedReason : "allocation failure");
   }

   synchronized boolean allowMediaStart() {
      return !this.open;
   }

   synchronized String reason() {
      return this.reason;
   }

   private MemoryCircuitBreaker.Evaluation open(long nowNanos, String openReason) {
      boolean newlyOpened = !this.open;
      this.open = true;
      this.reason = openReason;
      this.reopenAfterNanos = saturatedAdd(nowNanos, this.limits.cooldownNanos());
      return newlyOpened ? new MemoryCircuitBreaker.Evaluation(true, false, this.reason) : MemoryCircuitBreaker.Evaluation.none();
   }

   private String growingExceededLimit(MemoryCircuitBreaker.Sample previous, MemoryCircuitBreaker.Sample sample) {
      if (growingBytes(previous.ownedNativeBytes(), sample.ownedNativeBytes(), this.limits.ownedNativeBytes())) {
         return "NCPB native buffers " + mib(sample.ownedNativeBytes()) + " > " + mib(this.limits.ownedNativeBytes());
      } else if (growingBytes(previous.gpuPboBytes(), sample.gpuPboBytes(), this.limits.gpuPboBytes())) {
         return "NCPB PBO estimate " + mib(sample.gpuPboBytes()) + " > " + mib(this.limits.gpuPboBytes());
      } else if (growingBytes(previous.ffmpegBytes(), sample.ffmpegBytes(), this.limits.ffmpegBytes())) {
         return "FFmpeg heap " + mib(sample.ffmpegBytes()) + " > " + mib(this.limits.ffmpegBytes());
      } else if (growingBytes(previous.d3d11LogicalBytes(), sample.d3d11LogicalBytes(), this.limits.d3d11LogicalBytes())) {
         return "D3D11 logical resources " + mib(sample.d3d11LogicalBytes()) + " > " + mib(this.limits.d3d11LogicalBytes());
      } else {
         return growingCount(previous.d3d11Surfaces(), sample.d3d11Surfaces(), this.limits.d3d11Surfaces())
            ? "D3D11 surfaces " + sample.d3d11Surfaces() + " > " + this.limits.d3d11Surfaces()
            : "";
      }
   }

   private static boolean growingBytes(long previous, long current, long limit) {
      long minimumGrowth = Math.min(8388608L, Math.max(1L, limit / 100L));
      return exceeds(current, limit) && current > previous && current - previous >= minimumGrowth;
   }

   private static boolean growingCount(long previous, long current, long limit) {
      return exceeds(current, limit) && current > previous;
   }

   private boolean belowRecoveryWatermark(MemoryCircuitBreaker.Sample sample) {
      return this.below(sample.ownedNativeBytes(), this.limits.ownedNativeBytes())
         && this.below(sample.gpuPboBytes(), this.limits.gpuPboBytes())
         && this.below(sample.ffmpegBytes(), this.limits.ffmpegBytes())
         && this.below(sample.d3d11LogicalBytes(), this.limits.d3d11LogicalBytes())
         && this.below(sample.d3d11Surfaces(), this.limits.d3d11Surfaces());
   }

   private boolean below(long value, long limit) {
      return limit <= 0L || value < (long)(limit * this.limits.recoveryRatio());
   }

   private static boolean exceeds(long value, long limit) {
      return limit > 0L && value > limit;
   }

   private static long saturatedAdd(long left, long right) {
      long result = left + right;
      return result < left ? Long.MAX_VALUE : result;
   }

   private static String mib(long bytes) {
      return String.format(Locale.ROOT, "%.1fMiB", bytes / 1048576.0);
   }

   record Evaluation(boolean tripped, boolean recovered, String reason) {
      static MemoryCircuitBreaker.Evaluation none() {
         return new MemoryCircuitBreaker.Evaluation(false, false, "");
      }
   }

   record Limits(
      long ownedNativeBytes,
      long gpuPboBytes,
      long ffmpegBytes,
      long d3d11LogicalBytes,
      long d3d11Surfaces,
      int consecutiveSamples,
      long cooldownNanos,
      double recoveryRatio
   ) {
      Limits(
         long ownedNativeBytes,
         long gpuPboBytes,
         long ffmpegBytes,
         long d3d11LogicalBytes,
         long d3d11Surfaces,
         int consecutiveSamples,
         long cooldownNanos,
         double recoveryRatio
      ) {
         consecutiveSamples = Math.max(1, consecutiveSamples);
         cooldownNanos = Math.max(0L, cooldownNanos);
         recoveryRatio = Math.max(0.05, Math.min(0.95, recoveryRatio));
         this.ownedNativeBytes = ownedNativeBytes;
         this.gpuPboBytes = gpuPboBytes;
         this.ffmpegBytes = ffmpegBytes;
         this.d3d11LogicalBytes = d3d11LogicalBytes;
         this.d3d11Surfaces = d3d11Surfaces;
         this.consecutiveSamples = consecutiveSamples;
         this.cooldownNanos = cooldownNanos;
         this.recoveryRatio = recoveryRatio;
      }
   }

   record Sample(long ownedNativeBytes, long gpuPboBytes, long ffmpegBytes, long d3d11LogicalBytes, long d3d11Surfaces) {
   }
}
