package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.List;
import java.util.Objects;

public record TerrainSectionSnapshot(TerrainSectionKey key, long generation, List<TerrainCellSample> cells, long estimatedBytes) {
   public TerrainSectionSnapshot(TerrainSectionKey key, long generation, List<TerrainCellSample> cells, long estimatedBytes) {
      Objects.requireNonNull(key, "key");
      cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
      if (cells.size() != 4096) {
         throw new IllegalArgumentException("terrain section snapshot must contain exactly 4096 cells");
      } else if (generation >= 0L && estimatedBytes >= 0L) {
         this.key = key;
         this.generation = generation;
         this.cells = cells;
         this.estimatedBytes = estimatedBytes;
      } else {
         throw new IllegalArgumentException("snapshot generation and estimatedBytes must be non-negative");
      }
   }

   public TerrainCellSample cell(int localX, int localY, int localZ) {
      if (localX >= 0 && localX < 16 && localY >= 0 && localY < 16 && localZ >= 0 && localZ < 16) {
         return this.cells.get(index(localX, localY, localZ));
      } else {
         throw new IndexOutOfBoundsException("terrain local coordinates must be within [0, 15]");
      }
   }

   static int index(int localX, int localY, int localZ) {
      return (localY * 16 + localZ) * 16 + localX;
   }
}
