package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class ClientAudioEndpointIndex {
   private static final AudioEndpointIndex INDEX = new AudioEndpointIndex();
   private static final Map<PlaybackSourceId, BlockPos> SOURCE_POSITIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, BlockPos> ENDPOINT_POSITIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> ENDPOINT_REVISIONS = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, Long> SOURCE_GENERATIONS = new ConcurrentHashMap<>();

   private ClientAudioEndpointIndex() {
   }

   public static void accept(AudioEndpointSnapshotPacket packet) {
      if (packet != null) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null) {
            PlaybackSourceId sourceId = PlaybackSourceId.of(packet.sourceId());
            Long currentGeneration = SOURCE_GENERATIONS.get(sourceId);
            if (packet.generation() <= 0L || currentGeneration == null || packet.generation() > currentGeneration) {
               if (packet.reset()) {
                  clearSource(sourceId);
               }

               packet.removals().forEach(ClientAudioEndpointIndex::removeEndpoint);
               if (!packet.subscribed()) {
                  SOURCE_POSITIONS.remove(sourceId);
                  ClientAreaAudioZoneRegistry.removeOutput(packet.sourcePos());
                  if (packet.generation() > 0L) {
                     SOURCE_GENERATIONS.put(sourceId, packet.generation());
                  }
               } else {
                  SOURCE_POSITIONS.put(sourceId, packet.sourcePos().immutable());
                  ClientAreaAudioZoneRegistry.setOutputZone(packet.sourcePos(), packet.sourceZone());
                  String dimension = minecraft.level.dimension().location().toString();

                  for (AudioEndpointSnapshotPacket.Endpoint endpoint : packet.endpoints()) {
                     Long currentRevision = ENDPOINT_REVISIONS.get(endpoint.endpointId());
                     if (currentRevision == null || currentRevision <= endpoint.revision()) {
                        BlockPos pos = endpoint.pos();
                        BlockPos oldPos = ENDPOINT_POSITIONS.put(endpoint.endpointId(), pos);
                        if (oldPos != null && !oldPos.equals(pos)) {
                           ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(oldPos);
                        }

                        ENDPOINT_REVISIONS.put(endpoint.endpointId(), endpoint.revision());
                        ClientAreaAudioZoneRegistry.setOutputZone(pos, endpoint.zone());
                        ensureRelay(pos, packet.sourcePos(), endpoint);
                        INDEX.upsert(
                           new IndexedAudioEndpoint(
                              endpoint.endpointId(),
                              sourceId,
                              dimension,
                              pos.getX() + 0.5,
                              pos.getY() + 0.5,
                              pos.getZ() + 0.5,
                              endpoint.maxDistance(),
                              endpoint.volume(),
                              endpoint.volume(),
                              IndexedAudioEndpoint.Kind.SPEAKER,
                              endpoint.revision()
                           )
                        );
                     }
                  }

                  if (packet.generation() > 0L) {
                     SOURCE_GENERATIONS.put(sourceId, packet.generation());
                  }
               }
            }
         }
      }
   }

   public static Set<UUID> audibleDemands(PlaybackSourceId sourceId) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null) {
         long nowNanos = System.nanoTime();
         Set<UUID> geometric = INDEX.audibleDemands(
            sourceId, minecraft.level.dimension().location().toString(), minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ()
         );
         if (geometric.isEmpty()) {
            return Set.of();
         } else {
            Set<UUID> audible = ConcurrentHashMap.newKeySet();

            for (UUID endpointId : geometric) {
               BlockPos pos = ENDPOINT_POSITIONS.get(endpointId);
               if (pos != null && ClientAreaAudioZoneRegistry.audible(pos, nowNanos)) {
                  audible.add(endpointId);
               }
            }

            return Set.copyOf(audible);
         }
      } else {
         return Set.of();
      }
   }

   public static Set<UUID> geometricDemands(PlaybackSourceId sourceId) {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.level != null && minecraft.player != null
         ? INDEX.audibleDemands(
            sourceId, minecraft.level.dimension().location().toString(), minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ()
         )
         : Set.of();
   }

   public static Set<UUID> anticipatedDemands(PlaybackSourceId sourceId) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null) {
         Vec3 velocity = minecraft.player.getDeltaMovement();
         return INDEX.anticipatedDemands(
            sourceId,
            minecraft.level.dimension().location().toString(),
            minecraft.player.getX(),
            minecraft.player.getEyeY(),
            minecraft.player.getZ(),
            velocity.x,
            velocity.y,
            velocity.z
         );
      } else {
         return Set.of();
      }
   }

   public static BlockPos sourcePosition(PlaybackSourceId sourceId) {
      return sourceId != null ? SOURCE_POSITIONS.get(sourceId) : null;
   }

   public static List<IndexedAudioEndpoint> endpointSnapshot() {
      return INDEX.snapshot();
   }

   public static Map<PlaybackSourceId, BlockPos> sourcePositionSnapshot() {
      return Map.copyOf(SOURCE_POSITIONS);
   }

   public static void clear() {
      ENDPOINT_POSITIONS.values().forEach(ClientAudioOutputRegistry::clearMachineOverrideForSpeaker);
      ENDPOINT_POSITIONS.values().forEach(ClientAreaAudioZoneRegistry::removeOutput);
      SOURCE_POSITIONS.values().forEach(ClientAreaAudioZoneRegistry::removeOutput);
      ENDPOINT_POSITIONS.clear();
      ENDPOINT_REVISIONS.clear();
      SOURCE_POSITIONS.clear();
      SOURCE_GENERATIONS.clear();
      INDEX.clear();
   }

   private static void clearSource(PlaybackSourceId sourceId) {
      for (IndexedAudioEndpoint endpoint : INDEX.endpointsFor(sourceId)) {
         removeEndpoint(endpoint.endpointId());
      }

      INDEX.removeSource(sourceId);
      BlockPos sourcePos = SOURCE_POSITIONS.remove(sourceId);
      ClientAreaAudioZoneRegistry.removeOutput(sourcePos);
   }

   private static void removeEndpoint(UUID endpointId) {
      BlockPos oldPos = ENDPOINT_POSITIONS.remove(endpointId);
      ENDPOINT_REVISIONS.remove(endpointId);
      INDEX.remove(endpointId);
      if (oldPos != null) {
         ClientAudioOutputRegistry.clearMachineOverrideForSpeaker(oldPos);
         ClientAreaAudioZoneRegistry.removeOutput(oldPos);
      }
   }

   private static void ensureRelay(BlockPos speakerPos, BlockPos sourcePos, AudioEndpointSnapshotPacket.Endpoint endpoint) {
      if (!ClientAudioOutputRegistry.hasRelayAt(speakerPos)) {
         SpeakerAudioRelay relay = new SpeakerAudioRelay();
         ClientAudioOutputRegistry.registerRelay(speakerPos, sourcePos, relay);
      }

      ClientAudioOutputRegistry.updateRelayConfig(speakerPos, endpoint.channelIndex(), endpoint.volume(), endpoint.autoMixJoc(), endpoint.maxDistance());
   }
}
