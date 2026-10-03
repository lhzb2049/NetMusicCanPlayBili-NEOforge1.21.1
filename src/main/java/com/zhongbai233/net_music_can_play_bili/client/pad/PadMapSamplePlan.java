package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.List;

final class PadMapSamplePlan {
   private static final int TILE_SIZE = 16;

   private PadMapSamplePlan() {
   }

   static List<PadMapSamplePlan.Cell> collectPendingCells(
      int centerX, int centerZ, int cellSize, int width, int height, int visibleWidth, int visibleHeight, PadMapTileKind[] tiles
   ) {
      int halfW = width / 2;
      int halfH = height / 2;
      int visibleHalfW = visibleWidth / 2;
      int visibleHalfH = visibleHeight / 2;
      int edgeBand = edgeBand(visibleWidth, visibleHeight);
      int prefetchBand = edgeBand;
      List<PadMapSamplePlan.Cell> pendingCells = new ArrayList<>();

      for (int z = 0; z < height; z++) {
         int localZ = z - halfH;
         boolean visibleZ = localZ >= -visibleHalfH && localZ < visibleHeight - visibleHalfH;
         int worldZ = centerZ + localZ * cellSize;

         for (int x = 0; x < width; x++) {
            int index = z * width + x;
            if (tiles[index] == PadMapTileKind.UNKNOWN) {
               int localX = x - halfW;
               boolean visible = visibleZ && localX >= -visibleHalfW && localX < visibleWidth - visibleHalfW;
               int worldX = centerX + localX * cellSize;
               int tileX = Math.floorDiv(localX, 16);
               int tileZ = Math.floorDiv(localZ, 16);
               int tileDistance = Math.abs(tileX) + Math.abs(tileZ);
               int intraTileIndex = Math.floorMod(localZ, 16) * 16 + Math.floorMod(localX, 16);
               int priority = priority(localX, localZ, visibleWidth, visibleHeight, visibleHalfW, visibleHalfH, visible, edgeBand, prefetchBand);
               pendingCells.add(new PadMapSamplePlan.Cell(index, worldX, worldZ, visible, priority, tileDistance, tileX, tileZ, intraTileIndex));
            }
         }
      }

      return orderByPriorityAndTile(pendingCells, width, height);
   }

   private static List<PadMapSamplePlan.Cell> orderByPriorityAndTile(List<PadMapSamplePlan.Cell> cells, int width, int height) {
      int maxTileDistance = Math.floorDiv(width / 2, 16) + Math.floorDiv(height / 2, 16) + 2;
      List<PadMapSamplePlan.Cell>[][] buckets = new List[4][maxTileDistance + 1];

      for (PadMapSamplePlan.Cell cell : cells) {
         int distance = Math.min(maxTileDistance, cell.tileDistance());
         List<PadMapSamplePlan.Cell> bucket = buckets[cell.priority()][distance];
         if (bucket == null) {
            bucket = new ArrayList<>();
            buckets[cell.priority()][distance] = bucket;
         }

         bucket.add(cell);
      }

      List<PadMapSamplePlan.Cell> ordered = new ArrayList<>(cells.size());

      for (List<PadMapSamplePlan.Cell>[] priorityBuckets : buckets) {
         for (List<PadMapSamplePlan.Cell> bucket : priorityBuckets) {
            if (bucket != null) {
               bucket.sort((a, b) -> {
                  int byZ = Integer.compare(a.tileZ(), b.tileZ());
                  if (byZ != 0) {
                     return byZ;
                  } else {
                     int byX = Integer.compare(a.tileX(), b.tileX());
                     return byX != 0 ? byX : Integer.compare(a.intraTileIndex(), b.intraTileIndex());
                  }
               });
               ordered.addAll(bucket);
            }
         }
      }

      return ordered;
   }

   private static int priority(
      int localX, int localZ, int visibleWidth, int visibleHeight, int visibleHalfW, int visibleHalfH, boolean visible, int edgeBand, int prefetchBand
   ) {
      int minVisibleX = -visibleHalfW;
      int maxVisibleX = visibleWidth - visibleHalfW - 1;
      int minVisibleZ = -visibleHalfH;
      int maxVisibleZ = visibleHeight - visibleHalfH - 1;
      int centerBandX = Math.max(16, visibleWidth / 6);
      int centerBandZ = Math.max(16, visibleHeight / 6);
      if (Math.abs(localX) <= centerBandX && Math.abs(localZ) <= centerBandZ) {
         return 0;
      } else if (visible) {
         return 1;
      } else {
         return distanceOutsideRect(localX, localZ, minVisibleX, maxVisibleX, minVisibleZ, maxVisibleZ) <= prefetchBand ? 2 : 3;
      }
   }

   private static int edgeBand(int visibleWidth, int visibleHeight) {
      int shortSide = Math.max(1, Math.min(visibleWidth, visibleHeight));
      return Math.max(16, Math.min(64, shortSide / 4));
   }

   private static int distanceOutsideRect(int x, int z, int minX, int maxX, int minZ, int maxZ) {
      int dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0);
      int dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0);
      return Math.max(dx, dz);
   }

   record Cell(int index, int worldX, int worldZ, boolean visible, int priority, int tileDistance, int tileX, int tileZ, int intraTileIndex) {
   }
}
