package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class ControlConsoleExitPolicy {
   public static final double DISCONTINUITY_DISTANCE = 2.0;

   private ControlConsoleExitPolicy() {
   }

   public static boolean shouldFade(boolean wasActive, boolean sourceAvailable, boolean rangeChanged, boolean positionDiscontinuous, float previousRangeGain) {
      return !wasActive ? false : !sourceAvailable || rangeChanged || positionDiscontinuous || previousRangeGain >= 0.999F;
   }

   public static boolean positionDiscontinuous(double previousX, double previousY, double previousZ, double currentX, double currentY, double currentZ) {
      if (Double.isFinite(previousX)
         && Double.isFinite(previousY)
         && Double.isFinite(previousZ)
         && Double.isFinite(currentX)
         && Double.isFinite(currentY)
         && Double.isFinite(currentZ)) {
         double dx = currentX - previousX;
         double dy = currentY - previousY;
         double dz = currentZ - previousZ;
         return dx * dx + dy * dy + dz * dz > 4.0;
      } else {
         return false;
      }
   }
}
