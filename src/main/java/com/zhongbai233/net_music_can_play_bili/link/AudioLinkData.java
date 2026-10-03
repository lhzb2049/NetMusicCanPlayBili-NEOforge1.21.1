package com.zhongbai233.net_music_can_play_bili.link;

import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class AudioLinkData {
   public static final double MP4_HEADPHONE_RANGE_SQUARED = 4096.0;
   private static final String HEADPHONE_TURNTABLE_X = "headphones_turntable_x";
   private static final String HEADPHONE_TURNTABLE_Y = "headphones_turntable_y";
   private static final String HEADPHONE_TURNTABLE_Z = "headphones_turntable_z";
   private static final String HEADPHONE_MP4 = "headphones_mp4";
   private static final String HEADPHONE_MEDIA_DEVICE = "headphones_media_device";

   private AudioLinkData() {
   }

   public static void writeHeadphoneTurntable(ItemStack stack, BlockPos pos) {
      if (!stack.isEmpty() && pos != null) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.putInt("headphones_turntable_x", pos.getX());
            tag.putInt("headphones_turntable_y", pos.getY());
            tag.putInt("headphones_turntable_z", pos.getZ());
         }));
      }
   }

   @Nullable
   public static BlockPos readHeadphoneTurntable(ItemStack stack) {
      CompoundTag tag = customTag(stack);
      return tag != null && tag.contains("headphones_turntable_x") && tag.contains("headphones_turntable_y") && tag.contains("headphones_turntable_z")
         ? new BlockPos(
            LinkHelper.getIntOr(tag, "headphones_turntable_x", 0),
            LinkHelper.getIntOr(tag, "headphones_turntable_y", 0),
            LinkHelper.getIntOr(tag, "headphones_turntable_z", 0)
         )
         : null;
   }

   public static void writeHeadphoneMp4(ItemStack stack, UUID deviceId) {
      writeHeadphoneMediaDevice(stack, deviceId);
   }

   public static void writeHeadphoneMediaDevice(ItemStack stack, UUID deviceId) {
      if (!stack.isEmpty() && deviceId != null) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.putString("headphones_mp4", deviceId.toString());
            tag.putString("headphones_media_device", deviceId.toString());
         }));
      }
   }

   @Nullable
   public static UUID readHeadphoneMp4(ItemStack stack) {
      return readHeadphoneMediaDevice(stack);
   }

   @Nullable
   public static UUID readHeadphoneMediaDevice(ItemStack stack) {
      CompoundTag tag = customTag(stack);
      String value = tag != null ? tag.getString("headphones_media_device") : "";
      if (value.isBlank() && tag != null) {
         value = tag.getString("headphones_mp4");
      }

      if (value.isBlank()) {
         return null;
      } else {
         try {
            return UUID.fromString(value);
         } catch (IllegalArgumentException var4) {
            return null;
         }
      }
   }

   public static void clearHeadphoneMp4(ItemStack stack) {
      clearHeadphoneMediaDevice(stack);
   }

   public static void clearHeadphoneMediaDevice(ItemStack stack) {
      if (!stack.isEmpty()) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.remove("headphones_mp4");
            tag.remove("headphones_media_device");
         }));
      }
   }

   public static void clearHeadphoneTurntable(ItemStack stack) {
      if (!stack.isEmpty()) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.remove("headphones_turntable_x");
            tag.remove("headphones_turntable_y");
            tag.remove("headphones_turntable_z");
         }));
      }
   }

   public static int clearHeadphoneLinks(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0;
      } else {
         int count = 0;
         if (readHeadphoneTurntable(stack) != null) {
            count++;
         }

         if (readHeadphoneMp4(stack) != null) {
            count++;
         }

         if (count <= 0) {
            return 0;
         } else {
            stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
               tag.remove("headphones_turntable_x");
               tag.remove("headphones_turntable_y");
               tag.remove("headphones_turntable_z");
               tag.remove("headphones_mp4");
               tag.remove("headphones_media_device");
            }));
            return count;
         }
      }
   }

   public static boolean headphoneLinkedToMp4(ItemStack stack, UUID deviceId) {
      return headphoneLinkedToMediaDevice(stack, deviceId);
   }

   public static boolean headphoneLinkedToMediaDevice(ItemStack stack, UUID deviceId) {
      return deviceId != null && deviceId.equals(readHeadphoneMediaDevice(stack));
   }

   @Nullable
   private static CompoundTag customTag(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return customData != null && !customData.isEmpty() ? customData.copyTag() : null;
   }
}
