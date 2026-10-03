package com.zhongbai233.net_music_can_play_bili.media.sync;

public final class MonotonicMediaClock {
   private static final long NANOS_PER_MILLI = 1000000L;

   private MonotonicMediaClock() {
   }

   public static long nowNanos() {
      return System.nanoTime();
   }

   public static long nowTick() {
      return Math.floorDiv(nowNanos(), 50000000L);
   }

   public static MonotonicMediaClock.Anchor paused(long elapsedMillis) {
      return new MonotonicMediaClock.Anchor(Math.max(0L, elapsedMillis), 0L, false);
   }

   public static MonotonicMediaClock.Anchor running(long elapsedMillis, long nowNanos) {
      return new MonotonicMediaClock.Anchor(Math.max(0L, elapsedMillis), nowNanos, true);
   }

   public static long remainingSeconds(MonotonicMediaClock.Anchor anchor, long durationMillis, long nowNanos) {
      if (durationMillis <= 0L) {
         return 0L;
      } else {
         long remainingMillis = Math.max(0L, durationMillis - anchor.elapsedMillis(nowNanos, durationMillis));
         return (remainingMillis + 999L) / 1000L;
      }
   }

   private static long clamp(long elapsedMillis, long durationMillis) {
      long nonNegative = Math.max(0L, elapsedMillis);
      return durationMillis > 0L ? Math.min(nonNegative, durationMillis) : nonNegative;
   }

   private static long saturatedAdd(long left, long right) {
      return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   public record Anchor(long baseElapsedMillis, long anchorNanos, boolean running) {
      public Anchor(long baseElapsedMillis, long anchorNanos, boolean running) {
         baseElapsedMillis = Math.max(0L, baseElapsedMillis);
         this.baseElapsedMillis = baseElapsedMillis;
         this.anchorNanos = anchorNanos;
         this.running = running;
      }

      public long elapsedMillis(long nowNanos, long durationMillis) {
         long elapsed = this.baseElapsedMillis;
         if (this.running) {
            long deltaNanos = Math.max(0L, nowNanos - this.anchorNanos);
            elapsed = MonotonicMediaClock.saturatedAdd(elapsed, deltaNanos / 1000000L);
         }

         return MonotonicMediaClock.clamp(elapsed, durationMillis);
      }

      public MonotonicMediaClock.Anchor pause(long nowNanos, long durationMillis) {
         return MonotonicMediaClock.paused(this.elapsedMillis(nowNanos, durationMillis));
      }

      public MonotonicMediaClock.Anchor resume(long nowNanos, long durationMillis) {
         return MonotonicMediaClock.running(this.elapsedMillis(nowNanos, durationMillis), nowNanos);
      }

      public MonotonicMediaClock.Anchor seek(long elapsedMillis, long nowNanos, boolean shouldRun) {
         return shouldRun ? MonotonicMediaClock.running(elapsedMillis, nowNanos) : MonotonicMediaClock.paused(elapsedMillis);
      }

      public MonotonicMediaClock.Anchor reanchor(long nowNanos, long durationMillis) {
         long elapsed = this.elapsedMillis(nowNanos, durationMillis);
         return this.running ? MonotonicMediaClock.running(elapsed, nowNanos) : MonotonicMediaClock.paused(elapsed);
      }
   }
}
