package com.zhongbai233.net_music_can_play_bili.media.sync;

public final class AudioStartupSync {
   private AudioStartupSync() {
   }

   public static long elapsedSinceCaptureMillis(long capturedNanos, long nowNanos) {
      return capturedNanos > 0L && nowNanos > capturedNanos ? (nowNanos - capturedNanos) / 1000000L : 0L;
   }

   public static long compensatedElapsedMillis(long capturedElapsedMillis, long totalMillis, long capturedNanos, long nowNanos) {
      return compensatedOffsetMillis(capturedElapsedMillis, totalMillis, elapsedSinceCaptureMillis(capturedNanos, nowNanos));
   }

   public static long compensatedOffsetMillis(long capturedElapsedMillis, long totalMillis, long additionalSkippedMillis) {
      long elapsed = saturatedAdd(Math.max(0L, capturedElapsedMillis), Math.max(0L, additionalSkippedMillis));
      return totalMillis > 0L ? Math.min(elapsed, totalMillis) : elapsed;
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }
}
