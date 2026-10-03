package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.function.Predicate;

final class BoundedConcurrentStore<K, V> {
   private final int capacity;
   private final Map<K, BoundedConcurrentStore.Slot<V>> entries = new HashMap<>();
   private long sequence;

   BoundedConcurrentStore(int capacity) {
      if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
      } else {
         this.capacity = capacity;
      }
   }

   synchronized BoundedConcurrentStore.PutResult<V> put(K key, V value, long createdAtMillis) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      BoundedConcurrentStore.Slot<V> replaced = this.entries.put(key, new BoundedConcurrentStore.Slot<>(value, createdAtMillis, this.sequence++));
      List<V> evicted = new ArrayList<>();

      while (this.entries.size() > this.capacity) {
         K oldestKey = null;
         BoundedConcurrentStore.Slot<V> oldest = null;

         for (Entry<K, BoundedConcurrentStore.Slot<V>> candidate : this.entries.entrySet()) {
            if (oldest == null || candidate.getValue().olderThan(oldest)) {
               oldestKey = candidate.getKey();
               oldest = candidate.getValue();
            }
         }

         if (oldestKey == null || oldest == null) {
            break;
         }

         this.entries.remove(oldestKey);
         evicted.add(oldest.value());
      }

      return new BoundedConcurrentStore.PutResult<>(replaced != null ? replaced.value() : null, List.copyOf(evicted));
   }

   synchronized V get(K key) {
      BoundedConcurrentStore.Slot<V> slot = this.entries.get(key);
      return slot != null ? slot.value() : null;
   }

   synchronized boolean remove(K key, V expectedValue) {
      BoundedConcurrentStore.Slot<V> current = this.entries.get(key);
      if (current != null && current.value() == expectedValue) {
         this.entries.remove(key);
         return true;
      } else {
         return false;
      }
   }

   synchronized List<V> removeIf(Predicate<? super V> predicate) {
      Objects.requireNonNull(predicate, "predicate");
      List<V> removed = new ArrayList<>();
      this.entries.entrySet().removeIf(entry -> {
         if (!predicate.test(entry.getValue().value())) {
            return false;
         } else {
            removed.add(entry.getValue().value());
            return true;
         }
      });
      return List.copyOf(removed);
   }

   synchronized List<V> values() {
      return this.entries.values().stream().map(slot -> slot.value()).toList();
   }

   synchronized List<V> clear() {
      List<V> removed = this.entries.values().stream().map(slot -> slot.value()).toList();
      this.entries.clear();
      return removed;
   }

   synchronized int size() {
      return this.entries.size();
   }

   synchronized boolean isEmpty() {
      return this.entries.isEmpty();
   }

   record PutResult<V>(V replaced, List<V> evicted) {
   }

   private record Slot<V>(V value, long createdAtMillis, long sequence) {
      private boolean olderThan(BoundedConcurrentStore.Slot<V> other) {
         return this.createdAtMillis < other.createdAtMillis || this.createdAtMillis == other.createdAtMillis && this.sequence < other.sequence;
      }
   }
}
