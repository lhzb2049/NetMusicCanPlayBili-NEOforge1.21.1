package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Objects;

public record TerrainSurfaceFace(int localX, int localY, int localZ, TerrainSurfaceFace.Direction direction, TerrainCellSample.RenderCategory material) {
   public TerrainSurfaceFace(int localX, int localY, int localZ, TerrainSurfaceFace.Direction direction, TerrainCellSample.RenderCategory material) {
      Objects.requireNonNull(direction, "direction");
      Objects.requireNonNull(material, "material");
      this.localX = localX;
      this.localY = localY;
      this.localZ = localZ;
      this.direction = direction;
      this.material = material;
   }

   public static enum Direction {
      DOWN(0, -1, 0),
      UP(0, 1, 0),
      NORTH(0, 0, -1),
      SOUTH(0, 0, 1),
      WEST(-1, 0, 0),
      EAST(1, 0, 0);

      private final int dx;
      private final int dy;
      private final int dz;

      private Direction(int dx, int dy, int dz) {
         this.dx = dx;
         this.dy = dy;
         this.dz = dz;
      }

      public int dx() {
         return this.dx;
      }

      public int dy() {
         return this.dy;
      }

      public int dz() {
         return this.dz;
      }
   }
}
