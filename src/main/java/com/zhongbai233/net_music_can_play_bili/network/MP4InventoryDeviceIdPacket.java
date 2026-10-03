package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MP4InventoryDeviceIdPacket(int inventorySlot, UUID deviceId) implements CustomPacketPayload {
   public static final Type<MP4InventoryDeviceIdPacket> TYPE = new Type(NetworkPayloadIds.id("mp4_inventory_device_id"));
   public static final StreamCodec<RegistryFriendlyByteBuf, MP4InventoryDeviceIdPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MP4InventoryDeviceIdPacket>() {
      public MP4InventoryDeviceIdPacket decode(RegistryFriendlyByteBuf buffer) {
         return new MP4InventoryDeviceIdPacket(buffer.readInt(), buffer.readUUID());
      }

      public void encode(RegistryFriendlyByteBuf buffer, MP4InventoryDeviceIdPacket packet) {
         buffer.writeInt(packet.inventorySlot());
         buffer.writeUUID(packet.deviceId());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(MP4InventoryDeviceIdPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> MP4Client.receiveInventoryDeviceId(payload.inventorySlot(), payload.deviceId()));
   }
}
