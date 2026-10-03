package com.zhongbai233.net_music_can_play_bili.client;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaConsumerRegistry<K> {
   private final Map<K, Set<K>> consumers = new ConcurrentHashMap<>();

   public void register(K source, K consumer) {
      if (source != null && consumer != null) {
         this.unregister(consumer);
         this.consumers.computeIfAbsent(source, ignored -> ConcurrentHashMap.newKeySet()).add(consumer);
      }
   }

   public void unregister(K consumer) {
      if (consumer != null) {
         this.consumers.entrySet().removeIf(entry -> {
            entry.getValue().remove(consumer);
            return entry.getValue().isEmpty();
         });
      }
   }

   public List<K> consumersFor(K source) {
      Set<K> values = this.consumers.get(source);
      return values == null ? List.of() : List.copyOf(values);
   }

   public void clear() {
      this.consumers.clear();
   }
}
