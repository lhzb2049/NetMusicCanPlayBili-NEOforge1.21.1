package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClearEquippedBindingPacket() implements CustomPacketPayload {
   public static final Type<ClearEquippedBindingPacket> TYPE = new Type(NetworkPayloadIds.id("clear_equipped_binding"));
   public static final StreamCodec<RegistryFriendlyByteBuf, ClearEquippedBindingPacket> STREAM_CODEC = StreamCodec.unit(new ClearEquippedBindingPacket());

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ClearEquippedBindingPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player) {
         boolean[] var9 = new boolean[]{false, false};
         EquippedMediaItems.forEachEquipped(player, stack -> {
            var9[0] |= HolographicGlassesAbility.has(stack);
            var9[1] |= HeadphoneAbility.has(stack);
         });
         boolean holographic = var9[0];
         boolean headphones = var9[1];
         if (!holographic && !headphones) {
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.equipment_bindings.need_equipped"));
         } else {
            MediaBindingCleanupService.ClearEquipmentResult result = MediaBindingCleanupService.clearEquippedHeadBindings(player);
            int glassesCount = result.holographicCount();
            int headphoneCount = result.headphoneCount();
            if (holographic && headphones) {
               player.sendSystemMessage(
                  glassesCount <= 0 && headphoneCount <= 0
                     ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_both")
                     : Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_both", new Object[]{glassesCount, headphoneCount})
               );
            } else if (holographic) {
               player.sendSystemMessage(
                  glassesCount > 0
                     ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_glasses", new Object[]{glassesCount})
                     : Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_glasses")
               );
            } else {
               player.sendSystemMessage(
                  headphoneCount > 0
                     ? Component.translatable("message.net_music_can_play_bili.equipment_bindings.cleared_headphones", new Object[]{headphoneCount})
                     : Component.translatable("message.net_music_can_play_bili.equipment_bindings.none_headphones")
               );
            }
         }
      }
   }
}
