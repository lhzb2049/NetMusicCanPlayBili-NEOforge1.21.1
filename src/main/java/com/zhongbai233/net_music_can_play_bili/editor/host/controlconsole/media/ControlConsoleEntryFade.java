package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class ControlConsoleEntryFade {
   public static final long DURATION_NANOS = 250000000L;

   private ControlConsoleEntryFade() {
   }

   public static float gain(long startedNanos, long nowNanos) {
      if (startedNanos <= 0L) {
         return 1.0F;
      } else {
         long elapsed = nowNanos - startedNanos;
         if (elapsed <= 0L) {
            return 0.0F;
         } else if (elapsed >= 250000000L) {
            return 1.0F;
         } else {
            float t = (float)elapsed / 2.5E8F;
            return t * t * (3.0F - 2.0F * t);
         }
      }
   }
}
