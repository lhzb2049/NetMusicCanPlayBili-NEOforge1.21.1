package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

final class PadMapViewSnapshotCache {
   private static final int DEFAULT_MAX_ENTRIES = 16;
   private final int maxEntries;
   private final LinkedHashMap<PadMapViewSnapshotCache.Key, PadMapSnapshot> snapshots = new LinkedHashMap<>(16, 0.75F, true);

   PadMapViewSnapshotCache() {
      this(16);
   }

   PadMapViewSnapshotCache(int maxEntries) {
      this.maxEntries = Math.max(1, maxEntries);
   }

   PadMapSnapshot get(PadMapViewProfile profile, int floorY, int cellSize) {
      return this.snapshots.get(new PadMapViewSnapshotCache.Key(profile, floorY, cellSize));
   }

   void put(PadMapViewProfile profile, PadMapSnapshot snapshot) {
      if (snapshot != null) {
         this.snapshots.put(new PadMapViewSnapshotCache.Key(profile, snapshot.centerY(), snapshot.cellSizeBlocks()), snapshot);

         while (this.snapshots.size() > this.maxEntries) {
            Iterator<Entry<PadMapViewSnapshotCache.Key, PadMapSnapshot>> iterator = this.snapshots.entrySet().iterator();
            iterator.next();
            iterator.remove();
         }
      }
   }

   void invalidate(List<PadMapDirtyChunkTracker.Key> dirtyChunks) {
      for (Entry<PadMapViewSnapshotCache.Key, PadMapSnapshot> entry : this.snapshots.entrySet()) {
         PadMapSnapshot invalidated = PadMapDirtyInvalidation.invalidateSnapshot(entry.getValue(), entry.getKey().cellSize(), dirtyChunks);
         if (invalidated != null) {
            entry.setValue(invalidated);
         }
      }
   }

   void clear() {
      this.snapshots.clear();
   }

   private record Key(PadMapViewProfile profile, int floorY, int cellSize) {
   }
}
