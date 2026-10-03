package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSync;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelinePayload;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MP4PlaybackTimelinePacket(
   UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille, boolean headphoneRouted, AreaAudioZone areaAudioZone
) implements CustomPacketPayload, ClientMediaTimelinePayload {
   public static final Type<MP4PlaybackTimelinePacket> TYPE = new Type(NetworkPayloadIds.id("mp4_playback_timeline"));
   private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<RegistryFriendlyByteBuf, UUID>() {
      public UUID decode(RegistryFriendlyByteBuf buffer) {
         return buffer.readUUID();
      }

      public void encode(RegistryFriendlyByteBuf buffer, UUID value) {
         buffer.writeUUID(value);
      }
   };
   public static final StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackTimelinePacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MP4PlaybackTimelinePacket>() {
      public MP4PlaybackTimelinePacket decode(RegistryFriendlyByteBuf buffer) {
         return new MP4PlaybackTimelinePacket(
            (UUID)MP4PlaybackTimelinePacket.UUID_CODEC.decode(buffer),
            buffer.readUtf(128),
            buffer.readVarLong(),
            buffer.readVarInt(),
            buffer.readBoolean(),
            AreaAudioZoneCodec.decode(buffer)
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, MP4PlaybackTimelinePacket packet) {
         MP4PlaybackTimelinePacket.UUID_CODEC.encode(buffer, packet.sourceId());
         buffer.writeUtf(packet.sessionId() == null ? "" : packet.sessionId(), 128);
         buffer.writeVarLong(Math.max(0L, packet.elapsedMillis()));
         buffer.writeVarInt(Math.max(0, Math.min(1000, packet.volumePerMille())));
         buffer.writeBoolean(packet.headphoneRouted());
         AreaAudioZoneCodec.encode(buffer, packet.areaAudioZone());
      }
   };

   public MP4PlaybackTimelinePacket(UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille, boolean headphoneRouted) {
      this(sourceId, sessionId, elapsedMillis, volumePerMille, headphoneRouted, AreaAudioZone.unrestricted());
   }

   public MP4PlaybackTimelinePacket(
      UUID sourceId, String sessionId, long elapsedMillis, int volumePerMille, boolean headphoneRouted, AreaAudioZone areaAudioZone
   ) {
      areaAudioZone = areaAudioZone != null ? areaAudioZone : AreaAudioZone.unrestricted();
      this.sourceId = sourceId;
      this.sessionId = sessionId;
      this.elapsedMillis = elapsedMillis;
      this.volumePerMille = volumePerMille;
      this.headphoneRouted = headphoneRouted;
      this.areaAudioZone = areaAudioZone;
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(MP4PlaybackTimelinePacket payload, IPayloadContext context) {
      MP4ClientMediaSync.handleTimeline(payload);
   }
}
