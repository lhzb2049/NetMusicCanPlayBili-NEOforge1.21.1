package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

final class PadMapDirtyChunkTracker {
   private final int limit;
   private final LinkedHashMap<PadMapDirtyChunkTracker.Key, Boolean> dirtyChunks = new LinkedHashMap<>(256, 0.75F, true);

   PadMapDirtyChunkTracker(int limit) {
      this.limit = Math.max(1, limit);
   }

   void mark(String dimension, int chunkX, int chunkZ) {
      if (dimension != null && !dimension.isBlank()) {
         synchronized (this.dirtyChunks) {
            this.dirtyChunks.put(new PadMapDirtyChunkTracker.Key(dimension, chunkX, chunkZ), Boolean.TRUE);
            this.trimToLimit();
         }
      }
   }

   List<PadMapDirtyChunkTracker.Key> drainForDimension(String dimension, int maxCount) {
      if (dimension != null && !dimension.isBlank() && maxCount > 0) {
         List<PadMapDirtyChunkTracker.Key> drained = new ArrayList<>(Math.max(1, maxCount));
         synchronized (this.dirtyChunks) {
            Iterator<PadMapDirtyChunkTracker.Key> iterator = this.dirtyChunks.keySet().iterator();

            while (iterator.hasNext()) {
               PadMapDirtyChunkTracker.Key key = iterator.next();
               if (key.dimension().equals(dimension)) {
                  iterator.remove();
                  drained.add(key);
                  if (drained.size() >= maxCount) {
                     break;
                  }
               }
            }

            return drained;
         }
      } else {
         return List.of();
      }
   }

   int size() {
      synchronized (this.dirtyChunks) {
         return this.dirtyChunks.size();
      }
   }

   void clear() {
      synchronized (this.dirtyChunks) {
         this.dirtyChunks.clear();
      }
   }

   private void trimToLimit() {
      while (this.dirtyChunks.size() > this.limit) {
         Iterator<Entry<PadMapDirtyChunkTracker.Key, Boolean>> iterator = this.dirtyChunks.entrySet().iterator();
         if (!iterator.hasNext()) {
            return;
         }

         iterator.next();
         iterator.remove();
      }
   }

   record Key(String dimension, int chunkX, int chunkZ) {
   }
}
