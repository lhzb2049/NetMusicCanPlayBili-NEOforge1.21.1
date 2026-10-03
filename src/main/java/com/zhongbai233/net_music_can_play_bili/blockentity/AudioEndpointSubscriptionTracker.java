package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;

final class AudioEndpointSubscriptionTracker {
   static final int CELL_SIZE_BLOCKS = 32;
   static final double DISCOVERY_LEAD_BLOCKS = 48.0;
   private final Map<AudioEndpointSubscriptionTracker.Key, AudioEndpointSubscriptionTracker.State> states = new HashMap<>();
   private final Map<AudioEndpointSubscriptionTracker.Key, Long> generations = new HashMap<>();

   AudioEndpointSubscriptionTracker.Update update(
      UUID playerId,
      PlaybackSourceId sourceId,
      String sessionId,
      long sourcePos,
      double playerX,
      double playerY,
      double playerZ,
      int sourceRange,
      List<AudioEndpointSubscriptionTracker.Endpoint> endpoints
   ) {
      return this.update(playerId, sourceId, sessionId, sourcePos, playerX, playerY, playerZ, sourceRange, snapshot(endpoints));
   }

   synchronized AudioEndpointSubscriptionTracker.Update update(
      UUID playerId,
      PlaybackSourceId sourceId,
      String sessionId,
      long sourcePos,
      double playerX,
      double playerY,
      double playerZ,
      int sourceRange,
      AudioEndpointSubscriptionTracker.SpatialSnapshot endpoints
   ) {
      if (playerId != null && sourceId != null) {
         AudioEndpointSubscriptionTracker.Key key = new AudioEndpointSubscriptionTracker.Key(playerId, sourceId);
         AudioEndpointSubscriptionTracker.State previous = this.states.get(key);
         String normalizedSession = sessionId != null ? sessionId : "";
         boolean reset = previous == null || !previous.sessionId.equals(normalizedSession) || previous.sourcePos != sourcePos;
         Map<UUID, AudioEndpointSubscriptionTracker.Endpoint> interested = interestedEndpoints(
            playerX, playerY, playerZ, endpoints != null ? endpoints : AudioEndpointSubscriptionTracker.SpatialSnapshot.EMPTY
         );
         double sourceDiscoveryRange = Math.max(0, sourceRange) + 48.0;
         boolean sourceInterested = distanceSquared(playerX, playerY, playerZ, unpackX(sourcePos) + 0.5, unpackY(sourcePos) + 0.5, unpackZ(sourcePos) + 0.5)
            <= sourceDiscoveryRange * sourceDiscoveryRange;
         boolean subscribed = sourceInterested || !interested.isEmpty();
         if (subscribed) {
            Map<UUID, AudioEndpointSubscriptionTracker.Endpoint> old = !reset && previous != null ? previous.endpoints : Map.of();
            List<AudioEndpointSubscriptionTracker.Endpoint> upserts = new ArrayList<>();
            interested.forEach((endpointId, endpoint) -> {
               if (!endpoint.equals(old.get(endpointId))) {
                  upserts.add(endpoint);
               }
            });
            List<UUID> removals = reset ? List.of() : old.keySet().stream().filter(endpointId -> !interested.containsKey(endpointId)).toList();
            if (previous != null && !reset && upserts.isEmpty() && removals.isEmpty()) {
               return new AudioEndpointSubscriptionTracker.Update(true, true, false, previous.generation, List.of(), List.of());
            } else {
               long generation = this.nextGeneration(key);
               this.states.put(key, new AudioEndpointSubscriptionTracker.State(normalizedSession, sourcePos, Map.copyOf(interested), generation));
               return new AudioEndpointSubscriptionTracker.Update(true, true, reset, generation, List.copyOf(upserts), List.copyOf(removals));
            }
         } else if (previous == null) {
            return AudioEndpointSubscriptionTracker.Update.NONE;
         } else {
            long generation = this.nextGeneration(key);
            this.states.remove(key);
            return new AudioEndpointSubscriptionTracker.Update(false, false, true, generation, List.of(), List.of());
         }
      } else {
         return AudioEndpointSubscriptionTracker.Update.NONE;
      }
   }

   synchronized void forgetPlayer(UUID playerId) {
      if (playerId != null) {
         this.states.keySet().removeIf(key -> playerId.equals(key.playerId));
         this.generations.keySet().removeIf(key -> playerId.equals(key.playerId));
      }
   }

   synchronized void forgetSource(PlaybackSourceId sourceId) {
      if (sourceId != null) {
         this.states.keySet().removeIf(key -> sourceId.equals(key.sourceId));
      }
   }

   synchronized void clear() {
      this.states.clear();
      this.generations.clear();
   }

   private long nextGeneration(AudioEndpointSubscriptionTracker.Key key) {
      long next = this.generations.getOrDefault(key, 0L) + 1L;
      this.generations.put(key, next);
      return next;
   }

