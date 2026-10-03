package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TerrainSurfaceMesher {
   private static final TerrainSurfaceFace.Direction[] DIRECTIONS = TerrainSurfaceFace.Direction.values();
   private final int maxFaces;

   public TerrainSurfaceMesher(int maxFaces) {
      if (maxFaces <= 0) {
         throw new IllegalArgumentException("maxFaces must be positive");
      } else {
         this.maxFaces = maxFaces;
      }
   }

   public TerrainSurfaceMesh mesh(TerrainSectionSnapshot snapshot, TerrainLodLevel lod) {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(lod, "lod");
      if (lod == TerrainLodLevel.UNKNOWN) {
         return new TerrainSurfaceMesh(snapshot.key(), lod, List.of(), false, 0L);
      } else {
         List<TerrainSurfaceFace> faces = new ArrayList<>();
         boolean truncated = false;

         for (int y = 0; y < 16 && !truncated; y++) {
            for (int z = 0; z < 16 && !truncated; z++) {
               for (int x = 0; x < 16 && !truncated; x++) {
                  TerrainCellSample cell = snapshot.cell(x, y, z);
                  if (solid(cell)) {
                     for (TerrainSurfaceFace.Direction direction : DIRECTIONS) {
                        if (faces.size() >= this.maxFaces) {
                           truncated = true;
                           break;
                        }

                        if (!solid(neighbor(snapshot, x, y, z, direction))) {
                           faces.add(new TerrainSurfaceFace(x, y, z, direction, cell.renderCategory()));
                        }
                     }
                  }
               }
            }
         }

         return new TerrainSurfaceMesh(snapshot.key(), lod, faces, truncated, faces.size() * 32L);
      }
   }

   private static TerrainCellSample neighbor(TerrainSectionSnapshot snapshot, int x, int y, int z, TerrainSurfaceFace.Direction direction) {
      int nx = x + direction.dx();
      int ny = y + direction.dy();
      int nz = z + direction.dz();
      return nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16 ? snapshot.cell(nx, ny, nz) : TerrainCellSample.unknown();
   }

   private static boolean solid(TerrainCellSample cell) {
      return cell.availability() == TerrainCellSample.Availability.LOADED
         && cell.renderCategory() != TerrainCellSample.RenderCategory.AIR
         && cell.renderCategory() != TerrainCellSample.RenderCategory.INVISIBLE;
   }
}
