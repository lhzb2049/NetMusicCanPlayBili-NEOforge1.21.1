package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MP4PlaybackSyncPacket(
   UUID ownerId,
   UUID sourceId,
   int sourceType,
   int sourceEntityId,
   double sourceX,
   double sourceY,
   double sourceZ,
   boolean playing,
   int queueIndex,
   String playUrl,
   String rawUrl,
   String songName,
   int durationSeconds,
   int volumePerMille,
   String sessionId,
   long elapsedMillis,
   boolean headphoneRouted,
   AreaAudioZone areaAudioZone
) implements CustomPacketPayload, ClientMediaSyncPayload {
   public static final int SOURCE_PLAYER = 0;
   public static final int SOURCE_ITEM = 1;
   public static final int SOURCE_BLOCK = 2;
   public static final int SOURCE_CONTAINER_ENTITY = 3;
   public static final Type<MP4PlaybackSyncPacket> TYPE = new Type(NetworkPayloadIds.id("mp4_playback_sync"));
   private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<RegistryFriendlyByteBuf, UUID>() {
      public UUID decode(RegistryFriendlyByteBuf buffer) {
         return buffer.readUUID();
      }

      public void encode(RegistryFriendlyByteBuf buffer, UUID value) {
         buffer.writeUUID(value);
      }
   };
   public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackSyncPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackSyncPacket>() {
      public MP4PlaybackSyncPacket decode(RegistryFriendlyByteBuf buffer) {
         return new MP4PlaybackSyncPacket(
            (UUID)MP4PlaybackSyncPacket.UUID_CODEC.decode(buffer),
            (UUID)MP4PlaybackSyncPacket.UUID_CODEC.decode(buffer),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readDouble(),
            buffer.readBoolean(),
            buffer.readInt(),
            buffer.readUtf(),
            buffer.readUtf(),
            buffer.readUtf(),
            buffer.readInt(),
            buffer.readInt(),
            buffer.readUtf(),
            buffer.readVarLong(),
            buffer.readBoolean(),
            AreaAudioZoneCodec.decode(buffer)
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackSyncPacket packet) {
         MP4PlaybackSyncPacket.UUID_CODEC.encode(buffer, packet.ownerId());
         MP4PlaybackSyncPacket.UUID_CODEC.encode(buffer, packet.sourceId());
         buffer.writeVarInt(packet.sourceType());
         buffer.writeVarInt(packet.sourceEntityId());
         buffer.writeDouble(packet.sourceX());
         buffer.writeDouble(packet.sourceY());
         buffer.writeDouble(packet.sourceZ());
         buffer.writeBoolean(packet.playing());
         buffer.writeInt(packet.queueIndex());
         buffer.writeUtf(packet.playUrl());
         buffer.writeUtf(packet.rawUrl());
         buffer.writeUtf(packet.songName());
         buffer.writeInt(packet.durationSeconds());
         buffer.writeInt(packet.volumePerMille());
         buffer.writeUtf(packet.sessionId());
         buffer.writeVarLong(packet.elapsedMillis());
         buffer.writeBoolean(packet.headphoneRouted());
         AreaAudioZoneCodec.encode(buffer, packet.areaAudioZone());
      }
   };

   public MP4PlaybackSyncPacket(
      UUID ownerId,
      UUID sourceId,
      int sourceType,
      int sourceEntityId,
      double sourceX,
      double sourceY,
      double sourceZ,
      boolean playing,
      int queueIndex,
      String playUrl,
      String rawUrl,
      String songName,
      int durationSeconds,
      int volumePerMille,
      String sessionId,
      long elapsedMillis,
      boolean headphoneRouted
   ) {
      this(
         ownerId,
         sourceId,
         sourceType,
         sourceEntityId,
         sourceX,
         sourceY,
         sourceZ,
         playing,
         queueIndex,
         playUrl,
         rawUrl,
         songName,
         durationSeconds,
         volumePerMille,
         sessionId,
         elapsedMillis,
         headphoneRouted,
         AreaAudioZone.unrestricted()
      );
   }

   public MP4PlaybackSyncPacket(
      UUID ownerId,
      UUID sourceId,
      int sourceType,
      int sourceEntityId,
      double sourceX,
      double sourceY,
      double sourceZ,
      boolean playing,
      int queueIndex,
      String playUrl,
      String rawUrl,
      String songName,
      int durationSeconds,
      int volumePerMille,
      String sessionId,
      long elapsedMillis,
      boolean headphoneRouted,
      AreaAudioZone areaAudioZone
   ) {
      areaAudioZone = areaAudioZone != null ? areaAudioZone : AreaAudioZone.unrestricted();
      this.ownerId = ownerId;
      this.sourceId = sourceId;
      this.sourceType = sourceType;
      this.sourceEntityId = sourceEntityId;
      this.sourceX = sourceX;
      this.sourceY = sourceY;
      this.sourceZ = sourceZ;
      this.playing = playing;
      this.queueIndex = queueIndex;
      this.playUrl = playUrl;
      this.rawUrl = rawUrl;
      this.songName = songName;
      this.durationSeconds = durationSeconds;
      this.volumePerMille = volumePerMille;
      this.sessionId = sessionId;
      this.elapsedMillis = elapsedMillis;
      this.headphoneRouted = headphoneRouted;
      this.areaAudioZone = areaAudioZone;
   }

   public static MP4PlaybackSyncPacket stop(UUID ownerId, int queueIndex) {
      return stop(ownerId, ownerId, queueIndex);
   }

   public static MP4PlaybackSyncPacket stop(UUID ownerId, UUID sourceId, int queueIndex) {
      UUID normalizedSourceId = sourceId != null ? sourceId : ownerId;
      return new MP4PlaybackSyncPacket(ownerId, normalizedSourceId, 0, -1, 0.0, 0.0, 0.0, false, queueIndex, "", "", "", 0, 0, "", 0L, false);
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(MP4PlaybackSyncPacket payload, IPayloadContext context) {
      MP4ClientMediaSync.handleSync(payload);
   }
}
