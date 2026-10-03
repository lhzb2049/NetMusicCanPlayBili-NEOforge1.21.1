package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import java.util.Objects;

public record TerrainOverviewCell(int worldX, int worldY, int worldZ, int size, TerrainCellSample.RenderCategory material, int color) {
   public TerrainOverviewCell(int worldX, int worldY, int worldZ, int size, TerrainCellSample.RenderCategory material) {
      this(worldX, worldY, worldZ, size, material, 0);
   }

   public TerrainOverviewCell(int worldX, int worldY, int worldZ, int size, TerrainCellSample.RenderCategory material, int color) {
      if (size <= 0) {
         throw new IllegalArgumentException("overview cell size must be positive");
      } else {
         Objects.requireNonNull(material, "material");
         if (color != 0 && (color & 0xFF000000) != -16777216) {
            throw new IllegalArgumentException("overview color must be zero or opaque ARGB");
         } else {
            this.worldX = worldX;
            this.worldY = worldY;
            this.worldZ = worldZ;
            this.size = size;
            this.material = material;
            this.color = color;
         }
      }
   }
}
