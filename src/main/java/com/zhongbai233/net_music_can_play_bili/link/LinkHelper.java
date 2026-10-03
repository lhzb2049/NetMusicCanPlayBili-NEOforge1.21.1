package com.zhongbai233.net_music_can_play_bili.link;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class LinkHelper {
   public static final String LINK_X = "linked_x";
   public static final String LINK_Y = "linked_y";
   public static final String LINK_Z = "linked_z";
   public static final String LINK_DIMENSION = "linked_dimension";
   public static final String LINK_SOURCE_KIND = "linked_source_kind";

   private LinkHelper() {
   }

   public static void writeLinkToItem(ItemStack stack, BlockPos targetPos) {
      CompoundTag tag = new CompoundTag();
      tag.putInt("linked_x", targetPos.getX());
      tag.putInt("linked_y", targetPos.getY());
      tag.putInt("linked_z", targetPos.getZ());
      stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> existing.update(existingTag -> existingTag.merge(tag)));
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
   }

   public static void writeControlConsoleLinkToItem(ItemStack stack, BlockPos targetPos, String dimension, LinkHelper.ControlConsoleSourceKind sourceKind) {
      writeLinkToItem(stack, targetPos);
      CompoundTag tag = new CompoundTag();
      tag.putString("linked_dimension", dimension);
      tag.putString("linked_source_kind", sourceKind.name());
      stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> existing.update(existingTag -> existingTag.merge(tag)));
   }

   @Nullable
   public static LinkHelper.ControlConsoleLink readControlConsoleLinkFromItem(ItemStack stack) {
      BlockPos pos = readLinkFromItem(stack);
      if (pos == null) {
         return null;
      } else {
         CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
         CompoundTag tag = customData != null ? customData.copyTag() : new CompoundTag();
         String dimension = tag.getString("linked_dimension");
         String kindName = tag.getString("linked_source_kind");
         if (!dimension.isBlank() && !kindName.isBlank()) {
            try {
               return new LinkHelper.ControlConsoleLink(pos, dimension, LinkHelper.ControlConsoleSourceKind.valueOf(kindName), false);
            } catch (IllegalArgumentException var7) {
               return null;
            }
         } else {
            return new LinkHelper.ControlConsoleLink(pos, null, null, true);
         }
      }
   }

   @Nullable
   public static BlockPos readLinkFromItem(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         CompoundTag tag = customData.copyTag();
         return !tag.contains("linked_x") ? null : new BlockPos(tag.getInt("linked_x"), tag.getInt("linked_y"), tag.getInt("linked_z"));
      } else {
         return null;
      }
   }

   public static void clearLinkFromItem(ItemStack stack) {
      stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> existing.update(tag -> {
         tag.remove("linked_x");
         tag.remove("linked_y");
         tag.remove("linked_z");
         tag.remove("linked_dimension");
         tag.remove("linked_source_kind");
      }));
      CustomData remaining = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (remaining != null && remaining.isEmpty()) {
         stack.remove(DataComponents.CUSTOM_DATA);
      }

      stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
   }

   public static void saveLinkToBE(CompoundTag output, @Nullable BlockPos pos, String hasKey, String xKey, String yKey, String zKey) {
      output.putBoolean(hasKey, pos != null);
      if (pos != null) {
         output.putInt(xKey, pos.getX());
         output.putInt(yKey, pos.getY());
         output.putInt(zKey, pos.getZ());
      }
   }

   @Nullable
   public static BlockPos loadLinkFromBE(CompoundTag input, String hasKey, String xKey, String yKey, String zKey) {
      return !getBooleanOr(input, hasKey, false) ? null : new BlockPos(getIntOr(input, xKey, 0), getIntOr(input, yKey, 0), getIntOr(input, zKey, 0));
   }

   public static int getIntOr(CompoundTag tag, String key, int fallback) {
      return tag.contains(key, 3) ? tag.getInt(key) : fallback;
   }

   public static long getLongOr(CompoundTag tag, String key, long fallback) {
      return tag.contains(key, 4) ? tag.getLong(key) : fallback;
   }

   public static float getFloatOr(CompoundTag tag, String key, float fallback) {
      return tag.contains(key, 5) ? tag.getFloat(key) : fallback;
   }

   public static double getDoubleOr(CompoundTag tag, String key, double fallback) {
      return tag.contains(key, 6) ? tag.getDouble(key) : fallback;
   }

   public static boolean getBooleanOr(CompoundTag tag, String key, boolean fallback) {
      return tag.contains(key, 1) ? tag.getBoolean(key) : fallback;
   }

   public static String getStringOr(CompoundTag tag, String key, String fallback) {
      return tag.contains(key, 8) ? tag.getString(key) : fallback;
   }

   public static CompoundTag childOrEmpty(CompoundTag tag, String key) {
      return tag.contains(key, 10) ? tag.getCompound(key) : new CompoundTag();
   }

   public static ListTag childrenListOrEmpty(CompoundTag tag, String key) {
      return tag.contains(key, 9) ? tag.getList(key, 10) : new ListTag();
   }

   public record ControlConsoleLink(BlockPos pos, String dimension, LinkHelper.ControlConsoleSourceKind sourceKind, boolean legacy) {
   }

   public static enum ControlConsoleSourceKind {
      TURNTABLE,
      LIVE_STREAMER;
   }
}
