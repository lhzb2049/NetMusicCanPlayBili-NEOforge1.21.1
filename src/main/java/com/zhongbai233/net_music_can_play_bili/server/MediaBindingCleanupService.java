package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class MediaBindingCleanupService {
   private MediaBindingCleanupService() {
   }

   public static MediaBindingCleanupService.ClearEquipmentResult clearEquipmentBindings(ServerPlayer player, ItemStack equipment) {
      if (player != null && !equipment.isEmpty()) {
         boolean holographic = HolographicGlassesAbility.has(equipment);
         boolean headphones = HeadphoneAbility.has(equipment);
         int glassesCount = holographic ? HolographicGlassesItem.clearAllBoundMedia(equipment) : 0;
         int headphoneCount = headphones ? AudioLinkData.clearHeadphoneLinks(equipment) : 0;
         if (headphones) {
            AudioLinkIndex.updatePlayerHeadphones(player);
         }

         if (glassesCount > 0 || headphoneCount > 0) {
            player.getInventory().setChanged();
            if (player.containerMenu != null) {
               player.containerMenu.broadcastChanges();
            }
         }

         return new MediaBindingCleanupService.ClearEquipmentResult(glassesCount, headphoneCount);
      } else {
         return new MediaBindingCleanupService.ClearEquipmentResult(0, 0);
      }
   }

   public static MediaBindingCleanupService.ClearEquipmentResult clearEquippedHeadBindings(ServerPlayer player) {
      if (player == null) {
         return new MediaBindingCleanupService.ClearEquipmentResult(0, 0);
      } else {
         MediaBindingCleanupService.MutableCounts counts = new MediaBindingCleanupService.MutableCounts();
         EquippedMediaItems.forEachEquipped(player, equipment -> {
            MediaBindingCleanupService.ClearEquipmentResult result = clearEquipmentBindings(player, equipment);
            counts.holographicCount = counts.holographicCount + result.holographicCount();
            counts.headphoneCount = counts.headphoneCount + result.headphoneCount();
         });
         return new MediaBindingCleanupService.ClearEquipmentResult(counts.holographicCount, counts.headphoneCount);
      }
   }

   public static MediaBindingCleanupService.TargetBindingStats countTargetBindings(ServerPlayer viewer, MediaBindingData.MediaSource source) {
      if (viewer != null && source != null) {
         MinecraftServer server = viewer.level().getServer();
         if (server == null) {
            return MediaBindingCleanupService.TargetBindingStats.EMPTY;
         } else {
            Set<String> headphoneKeys = new HashSet<>();
            Set<String> holographicKeys = new HashSet<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               scanPlayerStacks(player, stack -> {
                  if (isHeadphoneBoundTo(stack, source)) {
                     headphoneKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                  }

                  if (HolographicGlassesItem.boundToMedia(stack, source)) {
                     holographicKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                  }
               });
            }

            return new MediaBindingCleanupService.TargetBindingStats(headphoneKeys.size(), holographicKeys.size());
         }
      } else {
         return MediaBindingCleanupService.TargetBindingStats.EMPTY;
      }
   }

   public static MediaBindingCleanupService.TargetBindingStats clearTargetBindings(ServerPlayer actor, MediaBindingData.MediaSource source) {
      if (actor != null && source != null) {
         MinecraftServer server = actor.level().getServer();
         if (server == null) {
            return MediaBindingCleanupService.TargetBindingStats.EMPTY;
         } else {
            Set<String> headphoneKeys = new HashSet<>();
            Set<String> holographicKeys = new HashSet<>();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
               boolean changed = false;
               MediaBindingCleanupService.MutableBoolean headphonesChanged = new MediaBindingCleanupService.MutableBoolean();
               MediaBindingCleanupService.MutableBoolean glassesChanged = new MediaBindingCleanupService.MutableBoolean();
               scanPlayerStacks(player, stack -> {
                  if (clearHeadphoneBoundTo(stack, source)) {
                     headphonesChanged.value = true;
                     headphoneKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                  }

                  if (HolographicGlassesItem.clearBoundMedia(stack, source)) {
                     glassesChanged.value = true;
                     holographicKeys.add(player.getUUID() + ":" + System.identityHashCode(stack));
                  }
               });
               changed |= headphonesChanged.value || glassesChanged.value;
               if (headphonesChanged.value) {
                  AudioLinkIndex.updatePlayerHeadphones(player);
               }

               if (changed) {
                  player.getInventory().setChanged();
                  if (player.containerMenu != null) {
                     player.containerMenu.broadcastChanges();
                  }
               }
            }

            return new MediaBindingCleanupService.TargetBindingStats(headphoneKeys.size(), holographicKeys.size());
         }
      } else {
         return MediaBindingCleanupService.TargetBindingStats.EMPTY;
      }
   }

   public static MediaBindingData.MediaSource mp4Source(UUID deviceId) {
      return MediaBindingData.MediaSource.mp4(deviceId);
   }

   public static MediaBindingData.MediaSource padSource(UUID deviceId) {
      return MediaBindingData.MediaSource.pad(deviceId);
   }

   public static MediaBindingData.MediaSource turntableSource(Level level, BlockPos pos) {
      return level != null && pos != null ? MediaBindingData.MediaSource.turntable(level.dimension(), pos) : null;
   }

   private static boolean isHeadphoneBoundTo(ItemStack stack, MediaBindingData.MediaSource source) {
      if (!HeadphoneAbility.has(stack) || source == null) {
         return false;
      } else if (source.isMediaDevice()) {
         return AudioLinkData.headphoneLinkedToMediaDevice(stack, source.deviceId());
      } else {
         return !source.isTurntable() ? false : source.pos() != null && source.pos().equals(AudioLinkData.readHeadphoneTurntable(stack));
      }
   }

   private static boolean clearHeadphoneBoundTo(ItemStack stack, MediaBindingData.MediaSource source) {
      if (!isHeadphoneBoundTo(stack, source)) {
         return false;
      } else {
         if (source.isMediaDevice()) {
            AudioLinkData.clearHeadphoneMediaDevice(stack);
         } else {
            AudioLinkData.clearHeadphoneTurntable(stack);
         }

         return true;
      }
   }

   private static void scanPlayerStacks(ServerPlayer player, MediaBindingCleanupService.StackConsumer consumer) {
      if (player != null && consumer != null) {
         Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
         EquippedMediaItems.forEachEquipped(player, stack -> acceptOnce(seen, consumer, stack));
         ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
         acceptOnce(seen, consumer, carried);
         if (player.containerMenu != null) {
            for (Slot slot : player.containerMenu.slots) {
               acceptOnce(seen, consumer, slot.getItem());
            }
         }

         Inventory inventory = player.getInventory();

         for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            acceptOnce(seen, consumer, inventory.getItem(slot));
         }
      }
   }

   private static void acceptOnce(Set<ItemStack> seen, MediaBindingCleanupService.StackConsumer consumer, ItemStack stack) {
      if (!stack.isEmpty() && seen.add(stack)) {
         consumer.accept(stack);
      }
   }

   public record ClearEquipmentResult(int holographicCount, int headphoneCount) {
      public int total() {
         return this.holographicCount + this.headphoneCount;
      }
   }

   private static final class MutableBoolean {
      private boolean value;
   }

   private static final class MutableCounts {
      private int holographicCount;
      private int headphoneCount;
   }

   @FunctionalInterface
   private interface StackConsumer {
      void accept(ItemStack var1);
   }

   public record TargetBindingStats(int headphoneCount, int holographicCount) {
      public static final MediaBindingCleanupService.TargetBindingStats EMPTY = new MediaBindingCleanupService.TargetBindingStats(0, 0);

      public int total() {
         return this.headphoneCount + this.holographicCount;
      }
   }
}
