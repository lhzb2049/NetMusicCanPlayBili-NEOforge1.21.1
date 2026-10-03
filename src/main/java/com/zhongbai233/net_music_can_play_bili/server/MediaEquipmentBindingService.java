package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData;
import com.zhongbai233.net_music_can_play_bili.network.ServerMediaPlayback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class MediaEquipmentBindingService {
   private MediaEquipmentBindingService() {
   }

   public static MediaEquipmentBindingService.BindResult bind(ServerPlayer player, ItemStack equipment, MediaBindingData.MediaSource source) {
      if (player != null && !equipment.isEmpty() && source != null) {
         return switch (source.kind()) {
            case MP4, PAD -> bindMediaDevice(player, equipment, source);
            case TURNTABLE -> bindTurntable(player, equipment, source);
            case VIDEO_PROJECTOR -> MediaEquipmentBindingService.BindResult.unhandled();
         };
      } else {
         return MediaEquipmentBindingService.BindResult.unhandled();
      }
   }

   private static MediaEquipmentBindingService.BindResult bindMediaDevice(ServerPlayer player, ItemStack equipment, MediaBindingData.MediaSource source) {
      boolean bound = false;
      boolean handled = false;
      if (HeadphoneAbility.has(equipment)) {
         handled = true;
         AudioLinkData.writeHeadphoneMediaDevice(equipment, source.deviceId());
         AudioLinkIndex.updatePlayerHeadphones(player);
         player.sendSystemMessage(
            Component.translatable(
                  source.isPad() ? "message.net_music_can_play_bili.headphones.pad_linked" : "message.net_music_can_play_bili.headphones.mp4_linked"
               )
               .withStyle(ChatFormatting.GOLD)
         );
         bound = true;
      }

      if (HolographicGlassesAbility.has(equipment)) {
         handled = true;
         boolean linked = HolographicGlassesItem.addOrUpdateBoundMedia(equipment, source);
         player.sendSystemMessage(
            linked
               ? Component.translatable(
                     "message.net_music_can_play_bili.holographic_glasses.media_linked_count",
                     new Object[]{HolographicGlassesItem.readScreenBindings(equipment).size(), 4}
                  )
                  .withStyle(ChatFormatting.GOLD)
               : Component.translatable("message.net_music_can_play_bili.holographic_glasses.media_slots_full").withStyle(ChatFormatting.RED)
         );
         if (linked) {
            ServerMediaPlayback.stopExternalPlaybackForLinkedHeadphones(player, source.deviceId());
            bound = true;
         }
      }

      return finish(player, bound, handled);
   }

   private static MediaEquipmentBindingService.BindResult bindTurntable(ServerPlayer player, ItemStack equipment, MediaBindingData.MediaSource source) {
      if (source.pos() == null) {
         return MediaEquipmentBindingService.BindResult.unhandled();
      } else {
         boolean bound = false;
         boolean handled = false;
         if (HeadphoneAbility.has(equipment)) {
            handled = true;
            AudioLinkData.writeHeadphoneTurntable(equipment, source.pos());
            AudioLinkIndex.updatePlayerHeadphones(player);
            player.sendSystemMessage(
               Component.translatable(
                     "message.net_music_can_play_bili.headphones.turntable_linked", new Object[]{source.pos().getX(), source.pos().getY(), source.pos().getZ()}
                  )
                  .withStyle(ChatFormatting.GOLD)
            );
            bound = true;
         }

         if (HolographicGlassesAbility.has(equipment)) {
            handled = true;
            bound |= bindHolographic(player, equipment, source, "message.net_music_can_play_bili.holographic_glasses.turntable_linked_count");
         }

         return finish(player, bound, handled);
      }
   }

   private static boolean bindHolographic(ServerPlayer player, ItemStack equipment, MediaBindingData.MediaSource source, String successKey) {
      if (source.pos() == null) {
         return false;
      } else {
         boolean linked = HolographicGlassesItem.addOrUpdateBoundMedia(equipment, source);
         player.sendSystemMessage(
            linked
               ? Component.translatable(
                     successKey,
                     new Object[]{
                        source.pos().getX(), source.pos().getY(), source.pos().getZ(), HolographicGlassesItem.readScreenBindings(equipment).size(), 4
                     }
                  )
                  .withStyle(ChatFormatting.GOLD)
               : Component.translatable("message.net_music_can_play_bili.holographic_glasses.media_slots_full").withStyle(ChatFormatting.RED)
         );
         return linked;
      }
   }

   private static MediaEquipmentBindingService.BindResult finish(ServerPlayer player, boolean bound, boolean handled) {
      if (!bound && !handled) {
         player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.media_tool.need_equipment_input").withStyle(ChatFormatting.RED));
      }

      return new MediaEquipmentBindingService.BindResult(bound, handled);
   }

   public record BindResult(boolean bound, boolean handledAbility) {
      private static MediaEquipmentBindingService.BindResult unhandled() {
         return new MediaEquipmentBindingService.BindResult(false, false);
      }
   }
}
