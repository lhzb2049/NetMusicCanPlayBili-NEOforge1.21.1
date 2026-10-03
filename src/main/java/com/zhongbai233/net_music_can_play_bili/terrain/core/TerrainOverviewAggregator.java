package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TerrainOverviewAggregator {
   private TerrainOverviewAggregator() {
   }

   public static List<TerrainOverviewAggregator.Cell> aggregate(List<TerrainOverviewAggregator.Cell> visible, int size) {
      Objects.requireNonNull(visible, "visible");
      if (size > 0 && 16 % size == 0) {
         int dimension = 16 / size;
         Map<Integer, TerrainOverviewAggregator.Cell> cells = new LinkedHashMap<>();

         for (TerrainOverviewAggregator.Cell cell : visible) {
            int gx = cell.localX() / size;
            int gy = cell.localY() / size;
            int gz = cell.localZ() / size;
            int index = (gy * dimension + gz) * dimension + gx;
            cells.putIfAbsent(index, new TerrainOverviewAggregator.Cell(gx * size, gy * size, gz * size, cell.material()));
         }

         return List.copyOf(new ArrayList<>(cells.values()));
      } else {
         throw new IllegalArgumentException("overview size must divide section size");
      }
   }

   public record Cell(int localX, int localY, int localZ, TerrainCellSample.RenderCategory material) {
      public Cell(int localX, int localY, int localZ, TerrainCellSample.RenderCategory material) {
         if (localX >= 0 && localX < 16 && localY >= 0 && localY < 16 && localZ >= 0 && localZ < 16) {
            Objects.requireNonNull(material, "material");
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.material = material;
         } else {
            throw new IllegalArgumentException("terrain overview local coordinates must be within [0, 15]");
         }
      }
   }
}
