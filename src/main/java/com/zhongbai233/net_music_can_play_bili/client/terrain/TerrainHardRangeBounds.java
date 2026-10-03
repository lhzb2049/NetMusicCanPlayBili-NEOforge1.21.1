package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;

public final class TerrainHardRangeBounds {
   private TerrainHardRangeBounds() {
   }

   public static TerrainBounds around(int consoleX, int consoleY, int consoleZ, double halfX, double halfY, double halfZ, int levelMinY, int levelMaxY) {
      validateRange(halfX);
      validateRange(halfY);
      validateRange(halfZ);
      if (levelMaxY <= levelMinY) {
         throw new IllegalArgumentException("invalid world height");
      } else {
         double centerX = consoleX + 0.5;
         double centerY = consoleY + 0.5;
         double centerZ = consoleZ + 0.5;
         int minX = floorClamped(centerX - halfX);
         int maxX = floorClamped(centerX + halfX);
         int minY = Math.max(levelMinY, floorClamped(centerY - halfY));
         int maxY = Math.min(levelMaxY - 1, floorClamped(centerY + halfY));
         int minZ = floorClamped(centerZ - halfZ);
         int maxZ = floorClamped(centerZ + halfZ);
         if (maxY < minY) {
            int clamped = Math.clamp((long)consoleY, levelMinY, levelMaxY - 1);
            minY = clamped;
            maxY = clamped;
         }

         return new TerrainBounds(minX, minY, minZ, maxX, maxY, maxZ);
      }
   }

   private static void validateRange(double range) {
      if (!Double.isFinite(range) || range < 0.0) {
         throw new IllegalArgumentException("hardRange must be finite and non-negative");
      }
   }

   private static int floorClamped(double value) {
      if (value <= -2.1474836E9F) {
         return Integer.MIN_VALUE;
      } else {
         return value >= 2.147483647E9 ? Integer.MAX_VALUE : (int)Math.floor(value);
      }
   }
}
