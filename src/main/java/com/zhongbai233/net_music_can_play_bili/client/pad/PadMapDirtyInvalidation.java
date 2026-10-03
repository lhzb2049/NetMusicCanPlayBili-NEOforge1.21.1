package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PadMapDirtyInvalidation {
   private static final int CHUNK_SIZE = 16;

   private PadMapDirtyInvalidation() {
   }

   static boolean touchesSnapshot(PadMapSnapshot snapshot, int cellSize, List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
      if (snapshot != null && dirtyChunks != null && !dirtyChunks.isEmpty()) {
         PadMapDirtyInvalidation.Bounds bounds = snapshotWorldBounds(snapshot, cellSize);

         for (PadMapDirtyChunkTracker.Key dirtyChunk : dirtyChunks) {
            PadMapDirtyInvalidation.Bounds chunk = chunkWorldBounds(dirtyChunk);
            if (chunk.maxX() >= bounds.minX() && chunk.minX() <= bounds.maxX() && chunk.maxZ() >= bounds.minZ() && chunk.minZ() <= bounds.maxZ()) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   static List<PadMapDirtyInvalidation.CellRange> cellRanges(List<PadMapDirtyChunkTracker.Key> dirtyChunks, int cellSize) {
      if (dirtyChunks != null && !dirtyChunks.isEmpty() && cellSize > 0) {
         List<PadMapDirtyInvalidation.CellRange> ranges = new ArrayList<>(dirtyChunks.size());

         for (PadMapDirtyChunkTracker.Key dirtyChunk : dirtyChunks) {
            int minCellX = Math.floorDiv(dirtyChunk.chunkX() * 16, cellSize);
            int maxCellX = Math.floorDiv(dirtyChunk.chunkX() * 16 + 16 - 1, cellSize);
            int minCellZ = Math.floorDiv(dirtyChunk.chunkZ() * 16, cellSize);
            int maxCellZ = Math.floorDiv(dirtyChunk.chunkZ() * 16 + 16 - 1, cellSize);
            ranges.add(new PadMapDirtyInvalidation.CellRange(minCellX, maxCellX, minCellZ, maxCellZ));
         }

         return ranges;
      } else {
         return List.of();
      }
   }

   static PadMapSnapshot invalidateSnapshot(PadMapSnapshot snapshot, int cellSize, List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
      if (snapshot != null && snapshot.tiles() != null && cellSize > 0) {
         List<PadMapDirtyInvalidation.CellRange> ranges = cellRanges(dirtyChunks, cellSize);
         if (ranges.isEmpty()) {
            return null;
         } else {
            PadMapTileKind[] tiles = Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
            boolean changed = false;
            int originCellX = Math.floorDiv(snapshot.centerX(), cellSize) - snapshot.width() / 2;
            int originCellZ = Math.floorDiv(snapshot.centerZ(), cellSize) - snapshot.height() / 2;

            for (PadMapDirtyInvalidation.CellRange range : ranges) {
               int minX = Math.max(0, range.minCellX() - originCellX);
               int maxX = Math.min(snapshot.width() - 1, range.maxCellX() - originCellX);
               int minZ = Math.max(0, range.minCellZ() - originCellZ);
               int maxZ = Math.min(snapshot.height() - 1, range.maxCellZ() - originCellZ);

               for (int z = minZ; z <= maxZ; z++) {
                  int row = z * snapshot.width();

                  for (int x = minX; x <= maxX; x++) {
                     int index = row + x;
                     if (index < tiles.length && tiles[index] != PadMapTileKind.UNKNOWN) {
                        tiles[index] = PadMapTileKind.UNKNOWN;
                        changed = true;
                     }
                  }
               }
            }

            return !changed
               ? null
               : new PadMapSnapshot(
                  snapshot.centerX(),
                  snapshot.centerY(),
                  snapshot.centerZ(),
                  snapshot.cellSizeBlocks(),
                  snapshot.width(),
                  snapshot.height(),
                  tiles,
                  snapshot.displayScale()
               );
         }
      } else {
         return null;
      }
   }

   static int affectedCellCount(PadMapSnapshot snapshot, int cellSize, List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
      if (snapshot != null && cellSize > 0) {
         int originCellX = Math.floorDiv(snapshot.centerX(), cellSize) - snapshot.width() / 2;
         int originCellZ = Math.floorDiv(snapshot.centerZ(), cellSize) - snapshot.height() / 2;
         int count = 0;

         for (PadMapDirtyInvalidation.CellRange range : cellRanges(dirtyChunks, cellSize)) {
            int width = Math.max(0, Math.min(snapshot.width() - 1, range.maxCellX() - originCellX) - Math.max(0, range.minCellX() - originCellX) + 1);
            int height = Math.max(0, Math.min(snapshot.height() - 1, range.maxCellZ() - originCellZ) - Math.max(0, range.minCellZ() - originCellZ) + 1);
            count += width * height;
         }

         return count;
      } else {
         return 0;
      }
   }

   private static PadMapDirtyInvalidation.Bounds snapshotWorldBounds(PadMapSnapshot snapshot, int cellSize) {
      int halfW = snapshot.width() / 2;
      int halfH = snapshot.height() / 2;
      int minWorldX = snapshot.centerX() - halfW * cellSize;
      int maxWorldX = snapshot.centerX() + (snapshot.width() - halfW - 1) * cellSize;
      int minWorldZ = snapshot.centerZ() - halfH * cellSize;
      int maxWorldZ = snapshot.centerZ() + (snapshot.height() - halfH - 1) * cellSize;
      return new PadMapDirtyInvalidation.Bounds(minWorldX, maxWorldX, minWorldZ, maxWorldZ);
   }

   private static PadMapDirtyInvalidation.Bounds chunkWorldBounds(PadMapDirtyChunkTracker.Key dirtyChunk) {
      int minX = dirtyChunk.chunkX() * 16;
      int minZ = dirtyChunk.chunkZ() * 16;
      return new PadMapDirtyInvalidation.Bounds(minX, minX + 16 - 1, minZ, minZ + 16 - 1);
   }

   private record Bounds(int minX, int maxX, int minZ, int maxZ) {
   }

   record CellRange(int minCellX, int maxCellX, int minCellZ, int maxCellZ) {
      boolean contains(int cellX, int cellZ) {
         return cellX >= this.minCellX && cellX <= this.maxCellX && cellZ >= this.minCellZ && cellZ <= this.maxCellZ;
      }
   }
}
