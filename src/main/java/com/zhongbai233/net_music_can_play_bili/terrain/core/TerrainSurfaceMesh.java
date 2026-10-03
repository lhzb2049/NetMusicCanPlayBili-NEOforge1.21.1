package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.List;
import java.util.Objects;

public record TerrainSurfaceMesh(TerrainSectionKey section, TerrainLodLevel lod, List<TerrainSurfaceFace> faces, boolean truncated, long estimatedBytes) {
   public TerrainSurfaceMesh(TerrainSectionKey section, TerrainLodLevel lod, List<TerrainSurfaceFace> faces, boolean truncated, long estimatedBytes) {
      Objects.requireNonNull(section, "section");
      Objects.requireNonNull(lod, "lod");
      faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
      if (estimatedBytes < 0L) {
         throw new IllegalArgumentException("estimatedBytes must be non-negative");
      } else {
         this.section = section;
         this.lod = lod;
         this.faces = faces;
         this.truncated = truncated;
         this.estimatedBytes = estimatedBytes;
      }
   }
}
