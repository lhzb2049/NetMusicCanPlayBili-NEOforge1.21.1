package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class VideoSessionInstanceRegistry<T> {
   private final Map<Optional<PlaybackSessionId>, T> instances = new ConcurrentHashMap<>();
   private final Consumer<? super T> disposer;

   VideoSessionInstanceRegistry(Consumer<? super T> disposer) {
      this.disposer = Objects.requireNonNull(disposer, "disposer");
   }

   T get(String sessionId) {
      return this.instances.get(key(sessionId));
   }

   void replace(String sessionId, T instance) {
      T next = Objects.requireNonNull(instance, "instance");
      T previous = this.instances.put(key(sessionId), next);
      if (previous != null && previous != next) {
         this.disposer.accept(previous);
      }
   }

   T remove(String sessionId) {
      T removed = this.instances.remove(key(sessionId));
      if (removed != null) {
         this.disposer.accept(removed);
      }

      return removed;
   }

   boolean remove(String sessionId, T expected) {
      if (expected != null && this.instances.remove(key(sessionId), expected)) {
         this.disposer.accept(expected);
         return true;
      } else {
         return false;
      }
   }

   void removeIf(Predicate<? super T> predicate) {
      Objects.requireNonNull(predicate, "predicate");

      for (Entry<Optional<PlaybackSessionId>, T> entry : this.instances.entrySet()) {
         T instance = entry.getValue();
         if (predicate.test(instance) && this.instances.remove(entry.getKey(), instance)) {
            this.disposer.accept(instance);
         }
      }
   }

   void clear() {
      for (Entry<Optional<PlaybackSessionId>, T> entry : this.instances.entrySet()) {
         T instance = entry.getValue();
         if (this.instances.remove(entry.getKey(), instance)) {
            this.disposer.accept(instance);
         }
      }
   }

   List<T> instances() {
      return List.copyOf(this.instances.values());
   }

   void forEach(Consumer<? super T> action) {
      this.instances().forEach(action);
   }

   int size() {
      return this.instances.size();
   }

   boolean isEmpty() {
      return this.instances.isEmpty();
   }

   private static Optional<PlaybackSessionId> key(String sessionId) {
      return PlaybackSessionId.parse(sessionId);
   }
}
