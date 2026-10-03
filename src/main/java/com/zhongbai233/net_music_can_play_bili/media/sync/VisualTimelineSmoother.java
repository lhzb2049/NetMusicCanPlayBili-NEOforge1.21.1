package com.zhongbai233.net_music_can_play_bili.media.sync;

public final class VisualTimelineSmoother {
   private final long hardSyncMillis;
   private final long maxCorrectionMillis;
   private final double correctionRatio;
   private long anchorNanos;
   private long anchorMillis;
   private long lastMillis = -1L;

   public VisualTimelineSmoother(long hardSyncMillis, long maxCorrectionMillis, double correctionRatio) {
      this.hardSyncMillis = Math.max(0L, hardSyncMillis);
      this.maxCorrectionMillis = Math.max(0L, maxCorrectionMillis);
      this.correctionRatio = Math.max(0.0, Math.min(1.0, correctionRatio));
   }

   public synchronized long sample(long mediaMillis, long totalMillis, long nowNanos) {
      long media = clamp(mediaMillis, totalMillis);
      if (this.lastMillis >= 0L && nowNanos > 0L) {
         long current = this.extrapolated(nowNanos, totalMillis);
         long drift = media - current;
         if (drift >= this.hardSyncMillis) {
            return this.reset(media, totalMillis, nowNanos);
         } else if (drift <= -this.hardSyncMillis) {
            this.anchorNanos = nowNanos;
            this.anchorMillis = this.lastMillis;
            return this.lastMillis;
         } else {
            long correction = Math.round(drift * this.correctionRatio);
            correction = Math.max(-this.maxCorrectionMillis, Math.min(this.maxCorrectionMillis, correction));
            long candidate = clamp(current + correction, totalMillis);
            if (candidate < this.lastMillis) {
               candidate = this.lastMillis;
            }

            this.anchorNanos = nowNanos;
            this.anchorMillis = candidate;
            this.lastMillis = candidate;
            return candidate;
         }
      } else {
         return this.reset(media, totalMillis, nowNanos);
      }
   }

   public synchronized long reset(long mediaMillis, long totalMillis, long nowNanos) {
      this.anchorNanos = Math.max(0L, nowNanos);
      this.anchorMillis = clamp(mediaMillis, totalMillis);
      this.lastMillis = this.anchorMillis;
      return this.anchorMillis;
   }

   private long extrapolated(long nowNanos, long totalMillis) {
      long elapsedMillis = nowNanos > this.anchorNanos ? (nowNanos - this.anchorNanos) / 1000000L : 0L;
      return clamp(saturatedAdd(this.anchorMillis, elapsedMillis), totalMillis);
   }

   private static long clamp(long millis, long totalMillis) {
      long value = Math.max(0L, millis);
      return totalMillis > 0L ? Math.min(value, totalMillis) : value;
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }
}
