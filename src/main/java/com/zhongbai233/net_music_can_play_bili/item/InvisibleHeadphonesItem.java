package com.zhongbai233.net_music_can_play_bili.item;

import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.level.Level;

public class InvisibleHeadphonesItem extends Item implements Equipable {
   public InvisibleHeadphonesItem(Properties properties) {
      super(properties.stacksTo(1));
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (hand != InteractionHand.MAIN_HAND) {
         return InteractionResultHolder.pass(stack);
      } else if (level.isClientSide()) {
         return InteractionResultHolder.success(stack);
      } else {
         ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
         if (head.isEmpty()) {
            player.setItemSlot(EquipmentSlot.HEAD, stack.copyWithCount(1));
            stack.shrink(1);
            if (player instanceof ServerPlayer serverPlayer) {
               AudioLinkIndex.updatePlayerHeadphones(serverPlayer);
            }

            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.headphones.equipped"));
            return InteractionResultHolder.success(stack);
         } else {
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.headphones.equip_slot_occupied"));
            return InteractionResultHolder.pass(stack);
         }
      }
   }

   public EquipmentSlot getEquipmentSlot() {
      return EquipmentSlot.HEAD;
   }

   public Holder<SoundEvent> getEquipSound() {
      return SoundEvents.ARMOR_EQUIP_GENERIC;
   }

   public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag flag) {
      BlockPos turntable = AudioLinkData.readHeadphoneTurntable(stack);
      if (turntable != null) {
         tooltipComponents.add(
            Component.translatable("tooltip.net_music_can_play_bili.headphones.turntable", new Object[]{turntable.getX(), turntable.getY(), turntable.getZ()})
               .withStyle(ChatFormatting.GRAY)
         );
      }

      UUID mp4 = AudioLinkData.readHeadphoneMediaDevice(stack);
      if (mp4 != null) {
         String shortId = mp4.toString();
         if (shortId.length() > 8) {
            shortId = shortId.substring(0, 8);
         }

         tooltipComponents.add(
            Component.translatable("tooltip.net_music_can_play_bili.headphones.media_device", new Object[]{shortId}).withStyle(ChatFormatting.GRAY)
         );
      }
   }
}
