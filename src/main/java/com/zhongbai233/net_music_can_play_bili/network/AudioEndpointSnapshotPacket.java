package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AudioEndpointSnapshotPacket(
   UUID sourceId,
   BlockPos sourcePos,
   AreaAudioZone sourceZone,
   long generation,
   boolean reset,
   boolean subscribed,
   List<AudioEndpointSnapshotPacket.Endpoint> endpoints,
   List<UUID> removals
) implements CustomPacketPayload {
   private static final int MAX_ENDPOINTS = 4096;
   public static final Type<AudioEndpointSnapshotPacket> TYPE = new Type(NetworkPayloadIds.id("audio_endpoint_snapshot"));
   public static final StreamCodec<RegistryFriendlyByteBuf, AudioEndpointSnapshotPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, AudioEndpointSnapshotPacket>() {
      public AudioEndpointSnapshotPacket decode(RegistryFriendlyByteBuf buffer) {
         UUID sourceId = buffer.readUUID();
         BlockPos sourcePos = (BlockPos)BlockPos.STREAM_CODEC.decode(buffer);
         AreaAudioZone sourceZone = AreaAudioZoneCodec.decode(buffer);
         long generation = buffer.readVarLong();
         boolean reset = buffer.readBoolean();
         boolean subscribed = buffer.readBoolean();
         int count = buffer.readVarInt();
         if (count >= 0 && count <= 4096) {
            List<AudioEndpointSnapshotPacket.Endpoint> endpoints = new ArrayList<>(count);

            for (int index = 0; index < count; index++) {
               endpoints.add(AudioEndpointSnapshotPacket.Endpoint.decode(buffer));
            }

            int removalCount = buffer.readVarInt();
            if (removalCount >= 0 && removalCount <= 4096) {
               List<UUID> removals = new ArrayList<>(removalCount);

               for (int index = 0; index < removalCount; index++) {
                  removals.add(buffer.readUUID());
               }

               return new AudioEndpointSnapshotPacket(sourceId, sourcePos, sourceZone, generation, reset, subscribed, endpoints, removals);
            } else {
               throw new IllegalArgumentException("invalid audio endpoint removal size: " + removalCount);
            }
         } else {
            throw new IllegalArgumentException("invalid audio endpoint snapshot size: " + count);
         }
      }

      public void encode(RegistryFriendlyByteBuf buffer, AudioEndpointSnapshotPacket packet) {
         buffer.writeUUID(packet.sourceId());
         BlockPos.STREAM_CODEC.encode(buffer, packet.sourcePos());
         AreaAudioZoneCodec.encode(buffer, packet.sourceZone());
         buffer.writeVarLong(packet.generation());
         buffer.writeBoolean(packet.reset());
         buffer.writeBoolean(packet.subscribed());
         buffer.writeVarInt(packet.endpoints().size());
         packet.endpoints().forEach(endpoint -> endpoint.encode(buffer));
         buffer.writeVarInt(packet.removals().size());
         packet.removals().forEach(buffer::writeUUID);
      }
   };

   public AudioEndpointSnapshotPacket(
      UUID sourceId,
      BlockPos sourcePos,
      AreaAudioZone sourceZone,
      long generation,
      boolean reset,
      boolean subscribed,
      List<AudioEndpointSnapshotPacket.Endpoint> endpoints,
      List<UUID> removals
   ) {
      Objects.requireNonNull(sourceId, "sourceId");
      sourcePos = Objects.requireNonNull(sourcePos, "sourcePos").immutable();
      sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
      endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
      removals = List.copyOf(Objects.requireNonNull(removals, "removals"));
      generation = Math.max(0L, generation);
      if (endpoints.size() <= 4096 && removals.size() <= 4096) {
         this.sourceId = sourceId;
         this.sourcePos = sourcePos;
         this.sourceZone = sourceZone;
         this.generation = generation;
         this.reset = reset;
         this.subscribed = subscribed;
         this.endpoints = endpoints;
         this.removals = removals;
      } else {
         throw new IllegalArgumentException("too many audio endpoints");
      }
   }

   public AudioEndpointSnapshotPacket(UUID sourceId, BlockPos sourcePos, List<AudioEndpointSnapshotPacket.Endpoint> endpoints) {
      this(sourceId, sourcePos, AreaAudioZone.unrestricted(), 0L, true, true, endpoints, List.of());
   }

   public AudioEndpointSnapshotPacket(
      UUID sourceId,
      BlockPos sourcePos,
      long generation,
      boolean reset,
      boolean subscribed,
      List<AudioEndpointSnapshotPacket.Endpoint> endpoints,
      List<UUID> removals
   ) {
      this(sourceId, sourcePos, AreaAudioZone.unrestricted(), generation, reset, subscribed, endpoints, removals);
   }

   public static AudioEndpointSnapshotPacket from(PlaybackSourceId sourceId, BlockPos sourcePos, List<AudioPlaybackIndexSavedData.EndpointEntry> entries) {
      return delta(sourceId, sourcePos, 0L, true, true, entries, List.of());
   }

   public static AudioEndpointSnapshotPacket delta(
      PlaybackSourceId sourceId,
      BlockPos sourcePos,
      long generation,
      boolean reset,
      boolean subscribed,
      List<AudioPlaybackIndexSavedData.EndpointEntry> upserts,
      List<UUID> removals
   ) {
      return new AudioEndpointSnapshotPacket(
         sourceId.value(),
         sourcePos,
         AreaAudioZone.unrestricted(),
         generation,
         reset,
         subscribed,
         upserts.stream().map(AudioEndpointSnapshotPacket.Endpoint::from).toList(),
         removals
      );
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(AudioEndpointSnapshotPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> ClientAudioEndpointIndex.accept(payload));
   }

   public record Endpoint(
      UUID endpointId, BlockPos pos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision, AreaAudioZone zone
   ) {
      public Endpoint(UUID endpointId, BlockPos pos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision, AreaAudioZone zone) {
         Objects.requireNonNull(endpointId, "endpointId");
         pos = Objects.requireNonNull(pos, "pos").immutable();
         volume = Math.clamp(volume, 0.0F, 2.0F);
         maxDistance = Math.clamp(maxDistance, 1.0F, 256.0F);
         revision = Math.max(0L, revision);
         zone = Objects.requireNonNull(zone, "zone");
         this.endpointId = endpointId;
         this.pos = pos;
         this.channelIndex = channelIndex;
         this.volume = volume;
         this.autoMixJoc = autoMixJoc;
         this.maxDistance = maxDistance;
         this.revision = revision;
         this.zone = zone;
      }

      public Endpoint(UUID endpointId, BlockPos pos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision) {
         this(endpointId, pos, channelIndex, volume, autoMixJoc, maxDistance, revision, AreaAudioZone.unrestricted());
      }

      static AudioEndpointSnapshotPacket.Endpoint from(AudioPlaybackIndexSavedData.EndpointEntry entry) {
         return new AudioEndpointSnapshotPacket.Endpoint(
            entry.endpointId(),
            BlockPos.of(entry.endpointPos()),
            entry.channelIndex(),
            entry.volume(),
            entry.autoMixJoc(),
            entry.maxDistance(),
            entry.revision(),
            AreaAudioZone.unrestricted()
         );
      }

      static AudioEndpointSnapshotPacket.Endpoint decode(RegistryFriendlyByteBuf buffer) {
         return new AudioEndpointSnapshotPacket.Endpoint(
            buffer.readUUID(),
            (BlockPos)BlockPos.STREAM_CODEC.decode(buffer),
            buffer.readVarInt(),
            buffer.readFloat(),
            buffer.readBoolean(),
            buffer.readFloat(),
            buffer.readVarLong(),
            AreaAudioZoneCodec.decode(buffer)
         );
      }

      void encode(RegistryFriendlyByteBuf buffer) {
         buffer.writeUUID(this.endpointId);
         BlockPos.STREAM_CODEC.encode(buffer, this.pos);
         buffer.writeVarInt(this.channelIndex);
         buffer.writeFloat(this.volume);
         buffer.writeBoolean(this.autoMixJoc);
         buffer.writeFloat(this.maxDistance);
         buffer.writeVarLong(this.revision);
         AreaAudioZoneCodec.encode(buffer, this.zone);
      }
   }
}
