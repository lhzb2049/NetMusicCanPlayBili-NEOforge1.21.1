package com.zhongbai233.net_music_can_play_bili.network;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

final class MP4PlaybackSessionRegistry<K, S> {
   private final ConcurrentMap<K, S> sessions = new ConcurrentHashMap<>();
   private final ConcurrentMap<K, Long> missingSince = new ConcurrentHashMap<>();

   S get(K key) {
      return key != null ? this.sessions.get(key) : null;
   }

   boolean contains(K key) {
      return key != null && this.sessions.containsKey(key);
   }

   S replace(K key, S session) {
      K requiredKey = Objects.requireNonNull(key, "key");
      S requiredSession = Objects.requireNonNull(session, "session");
      S previous = this.sessions.put(requiredKey, requiredSession);
      this.missingSince.remove(requiredKey);
      return previous;
   }

   boolean replace(K key, S expected, S replacement) {
      if (key != null && expected != null && replacement != null) {
         AtomicBoolean replaced = new AtomicBoolean();
         this.sessions.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
               return (S)current;
            } else {
               replaced.set(true);
               return replacement;
            }
         });
         if (!replaced.get()) {
            return false;
         } else {
            this.missingSince.remove(key);
            return true;
         }
      } else {
         return false;
      }
   }

   S updateIfPresent(K key, UnaryOperator<S> updater) {
      if (key == null) {
         return null;
      } else {
         Objects.requireNonNull(updater, "updater");
         return this.sessions.computeIfPresent(key, (ignored, current) -> Objects.requireNonNull(updater.apply((S)current), "updated session"));
      }
   }

   S remove(K key) {
      if (key == null) {
         return null;
      } else {
         S removed = this.sessions.remove(key);
         this.missingSince.remove(key);
         return removed;
      }
   }

   boolean remove(K key, S expected) {
      if (key != null && expected != null) {
         AtomicBoolean removed = new AtomicBoolean();
         this.sessions.computeIfPresent(key, (ignored, current) -> {
            if (current != expected) {
               return (S)current;
            } else {
               removed.set(true);
               return null;
            }
         });
         if (!removed.get()) {
            return false;
         } else {
            this.missingSince.remove(key);
            return true;
         }
      } else {
         return false;
      }
   }

   Long markMissingIfCurrent(K key, S expected, long gameTime) {
      if (key != null && expected != null) {
         AtomicReference<Long> result = new AtomicReference<>();
         this.sessions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
               result.set(this.missingSince.computeIfAbsent(key, missing -> gameTime));
            }

            return (S)current;
         });
         return result.get();
      } else {
         return null;
      }
   }

   boolean clearMissingIfCurrent(K key, S expected) {
      if (key != null && expected != null) {
         AtomicBoolean currentSession = new AtomicBoolean();
         this.sessions.computeIfPresent(key, (ignored, current) -> {
            if (current == expected) {
               this.missingSince.remove(key);
               currentSession.set(true);
            }

            return (S)current;
         });
         return currentSession.get();
      } else {
         return false;
      }
   }

   List<Entry<K, S>> entries() {
      return this.sessions.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
   }

   List<S> values() {
      return List.copyOf(this.sessions.values());
   }

   boolean isEmpty() {
      return this.sessions.isEmpty();
   }

   int size() {
      return this.sessions.size();
   }

   void clear() {
      this.sessions.clear();
      this.missingSince.clear();
   }
}
