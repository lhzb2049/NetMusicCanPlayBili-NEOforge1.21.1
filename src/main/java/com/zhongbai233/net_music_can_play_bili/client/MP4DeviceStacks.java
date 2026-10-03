package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

final class MP4DeviceStacks {
   private MP4DeviceStacks() {
   }

   static boolean forEachHotbarAndOffhand(Player player, MP4DeviceStacks.Visitor visitor) {
      if (player == null) {
         return false;
      } else if (visitDeviceStack(player.getOffhandItem(), visitor)) {
         return true;
      } else {
         Inventory inventory = player.getInventory();
         int hotbarSlots = Math.min(9, inventory.getContainerSize());

         for (int slot = 0; slot < hotbarSlots; slot++) {
            if (visitDeviceStack(inventory.getItem(slot), visitor)) {
               return true;
            }
         }

         return false;
      }
   }

   static void addHotbarAndOffhandDeviceIds(Player player, Set<UUID> deviceIds) {
      if (deviceIds != null) {
         forEachHotbarAndOffhand(player, stack -> {
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId != null) {
               deviceIds.add(deviceId);
            }

            return false;
         });
      }
   }

   private static boolean visitDeviceStack(ItemStack stack, MP4DeviceStacks.Visitor visitor) {
      return stack.getItem() instanceof MP4Item && visitor.visit(stack);
   }

   @FunctionalInterface
   interface Visitor {
      boolean visit(ItemStack var1);
   }
}
