package com.zhongbai233.net_music_can_play_bili.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class PlaybackSourceRegistry<T> {
   private final ConcurrentHashMap<String, T> sources = new ConcurrentHashMap<>();

   void put(String key, T source) {
      this.sources.put(key, source);
   }

   T get(String key) {
      return this.sources.get(key);
   }

   List<T> snapshot() {
      return new ArrayList<>(this.sources.values());
   }

   void removeIf(Predicate<T> predicate) {
      this.sources.values().removeIf(predicate);
   }

   Set<String> keys() {
      return Set.copyOf(this.sources.keySet());
   }
}
