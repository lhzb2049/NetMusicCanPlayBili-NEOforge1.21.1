package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class ClientMediaRetryRegistry {
   private final Set<ClientMediaRetryRegistry.Key> pending = ConcurrentHashMap.newKeySet();

   synchronized boolean tryMark(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      return sourceId != null && sessionId != null && this.pending.add(new ClientMediaRetryRegistry.Key(sourceId, sessionId));
   }

   synchronized boolean contains(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      return sourceId != null && sessionId != null && this.pending.contains(new ClientMediaRetryRegistry.Key(sourceId, sessionId));
   }

   synchronized boolean forget(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      return sourceId != null && sessionId != null && this.pending.remove(new ClientMediaRetryRegistry.Key(sourceId, sessionId));
   }

   synchronized void forgetSource(PlaybackSourceId sourceId) {
      if (sourceId != null) {
         this.pending.removeIf(key -> key.sourceId().equals(sourceId));
      }
   }

   synchronized boolean dispatchIfPending(PlaybackSourceId sourceId, PlaybackSessionId sessionId, BooleanSupplier dispatch) {
      return sourceId != null && sessionId != null && dispatch != null && this.pending.contains(new ClientMediaRetryRegistry.Key(sourceId, sessionId))
         ? dispatch.getAsBoolean()
         : false;
   }

   synchronized int size() {
      return this.pending.size();
   }

   synchronized void clear() {
      this.pending.clear();
   }

   private record Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      private Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(sessionId, "sessionId");
         this.sourceId = sourceId;
         this.sessionId = sessionId;
      }
   }
}
