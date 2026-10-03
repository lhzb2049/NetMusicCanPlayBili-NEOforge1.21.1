package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class MP4DeviceIdentity {
   private MP4DeviceIdentity() {
   }

   public static UUID getOrCreateUnique(ServerLevel level, ServerPlayer player, ItemStack stack) {
      if (!(stack.getItem() instanceof MP4Item)) {
         return null;
      } else {
         UUID deviceId = MP4Item.readDeviceId(stack);
         if (player != null && deviceId != null && isDuplicateOccurrence(player, stack, deviceId)) {
            deviceId = replaceDeviceId(level, stack);
            markInventoryChanged(player);
            normalizeDuplicateDeviceIds(level, player);
            deviceId = MP4Item.readDeviceId(stack);
         }

         if (deviceId == null) {
            deviceId = MP4Item.getOrCreateDeviceId(stack);
            initializeDevice(level, deviceId, stack);
            markInventoryChanged(player);
         }

         initializeDevice(level, deviceId, stack);
         if (level != null && player != null) {
            MP4DeviceLocationIndex.recordPlayer(level, player, deviceId);
         }

         return deviceId;
      }
   }

   public static UUID replaceDeviceId(ServerLevel level, ItemStack stack) {
      if (!(stack.getItem() instanceof MP4Item)) {
         return null;
      } else {
         UUID replacement = UUID.randomUUID();
         MP4Item.writeDeviceId(stack, replacement);
         initializeDevice(level, replacement, stack);
         return replacement;
      }
   }

   public static UUID ensureUniqueForContainerSlot(ServerLevel level, ServerPlayer player, ItemStack stack) {
      UUID deviceId = getOrCreateUnique(level, player, stack);
      if (deviceId == null) {
         return null;
      } else {
         UUID afterNormalize = MP4Item.readDeviceId(stack);
         return afterNormalize != null ? afterNormalize : deviceId;
      }
   }

   public static boolean normalizeDuplicateDeviceIds(ServerLevel level, ServerPlayer player) {
      if (player == null) {
         return false;
      } else {
         Set<UUID> seen = new HashSet<>();
         boolean changed = normalizeStack(level, player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY, seen);
         Inventory inventory = player.getInventory();

         for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            changed |= normalizeStack(level, inventory.getItem(slot), seen);
         }

         if (changed) {
            markInventoryChanged(player);
         }

         return changed;
      }
   }

   private static boolean isDuplicateOccurrence(ServerPlayer player, ItemStack target, UUID deviceId) {
      if (deviceId == null) {
         return false;
      } else {
         ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
         if (carried == target) {
            return false;
         } else if (carried.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(carried))) {
            return target != carried;
         } else {
            Inventory inventory = player.getInventory();

            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
               ItemStack stack = inventory.getItem(slot);
               if (stack == target) {
                  return false;
               }

               if (stack.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(stack))) {
                  return true;
               }
            }

            return false;
         }
      }
   }

   private static boolean normalizeStack(ServerLevel level, ItemStack stack, Set<UUID> seen) {
      if (!(stack.getItem() instanceof MP4Item)) {
         return false;
      } else {
         UUID deviceId = MP4Item.readDeviceId(stack);
         if (deviceId == null) {
            return false;
         } else if (seen.add(deviceId)) {
            return false;
         } else {
            replaceDeviceId(level, stack);
            return true;
         }
      }
   }

   private static void initializeDevice(ServerLevel level, UUID deviceId, ItemStack stack) {
      if (level != null && deviceId != null && stack.getItem() instanceof MP4Item) {
         MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
         MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
      }
   }

   private static void markInventoryChanged(ServerPlayer player) {
      if (player != null) {
         player.getInventory().setChanged();
         if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
         }
      }
   }
}
