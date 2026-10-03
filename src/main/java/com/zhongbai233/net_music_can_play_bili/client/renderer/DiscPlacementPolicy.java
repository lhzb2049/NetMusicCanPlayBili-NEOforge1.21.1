package com.zhongbai233.net_music_can_play_bili.client.renderer;

final class DiscPlacementPolicy {
   static final double MODEL_SCALE = 0.75;
   static final double BASE_CENTER_X = 0.60905625;
   static final double BASE_CENTER_Z = 0.48405625;

   private DiscPlacementPolicy() {
   }

   static DiscPlacementPolicy.Placement forClockwiseQuarterTurns(int quarterTurns) {
      double offsetX = 0.10905624999999997;
      double offsetZ = -0.01594374999999998;

      return switch (Math.floorMod(quarterTurns, 4)) {
         case 0 -> placement(offsetX, offsetZ);
         case 1 -> placement(-offsetZ, offsetX);
         case 2 -> placement(-offsetX, -offsetZ);
         default -> placement(offsetZ, -offsetX);
      };
   }

   private static DiscPlacementPolicy.Placement placement(double offsetX, double offsetZ) {
      return new DiscPlacementPolicy.Placement((0.5 + offsetX) / 0.75, (0.5 + offsetZ) / 0.75);
   }

   record Placement(double anchorX, double anchorZ) {
   }
}
