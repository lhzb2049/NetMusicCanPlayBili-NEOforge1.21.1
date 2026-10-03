package com.zhongbai233.net_music_can_play_bili.blockentity;

public final class TurntableComparatorSignal {
   public static final int MAX_OUTPUT = 15;

   private TurntableComparatorSignal() {
   }

   public static int fromProgress(boolean hasDisc, long elapsedMillis, long durationMillis) {
      if (hasDisc && durationMillis > 0L) {
         long clampedElapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
         return 1 + (int)Math.min(14L, clampedElapsed * 14L / durationMillis);
      } else {
         return 0;
      }
   }
}
