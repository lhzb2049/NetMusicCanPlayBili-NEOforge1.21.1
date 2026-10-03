package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MP4ContainerDeviceIdPacket(int containerSlotIndex, UUID deviceId) implements CustomPacketPayload {
   public static final Type<MP4ContainerDeviceIdPacket> TYPE = new Type(NetworkPayloadIds.id("mp4_container_device_id"));
   public static final StreamCodec<RegistryFriendlyByteBuf, MP4ContainerDeviceIdPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MP4ContainerDeviceIdPacket>() {
      public MP4ContainerDeviceIdPacket decode(RegistryFriendlyByteBuf buffer) {
         return new MP4ContainerDeviceIdPacket(buffer.readInt(), buffer.readUUID());
      }

      public void encode(RegistryFriendlyByteBuf buffer, MP4ContainerDeviceIdPacket packet) {
         buffer.writeInt(packet.containerSlotIndex());
         buffer.writeUUID(packet.deviceId());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(MP4ContainerDeviceIdPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> MP4Client.receiveContainerDeviceId(payload.containerSlotIndex(), payload.deviceId()));
   }
}
