package com.zhongbai233.net_music_can_play_bili.client.audio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class PersistentRouteOwners<K> {
   private final Map<K, K> sourceByOwner = new HashMap<>();
   private final Map<K, Set<K>> ownersBySource = new HashMap<>();

   synchronized PersistentRouteOwners.Change<K> bind(K owner, K source) {
      if (owner != null && source != null) {
         K previous = this.sourceByOwner.put(owner, source);
         if (previous != null && !previous.equals(source)) {
            this.removeOwner(previous, owner);
         }

         this.ownersBySource.computeIfAbsent(source, ignored -> new HashSet<>()).add(owner);
         return new PersistentRouteOwners.Change<>(previous, source);
      } else {
         throw new IllegalArgumentException("owner and source must not be null");
      }
   }

   synchronized K unbind(K owner) {
      if (owner == null) {
         return null;
      } else {
         K previous = this.sourceByOwner.remove(owner);
         if (previous != null) {
            this.removeOwner(previous, owner);
         }

         return previous;
      }
   }

   synchronized boolean hasOwners(K source) {
      Set<K> owners = source != null ? this.ownersBySource.get(source) : null;
      return owners != null && !owners.isEmpty();
   }

   synchronized void clear() {
      this.sourceByOwner.clear();
      this.ownersBySource.clear();
   }

   private void removeOwner(K source, K owner) {
      Set<K> owners = this.ownersBySource.get(source);
      if (owners != null) {
         owners.remove(owner);
         if (owners.isEmpty()) {
            this.ownersBySource.remove(source);
         }
      }
   }

   record Change<K>(K previousSource, K currentSource) {
   }
}
