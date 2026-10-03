package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

final class MP4ResolveIntentRegistry {
   private final ConcurrentMap<PlaybackSourceId, MP4ResolveIntentRegistry.Intent> active = new ConcurrentHashMap<>();
   private final AtomicReference<ResolveGeneration> generations = new AtomicReference<>(ResolveGeneration.initial());

   MP4ResolveIntentRegistry.Intent begin(UUID deviceId, int queueIndex, String sourceUrl) {
      PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
      String normalizedSource = sourceUrl != null ? sourceUrl : "";
      MP4ResolveIntentRegistry.Intent[] started = new MP4ResolveIntentRegistry.Intent[1];
      this.active.compute(sourceId, (ignored, current) -> {
         if (current != null && current.matches(queueIndex, normalizedSource)) {
            return (MP4ResolveIntentRegistry.Intent)current;
         } else {
            MP4ResolveIntentRegistry.Intent replacement = new MP4ResolveIntentRegistry.Intent(this.nextGeneration(), queueIndex, normalizedSource);
            started[0] = replacement;
            return replacement;
         }
      });
      return started[0];
   }

   MP4ResolveIntentRegistry.Intent replace(UUID deviceId, int queueIndex, String sourceUrl) {
      PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
      MP4ResolveIntentRegistry.Intent replacement = new MP4ResolveIntentRegistry.Intent(this.nextGeneration(), queueIndex, sourceUrl != null ? sourceUrl : "");
      this.active.put(sourceId, replacement);
      return replacement;
   }

   MP4ResolveIntentRegistry.Intent beginIfIdle(UUID deviceId, int queueIndex, String sourceUrl) {
      PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
      String normalizedSource = sourceUrl != null ? sourceUrl : "";
      MP4ResolveIntentRegistry.Intent[] started = new MP4ResolveIntentRegistry.Intent[1];
      this.active.compute(sourceId, (ignored, current) -> {
         if (current != null) {
            return (MP4ResolveIntentRegistry.Intent)current;
         } else {
            MP4ResolveIntentRegistry.Intent intent = new MP4ResolveIntentRegistry.Intent(this.nextGeneration(), queueIndex, normalizedSource);
            started[0] = intent;
            return intent;
         }
      });
      return started[0];
   }

   boolean isCurrent(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
      return deviceId != null && intent != null && this.active.get(PlaybackSourceId.of(deviceId)) == intent;
   }

   void complete(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
      if (deviceId != null && intent != null) {
         this.active.remove(PlaybackSourceId.of(deviceId), intent);
      }
   }

   void invalidate(UUID deviceId) {
      if (deviceId != null) {
         this.active.remove(PlaybackSourceId.of(deviceId));
      }
   }

   void clear() {
      this.active.clear();
   }

   private ResolveGeneration nextGeneration() {
      return this.generations.updateAndGet(current -> Objects.requireNonNull(current, "current generation").next());
   }

   record Intent(ResolveGeneration generation, int queueIndex, String sourceUrl) {
      Intent(ResolveGeneration generation, int queueIndex, String sourceUrl) {
         Objects.requireNonNull(generation, "generation");
         this.generation = generation;
         this.queueIndex = queueIndex;
         this.sourceUrl = sourceUrl;
      }

      private boolean matches(int candidateQueueIndex, String candidateSourceUrl) {
         return this.queueIndex == candidateQueueIndex && this.sourceUrl.equals(candidateSourceUrl);
      }
   }
}
