package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class TerrainDirtyTracker {
   private final int limit;
   private final Set<TerrainSectionKey> dirty;

   public TerrainDirtyTracker(int limit) {
      if (limit <= 0) {
         throw new IllegalArgumentException("dirty section limit must be positive");
      } else {
         this.limit = limit;
         this.dirty = new LinkedHashSet<>(Math.min(limit, 256));
      }
   }

   public synchronized void markSection(TerrainSectionKey section) {
      Objects.requireNonNull(section, "section");
      this.dirty.remove(section);
      this.dirty.add(section);
      this.trim();
   }

   public synchronized void markBlockAndBoundaryNeighbors(int blockX, int blockY, int blockZ) {
      TerrainSectionKey center = TerrainSectionKey.fromBlock(blockX, blockY, blockZ);
      this.markSection(center);
      int localX = Math.floorMod(blockX, 16);
      int localY = Math.floorMod(blockY, 16);
      int localZ = Math.floorMod(blockZ, 16);
      if (localX == 0) {
         this.markSection(new TerrainSectionKey(center.x() - 1, center.y(), center.z()));
      }

      if (localX == 15) {
         this.markSection(new TerrainSectionKey(center.x() + 1, center.y(), center.z()));
      }

      if (localY == 0) {
         this.markSection(new TerrainSectionKey(center.x(), center.y() - 1, center.z()));
      }

      if (localY == 15) {
         this.markSection(new TerrainSectionKey(center.x(), center.y() + 1, center.z()));
      }

      if (localZ == 0) {
         this.markSection(new TerrainSectionKey(center.x(), center.y(), center.z() - 1));
      }

      if (localZ == 15) {
         this.markSection(new TerrainSectionKey(center.x(), center.y(), center.z() + 1));
      }
   }

   public synchronized List<TerrainSectionKey> drain(int maxCount) {
      if (maxCount > 0 && !this.dirty.isEmpty()) {
         List<TerrainSectionKey> result = new ArrayList<>(Math.min(maxCount, this.dirty.size()));
         Iterator<TerrainSectionKey> iterator = this.dirty.iterator();

         while (iterator.hasNext() && result.size() < maxCount) {
            result.add(iterator.next());
            iterator.remove();
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   public synchronized int size() {
      return this.dirty.size();
   }

   public synchronized void clear() {
      this.dirty.clear();
   }

   private void trim() {
      while (this.dirty.size() > this.limit) {
         Iterator<TerrainSectionKey> iterator = this.dirty.iterator();
         iterator.next();
         iterator.remove();
      }
   }
}
