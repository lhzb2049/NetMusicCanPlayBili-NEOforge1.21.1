package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class ControlConsoleExitFade {
   public static final long DURATION_NANOS = 250000000L;

   private ControlConsoleExitFade() {
   }

   public static float gain(long startedNanos, long nowNanos) {
      if (nowNanos <= startedNanos) {
         return 1.0F;
      } else {
         long elapsed = nowNanos - startedNanos;
         if (elapsed < 250000000L && elapsed >= 0L) {
            double t = elapsed / 2.5E8;
            double smooth = t * t * (3.0 - 2.0 * t);
            return (float)(1.0 - smooth);
         } else {
            return 0.0F;
         }
      }
   }

   public static boolean finished(long startedNanos, long nowNanos) {
      return gain(startedNanos, nowNanos) <= 0.0F;
   }
}
