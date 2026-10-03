package com.zhongbai233.net_music_can_play_bili.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ControlConsoleConsumerLeaseRegistry {
   public static final long LEASE_MILLIS = 3000L;
   private static final Map<ControlConsoleConsumerLeaseRegistry.Key, ControlConsoleConsumerLeaseRegistry.Lease> LEASES = new HashMap<>();

   private ControlConsoleConsumerLeaseRegistry() {
   }

   public static synchronized UUID acquireOrRenew(ControlConsoleConsumerLeaseRegistry.Key key, long nowMillis) {
      Objects.requireNonNull(key, "key");
      ControlConsoleConsumerLeaseRegistry.Lease current = LEASES.get(key);
      if (current != null && current.expiresAtMillis() > nowMillis) {
         LEASES.put(key, new ControlConsoleConsumerLeaseRegistry.Lease(current.leaseId(), expiresAt(nowMillis)));
         return current.leaseId();
      } else {
         UUID leaseId = UUID.randomUUID();
         LEASES.put(key, new ControlConsoleConsumerLeaseRegistry.Lease(leaseId, expiresAt(nowMillis)));
         return leaseId;
      }
   }

   public static synchronized boolean renew(ControlConsoleConsumerLeaseRegistry.Key key, UUID leaseId, long nowMillis) {
      ControlConsoleConsumerLeaseRegistry.Lease current = LEASES.get(key);
      if (current != null && current.expiresAtMillis() > nowMillis && current.leaseId().equals(leaseId)) {
         LEASES.put(key, new ControlConsoleConsumerLeaseRegistry.Lease(current.leaseId(), expiresAt(nowMillis)));
         return true;
      } else {
         return false;
      }
   }

   public static synchronized boolean validate(ControlConsoleConsumerLeaseRegistry.Key key, UUID leaseId, long nowMillis) {
      ControlConsoleConsumerLeaseRegistry.Lease lease = LEASES.get(key);
      return lease != null && lease.expiresAtMillis() > nowMillis && lease.leaseId().equals(leaseId);
   }

   public static synchronized boolean hasActive(ControlConsoleConsumerLeaseRegistry.Key key, long nowMillis) {
      ControlConsoleConsumerLeaseRegistry.Lease lease = LEASES.get(key);
      return lease != null && lease.expiresAtMillis() > nowMillis;
   }

   public static synchronized void release(ControlConsoleConsumerLeaseRegistry.Key key, UUID leaseId) {
      ControlConsoleConsumerLeaseRegistry.Lease lease = LEASES.get(key);
      if (lease != null && lease.leaseId().equals(leaseId)) {
         LEASES.remove(key);
      }
   }

   public static synchronized void releasePlayer(UUID playerId) {
      LEASES.keySet().removeIf(key -> key.playerId().equals(playerId));
   }

   public static synchronized void cleanupExpired(long nowMillis) {
      LEASES.values().removeIf(lease -> lease.expiresAtMillis() <= nowMillis);
   }

   public static synchronized void clear() {
      LEASES.clear();
   }

   public static synchronized Set<UUID> activePlayers(String dimension, long packedPos, long nowMillis) {
      Objects.requireNonNull(dimension, "dimension");
      return LEASES.entrySet()
         .stream()
         .filter(
            entry -> entry.getKey().dimension().equals(dimension) && entry.getKey().packedPos() == packedPos && entry.getValue().expiresAtMillis() > nowMillis
         )
         .map(entry -> entry.getKey().playerId())
         .collect(Collectors.toUnmodifiableSet());
   }

   private static long expiresAt(long nowMillis) {
      return nowMillis > 9223372036854772807L ? Long.MAX_VALUE : nowMillis + 3000L;
   }

   public record Key(String dimension, long packedPos, UUID playerId) {
      public Key(String dimension, long packedPos, UUID playerId) {
         dimension = Objects.requireNonNull(dimension, "dimension");
         playerId = Objects.requireNonNull(playerId, "playerId");
         if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
         } else {
            this.dimension = dimension;
            this.packedPos = packedPos;
            this.playerId = playerId;
         }
      }
   }

   private record Lease(UUID leaseId, long expiresAtMillis) {
   }
}
