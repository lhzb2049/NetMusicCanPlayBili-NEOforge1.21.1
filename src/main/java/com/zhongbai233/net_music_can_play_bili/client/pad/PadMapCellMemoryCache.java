package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class PadMapCellMemoryCache {
   private final int limit;
   private final LinkedHashMap<PadMapCellMemoryCache.Key, PadMapTileKind> values = new LinkedHashMap<>(1024, 0.75F, true);

   PadMapCellMemoryCache(int limit) {
      this.limit = Math.max(1, limit);
   }

   synchronized PadMapTileKind get(PadMapCellMemoryCache.Key key) {
      return this.values.get(key);
   }

   synchronized void put(PadMapCellMemoryCache.Key key, PadMapTileKind kind) {
      this.values.put(key, kind);

      while (this.values.size() > this.limit) {
         this.values.remove(this.values.keySet().iterator().next());
      }
   }

   synchronized PadMapTileKind remove(PadMapCellMemoryCache.Key key) {
      return this.values.remove(key);
   }

   synchronized int size() {
      return this.values.size();
   }

   synchronized void clear() {
      this.values.clear();
   }

   synchronized List<PadMapCellMemoryCache.Entry> entries() {
      List<PadMapCellMemoryCache.Entry> entries = new ArrayList<>(this.values.size());
      this.values.forEach((key, kind) -> entries.add(new PadMapCellMemoryCache.Entry(key, kind)));
      return entries;
   }

   record Entry(PadMapCellMemoryCache.Key key, PadMapTileKind kind) {
   }

   record Key(String dimension, int cellSize, int cellX, int cellZ) {
   }
}
