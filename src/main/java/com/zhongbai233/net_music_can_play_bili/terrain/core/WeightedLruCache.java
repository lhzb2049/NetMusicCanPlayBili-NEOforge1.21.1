package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;

public final class WeightedLruCache<K, V> {
   private final long maxWeight;
   private final ToLongFunction<? super V> weightFunction;
   private final LinkedHashMap<K, WeightedLruCache.Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
   private long totalWeight;

   public WeightedLruCache(long maxWeight, ToLongFunction<? super V> weightFunction) {
      if (maxWeight <= 0L) {
         throw new IllegalArgumentException("cache maxWeight must be positive");
      } else {
         this.maxWeight = maxWeight;
         this.weightFunction = Objects.requireNonNull(weightFunction, "weightFunction");
      }
   }

   public synchronized Optional<V> get(K key) {
      WeightedLruCache.Entry<V> entry = this.entries.get(key);
      return entry == null ? Optional.empty() : Optional.of(entry.value());
   }

   public synchronized boolean put(K key, V value) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      long weight = this.weightFunction.applyAsLong(value);
      if (weight < 0L) {
         throw new IllegalArgumentException("cache entry weight must be non-negative");
      } else if (weight > this.maxWeight) {
         return false;
      } else {
         WeightedLruCache.Entry<V> old = this.entries.remove(key);
         if (old != null) {
            this.totalWeight = this.totalWeight - old.weight();
         }

         this.entries.put(key, new WeightedLruCache.Entry<>(value, weight));
         this.totalWeight += weight;
         this.evictToBudget();
         return true;
      }
   }

   public synchronized Optional<V> remove(K key) {
      WeightedLruCache.Entry<V> removed = this.entries.remove(key);
      if (removed == null) {
         return Optional.empty();
      } else {
         this.totalWeight = this.totalWeight - removed.weight();
         return Optional.of(removed.value());
      }
   }

   public synchronized Map<K, V> snapshot() {
      LinkedHashMap<K, V> copy = new LinkedHashMap<>();
      this.entries.forEach((key, entry) -> copy.put((K)key, entry.value()));
      return Map.copyOf(copy);
   }

   public synchronized long totalWeight() {
      return this.totalWeight;
   }

   public synchronized int size() {
      return this.entries.size();
   }

   public synchronized void clear() {
      this.entries.clear();
      this.totalWeight = 0L;
   }

   private void evictToBudget() {
      Iterator<Map.Entry<K, WeightedLruCache.Entry<V>>> iterator = this.entries.entrySet().iterator();

      while (this.totalWeight > this.maxWeight && iterator.hasNext()) {
         this.totalWeight = this.totalWeight - iterator.next().getValue().weight();
         iterator.remove();
      }
   }

   private record Entry<V>(V value, long weight) {
   }
}
