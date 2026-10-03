package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

final class PadResolveIntentRegistry {
   private final ConcurrentMap<PlaybackSourceId, PadResolveIntentRegistry.Intent> active = new ConcurrentHashMap<>();
   private final AtomicReference<ResolveGeneration> generations = new AtomicReference<>(ResolveGeneration.initial());

   synchronized PadResolveIntentRegistry.Intent replaceCommand(UUID ownerId, UUID deviceId, UUID pointId, int mediaId, String sourceUrl) {
      PadResolveIntentRegistry.Intent replacement = new PadResolveIntentRegistry.Intent(
         this.nextGeneration(), ownerId, pointId, mediaId, normalize(sourceUrl), null
      );
      this.active.put(PlaybackSourceId.of(deviceId), replacement);
      return replacement;
   }

   synchronized PadResolveIntentRegistry.Intent beginRetryIfIdle(
      UUID ownerId, UUID deviceId, UUID pointId, int mediaId, String sourceUrl, PlaybackSessionId expectedSessionId
   ) {
      if (ownerId != null
         && deviceId != null
         && pointId != null
         && expectedSessionId != null
         && PadPlaybackSessionIds.matches(expectedSessionId.value(), deviceId, pointId)) {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         PadResolveIntentRegistry.Intent[] started = new PadResolveIntentRegistry.Intent[1];
         this.active
            .compute(
               sourceId,
               (ignored, current) -> {
                  if (current != null) {
                     return (PadResolveIntentRegistry.Intent)current;
                  } else {
                     PadResolveIntentRegistry.Intent intent = new PadResolveIntentRegistry.Intent(
                        this.nextGeneration(), ownerId, pointId, mediaId, normalize(sourceUrl), expectedSessionId
                     );
                     started[0] = intent;
                     return intent;
                  }
               }
            );
         return started[0];
      } else {
         return null;
      }
   }

   synchronized boolean isCurrent(UUID deviceId, PadResolveIntentRegistry.Intent intent) {
      return deviceId != null && intent != null && this.active.get(PlaybackSourceId.of(deviceId)) == intent;
   }

   synchronized void complete(UUID deviceId, PadResolveIntentRegistry.Intent intent) {
      if (deviceId != null && intent != null) {
         this.active.remove(PlaybackSourceId.of(deviceId), intent);
      }
   }

   synchronized void invalidate(UUID deviceId) {
      if (deviceId != null) {
         this.active.remove(PlaybackSourceId.of(deviceId));
      }
   }

   synchronized void invalidateOwner(UUID ownerId) {
      if (ownerId != null) {
         this.active.entrySet().removeIf(entry -> ownerId.equals(entry.getValue().ownerId()));
      }
   }

   synchronized void clear() {
      this.active.clear();
   }

   synchronized boolean commitIfCurrent(UUID deviceId, PadResolveIntentRegistry.Intent intent, BooleanSupplier commit) {
      if (this.isCurrent(deviceId, intent) && commit != null) {
         boolean var4;
         try {
            var4 = commit.getAsBoolean();
         } finally {
            this.active.remove(PlaybackSourceId.of(deviceId), intent);
         }

         return var4;
      } else {
         return false;
      }
   }

   private ResolveGeneration nextGeneration() {
      return this.generations.updateAndGet(current -> Objects.requireNonNull(current, "current generation").next());
   }

   private static String normalize(String value) {
      return value != null ? value : "";
   }

   record Intent(ResolveGeneration generation, UUID ownerId, UUID pointId, int mediaId, String sourceUrl, PlaybackSessionId expectedSessionId) {
      Intent(ResolveGeneration generation, UUID ownerId, UUID pointId, int mediaId, String sourceUrl, PlaybackSessionId expectedSessionId) {
         Objects.requireNonNull(generation, "generation");
         Objects.requireNonNull(ownerId, "ownerId");
         Objects.requireNonNull(pointId, "pointId");
         sourceUrl = PadResolveIntentRegistry.normalize(sourceUrl);
         this.generation = generation;
         this.ownerId = ownerId;
         this.pointId = pointId;
         this.mediaId = mediaId;
         this.sourceUrl = sourceUrl;
         this.expectedSessionId = expectedSessionId;
      }

      boolean retry() {
         return this.expectedSessionId != null;
      }
   }
}
