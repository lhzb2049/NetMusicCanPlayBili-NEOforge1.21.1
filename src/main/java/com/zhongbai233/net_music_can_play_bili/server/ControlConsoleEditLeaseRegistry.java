package com.zhongbai233.net_music_can_play_bili.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ControlConsoleEditLeaseRegistry {
   public static final long LEASE_MILLIS = 10000L;
   private static final Map<ControlConsoleEditLeaseRegistry.Key, ControlConsoleEditLeaseRegistry.Lease> LEASES = new HashMap<>();

   private ControlConsoleEditLeaseRegistry() {
   }

   public static synchronized ControlConsoleEditLeaseRegistry.AcquireResult acquire(ControlConsoleEditLeaseRegistry.Key key, UUID playerId, long nowMillis) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(playerId, "playerId");
      ControlConsoleEditLeaseRegistry.Lease current = LEASES.get(key);
      if (current == null || current.expiresAtMillis() <= nowMillis) {
         ControlConsoleEditLeaseRegistry.Lease granted = new ControlConsoleEditLeaseRegistry.Lease(UUID.randomUUID(), playerId, expiresAt(nowMillis));
         LEASES.put(key, granted);
         return new ControlConsoleEditLeaseRegistry.AcquireResult(ControlConsoleEditLeaseRegistry.Status.GRANTED, granted.leaseId(), playerId);
      } else if (!current.playerId().equals(playerId)) {
         return new ControlConsoleEditLeaseRegistry.AcquireResult(ControlConsoleEditLeaseRegistry.Status.BUSY, null, current.playerId());
      } else {
         ControlConsoleEditLeaseRegistry.Lease renewed = current.renewed(nowMillis);
         LEASES.put(key, renewed);
         return new ControlConsoleEditLeaseRegistry.AcquireResult(ControlConsoleEditLeaseRegistry.Status.GRANTED, renewed.leaseId(), playerId);
      }
   }

   public static synchronized boolean renew(ControlConsoleEditLeaseRegistry.Key key, UUID playerId, UUID leaseId, long nowMillis) {
      ControlConsoleEditLeaseRegistry.Lease current = LEASES.get(key);
      if (!matches(current, playerId, leaseId, nowMillis)) {
         return false;
      } else {
         LEASES.put(key, current.renewed(nowMillis));
         return true;
      }
   }

   public static synchronized boolean validate(ControlConsoleEditLeaseRegistry.Key key, UUID playerId, UUID leaseId, long nowMillis) {
      return matches(LEASES.get(key), playerId, leaseId, nowMillis);
   }

   public static synchronized void release(ControlConsoleEditLeaseRegistry.Key key, UUID playerId, UUID leaseId) {
      ControlConsoleEditLeaseRegistry.Lease current = LEASES.get(key);
      if (current != null && current.playerId().equals(playerId) && current.leaseId().equals(leaseId)) {
         LEASES.remove(key);
      }
   }

   public static synchronized void releasePlayer(UUID playerId) {
      LEASES.values().removeIf(lease -> lease.playerId().equals(playerId));
   }

   public static synchronized void cleanupExpired(long nowMillis) {
      Iterator<ControlConsoleEditLeaseRegistry.Lease> iterator = LEASES.values().iterator();

      while (iterator.hasNext()) {
         if (iterator.next().expiresAtMillis() <= nowMillis) {
            iterator.remove();
         }
      }
   }

   public static synchronized void clear() {
      LEASES.clear();
   }

   private static boolean matches(ControlConsoleEditLeaseRegistry.Lease lease, UUID playerId, UUID leaseId, long nowMillis) {
      return lease != null && lease.expiresAtMillis() > nowMillis && lease.playerId().equals(playerId) && lease.leaseId().equals(leaseId);
   }

   private static long expiresAt(long nowMillis) {
      return nowMillis > 9223372036854765807L ? Long.MAX_VALUE : nowMillis + 10000L;
   }

   public record AcquireResult(ControlConsoleEditLeaseRegistry.Status status, UUID leaseId, UUID holderId) {
   }

   public record Key(String dimension, long packedPos) {
      public Key(String dimension, long packedPos) {
         dimension = Objects.requireNonNull(dimension, "dimension");
         if (dimension.isBlank()) {
            throw new IllegalArgumentException("dimension must not be blank");
         } else {
            this.dimension = dimension;
            this.packedPos = packedPos;
         }
      }
   }

   private record Lease(UUID leaseId, UUID playerId, long expiresAtMillis) {
      private ControlConsoleEditLeaseRegistry.Lease renewed(long nowMillis) {
         return new ControlConsoleEditLeaseRegistry.Lease(this.leaseId, this.playerId, ControlConsoleEditLeaseRegistry.expiresAt(nowMillis));
      }
   }

   public static enum Status {
      GRANTED,
      BUSY;
   }
}
