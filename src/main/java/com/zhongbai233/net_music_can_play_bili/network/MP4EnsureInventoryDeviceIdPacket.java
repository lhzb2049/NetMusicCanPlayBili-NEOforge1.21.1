package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MP4EnsureInventoryDeviceIdPacket(int inventorySlot) implements CustomPacketPayload {
   public static final Type<MP4EnsureInventoryDeviceIdPacket> TYPE = new Type(NetworkPayloadIds.id("mp4_ensure_inventory_device_id"));
   public static final StreamCodec<RegistryFriendlyByteBuf, MP4EnsureInventoryDeviceIdPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, MP4EnsureInventoryDeviceIdPacket>() {
      public MP4EnsureInventoryDeviceIdPacket decode(RegistryFriendlyByteBuf buffer) {
         return new MP4EnsureInventoryDeviceIdPacket(buffer.readInt());
      }

      public void encode(RegistryFriendlyByteBuf buffer, MP4EnsureInventoryDeviceIdPacket packet) {
         buffer.writeInt(packet.inventorySlot());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(MP4EnsureInventoryDeviceIdPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player) {
         if (NetworkRateLimiter.allow(player.getUUID(), "mp4_ensure_inventory_device_id", 12)) {
            int slot = payload.inventorySlot();
            if (slot >= 0 && slot < Math.min(9, player.getInventory().getContainerSize())) {
               ItemStack stack = player.getInventory().getItem(slot);
               if (stack.getItem() instanceof MP4Item) {
                  ServerLevel level = (ServerLevel)player.level();
                  UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
                  if (deviceId != null) {
                     PacketDistributor.sendToPlayer(player, new MP4InventoryDeviceIdPacket(slot, deviceId), new CustomPacketPayload[0]);
                     PacketDistributor.sendToPlayer(
                        player,
                        MP4DeviceStateMirrorPacket.fromEntry(
                           deviceId, MP4DeviceStateStore.getOrCreate(level, deviceId, stack), AudioLinkIndex.hasHeadphoneLinkedToMp4(deviceId)
                        ),
                        new CustomPacketPayload[0]
                     );
                  }
               }
            }
         }
      }
   }
}
