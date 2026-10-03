package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAreaAudioZoneRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AreaAudioListenerPacket(AreaAudioZone zone) implements CustomPacketPayload {
   public static final Type<AreaAudioListenerPacket> TYPE = new Type(NetworkPayloadIds.id("area_audio_listener"));
   public static final StreamCodec<RegistryFriendlyByteBuf, AreaAudioListenerPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, AreaAudioListenerPacket>() {
      public AreaAudioListenerPacket decode(RegistryFriendlyByteBuf buffer) {
         return new AreaAudioListenerPacket(AreaAudioZoneCodec.decode(buffer));
      }

      public void encode(RegistryFriendlyByteBuf buffer, AreaAudioListenerPacket packet) {
         AreaAudioZoneCodec.encode(buffer, packet.zone());
      }
   };

   public AreaAudioListenerPacket(AreaAudioZone zone) {
      zone = Objects.requireNonNull(zone, "zone");
      this.zone = zone;
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(AreaAudioListenerPacket packet, IPayloadContext context) {
      context.enqueueWork(() -> ClientAreaAudioZoneRegistry.acceptListener(packet.zone()));
   }
}
