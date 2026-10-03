package com.zhongbai233.net_music_can_play_bili.terrain.core;

public final class TerrainNeighborhoodIndex {
   public static final int BORDER = 2;
   public static final int MIN_LOCAL = -2;
   public static final int MAX_LOCAL = 17;
   public static final int SIZE = 20;
   public static final int CELL_COUNT = 8000;

   private TerrainNeighborhoodIndex() {
   }

   public static boolean contains(int localX, int localY, int localZ) {
      return localX >= -2 && localX <= 17 && localY >= -2 && localY <= 17 && localZ >= -2 && localZ <= 17;
   }

   public static int index(int localX, int localY, int localZ) {
      if (!contains(localX, localY, localZ)) {
         throw new IndexOutOfBoundsException("neighborhood coordinates must be within [-2, 17]");
      } else {
         int x = localX - -2;
         int y = localY - -2;
         int z = localZ - -2;
         return (y * 20 + z) * 20 + x;
      }
   }

   public static int neighborChunkIndex(int localX, int localZ) {
      if (localX >= -2 && localX <= 17 && localZ >= -2 && localZ <= 17) {
         int offsetX = Math.floorDiv(localX, 16);
         int offsetZ = Math.floorDiv(localZ, 16);
         return (offsetZ + 1) * 3 + offsetX + 1;
      } else {
         throw new IndexOutOfBoundsException("horizontal neighborhood coordinates must be within [-2, 17]");
      }
   }
}
