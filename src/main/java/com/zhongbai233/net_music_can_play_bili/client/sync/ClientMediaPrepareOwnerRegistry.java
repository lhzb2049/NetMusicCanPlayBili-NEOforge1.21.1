package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class ClientMediaPrepareOwnerRegistry {
   private final ConcurrentMap<ClientMediaPrepareOwnerRegistry.Key, ClientMediaPrepareOwnerRegistry.Owner> owners = new ConcurrentHashMap<>();

   boolean tryRegister(ClientMediaPrepareOwnerRegistry.Key key, ClientMediaPrepareOwnerRegistry.Owner owner) {
      return this.owners.putIfAbsent(Objects.requireNonNull(key, "key"), Objects.requireNonNull(owner, "owner")) == null;
   }

   void replace(ClientMediaPrepareOwnerRegistry.Key key, ClientMediaPrepareOwnerRegistry.Owner owner) {
      ClientMediaPrepareOwnerRegistry.Owner replacement = Objects.requireNonNull(owner, "owner");
      ClientMediaPrepareOwnerRegistry.Owner previous = this.owners.put(Objects.requireNonNull(key, "key"), replacement);
      if (previous != null && previous != replacement) {
         previous.cancel();
      }
   }

   boolean remove(ClientMediaPrepareOwnerRegistry.Key key, ClientMediaPrepareOwnerRegistry.Owner owner) {
      return key != null && owner != null && this.owners.remove(key, owner);
   }

   void cancelSource(PlaybackSourceId sourceId) {
      if (sourceId != null) {
         this.owners.forEach((key, owner) -> {
            if (sourceId.equals(key.sourceId()) && this.owners.remove(key, owner)) {
               owner.cancel();
            }
         });
      }
   }

   boolean contains(ClientMediaPrepareOwnerRegistry.Key key) {
      return key != null && this.owners.containsKey(key);
   }

   int size() {
      return this.owners.size();
   }

   void clear() {
      this.owners.forEach((key, owner) -> {
         if (this.owners.remove(key, owner)) {
            owner.cancel();
         }
      });
   }

   record Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId, boolean headphoneRouted) {
      Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId, boolean headphoneRouted) {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(sessionId, "sessionId");
         this.sourceId = sourceId;
         this.sessionId = sessionId;
         this.headphoneRouted = headphoneRouted;
      }
   }

   interface Owner {
      void cancel();
   }
}