   static AudioEndpointSubscriptionTracker.SpatialSnapshot snapshot(List<AudioEndpointSubscriptionTracker.Endpoint> endpoints) {
      if (endpoints != null && !endpoints.isEmpty()) {
         Map<AudioEndpointSubscriptionTracker.Cell, List<AudioEndpointSubscriptionTracker.Endpoint>> mutable = new HashMap<>();

         for (AudioEndpointSubscriptionTracker.Endpoint endpoint : endpoints) {
            mutable.computeIfAbsent(AudioEndpointSubscriptionTracker.Cell.of(endpoint.endpointPos()), ignored -> new ArrayList<>()).add(endpoint);
         }

         Map<AudioEndpointSubscriptionTracker.Cell, List<AudioEndpointSubscriptionTracker.Endpoint>> cells = new HashMap<>();
         mutable.forEach((cell, entries) -> cells.put(cell, List.copyOf(entries)));
         return new AudioEndpointSubscriptionTracker.SpatialSnapshot(Map.copyOf(cells));
      } else {
         return AudioEndpointSubscriptionTracker.SpatialSnapshot.EMPTY;
      }
   }

   private static Map<UUID, AudioEndpointSubscriptionTracker.Endpoint> interestedEndpoints(
      double playerX, double playerY, double playerZ, AudioEndpointSubscriptionTracker.SpatialSnapshot endpoints
   ) {
      if (endpoints.cells.isEmpty()) {
         return Map.of();
      } else {
         Map<UUID, AudioEndpointSubscriptionTracker.Endpoint> result = new HashMap<>();

         for (Entry<AudioEndpointSubscriptionTracker.Cell, List<AudioEndpointSubscriptionTracker.Endpoint>> cell : endpoints.cells.entrySet()) {
            double maximumRadius = cell.getValue().stream().mapToDouble(AudioEndpointSubscriptionTracker::discoveryRadius).max().orElse(0.0);
            if (cell.getKey().mayReach(playerX, playerY, playerZ, maximumRadius)) {
               for (AudioEndpointSubscriptionTracker.Endpoint endpoint : cell.getValue()) {
                  long pos = endpoint.endpointPos();
                  double radius = discoveryRadius(endpoint);
                  if (distanceSquared(playerX, playerY, playerZ, unpackX(pos) + 0.5, unpackY(pos) + 0.5, unpackZ(pos) + 0.5) <= radius * radius) {
                     result.put(endpoint.endpointId(), endpoint);
                  }
               }
            }
         }

         return result;
      }
   }

   private static double discoveryRadius(AudioEndpointSubscriptionTracker.Endpoint endpoint) {
      AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(endpoint.maxDistance(), endpoint.volume(), endpoint.volume());
      return profile.fadeEndDistance() + 48.0;
   }

   private static double distanceSquared(double ax, double ay, double az, double bx, double by, double bz) {
      double dx = ax - bx;
      double dy = ay - by;
      double dz = az - bz;
      return dx * dx + dy * dy + dz * dz;
   }

   private static int unpackX(long packed) {
      return (int)(packed >> 38);
   }

   private static int unpackY(long packed) {
      return (int)(packed << 52 >> 52);
   }

   private static int unpackZ(long packed) {
      return (int)(packed << 26 >> 38);
   }

   private record Cell(int x, int y, int z) {
      static AudioEndpointSubscriptionTracker.Cell of(long pos) {
         return new AudioEndpointSubscriptionTracker.Cell(
            Math.floorDiv(AudioEndpointSubscriptionTracker.unpackX(pos), 32),
            Math.floorDiv(AudioEndpointSubscriptionTracker.unpackY(pos), 32),
            Math.floorDiv(AudioEndpointSubscriptionTracker.unpackZ(pos), 32)
         );
      }

      boolean mayReach(double pointX, double pointY, double pointZ, double radius) {
         double minX = this.x * 32.0;
         double minY = this.y * 32.0;
         double minZ = this.z * 32.0;
         double maxX = minX + 32.0;
         double maxY = minY + 32.0;
         double maxZ = minZ + 32.0;
         double closestX = Math.clamp(pointX, minX, maxX);
         double closestY = Math.clamp(pointY, minY, maxY);
         double closestZ = Math.clamp(pointZ, minZ, maxZ);
         return AudioEndpointSubscriptionTracker.distanceSquared(pointX, pointY, pointZ, closestX, closestY, closestZ) <= radius * radius;
      }
   }

   record Endpoint(UUID endpointId, long endpointPos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision) {
   }

   private record Key(UUID playerId, PlaybackSourceId sourceId) {
   }

   record SpatialSnapshot(Map<AudioEndpointSubscriptionTracker.Cell, List<AudioEndpointSubscriptionTracker.Endpoint>> cells) {
      private static final AudioEndpointSubscriptionTracker.SpatialSnapshot EMPTY = new AudioEndpointSubscriptionTracker.SpatialSnapshot(Map.of());
   }

   private record State(String sessionId, long sourcePos, Map<UUID, AudioEndpointSubscriptionTracker.Endpoint> endpoints, long generation) {
   }

   record Update(
      boolean subscribed,
      boolean playbackRecipient,
      boolean reset,
      long generation,
      List<AudioEndpointSubscriptionTracker.Endpoint> upserts,
      List<UUID> removals
   ) {
      private static final AudioEndpointSubscriptionTracker.Update NONE = new AudioEndpointSubscriptionTracker.Update(
         false, false, false, 0L, List.of(), List.of()
      );

      boolean packetRequired() {
         return this.reset || !this.upserts.isEmpty() || !this.removals.isEmpty();
      }
   }
}
