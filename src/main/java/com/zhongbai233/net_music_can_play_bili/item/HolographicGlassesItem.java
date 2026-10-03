package com.zhongbai233.net_music_can_play_bili.item;

import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public class HolographicGlassesItem extends Item implements Equipable {
   public static final int MAX_BOUND_MEDIA = 4;
   public static final int PERSISTENCE_SCHEMA_VERSION = 1;
   private static final String DATA_SCHEMA_VERSION = "holographic_glasses_schema_version";
   private static final String DATA_MP4_SCREENS = "holographic_glasses_mp4_screens";
   private static final String DATA_SCREEN_DISTANCE = "holographic_screen_distance";
   private static final String DATA_SCREEN_OFFSET_X = "holographic_screen_offset_x";
   private static final String DATA_SCREEN_OFFSET_Y = "holographic_screen_offset_y";
   private static final String DATA_SCREEN_HEIGHT = "holographic_screen_height";
   private static final String DATA_SCREEN_ASPECT = "holographic_screen_aspect";
   private static final String DATA_SCREEN_ROLL = "holographic_screen_roll";

   public HolographicGlassesItem(Properties properties) {
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
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.holographic_glasses.equipped"));
            return InteractionResultHolder.success(stack);
         } else {
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.holographic_glasses.equip_slot_occupied"));
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

   public static boolean addOrUpdateBoundMedia(ItemStack stack, MediaBindingData.MediaSource source) {
      if (!stack.isEmpty() && HolographicGlassesAbility.has(stack) && source != null) {
         List<HolographicGlassesItem.ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));

         for (HolographicGlassesItem.ScreenBinding binding : bindings) {
            if (source.equals(binding.source())) {
               writeScreenBindings(stack, bindings);
               return true;
            }
         }

         if (bindings.size() >= 4) {
            return false;
         } else {
            bindings.add(new HolographicGlassesItem.ScreenBinding(source, defaultScreenConfig(bindings.size())));
            writeScreenBindings(stack, bindings);
            return true;
         }
      } else {
         return false;
      }
   }

   public static List<UUID> readBoundMp4s(ItemStack stack) {
      return readBoundMediaDevices(stack);
   }

   public static List<UUID> readBoundMediaDevices(ItemStack stack) {
      List<UUID> result = new ArrayList<>();

      for (HolographicGlassesItem.ScreenBinding binding : readScreenBindings(stack)) {
         UUID deviceId = binding.deviceId();
         if (deviceId != null) {
            result.add(deviceId);
         }
      }

      return List.copyOf(result);
   }

   public static boolean boundToMp4(ItemStack stack, UUID deviceId) {
      return boundToMediaDevice(stack, deviceId);
   }

   public static boolean boundToMediaDevice(ItemStack stack, UUID deviceId) {
      return deviceId != null && readBoundMediaDevices(stack).contains(deviceId);
   }

   public static boolean boundToMedia(ItemStack stack, MediaBindingData.MediaSource source) {
      if (!stack.isEmpty() && HolographicGlassesAbility.has(stack) && source != null) {
         for (HolographicGlassesItem.ScreenBinding binding : readScreenBindings(stack)) {
            if (source.equals(binding.source())) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static int clearAllBoundMedia(ItemStack stack) {
      if (!stack.isEmpty() && HolographicGlassesAbility.has(stack)) {
         int count = readScreenBindings(stack).size();
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.remove("holographic_glasses_mp4_screens")));
         return count;
      } else {
         return 0;
      }
   }

   public static boolean clearBoundMp4(ItemStack stack, UUID deviceId) {
      return clearBoundMediaDevice(stack, deviceId);
   }

   public static boolean clearBoundMediaDevice(ItemStack stack, UUID deviceId) {
      if (!stack.isEmpty() && HolographicGlassesAbility.has(stack) && deviceId != null) {
         List<HolographicGlassesItem.ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
         boolean removed = bindings.removeIf(binding -> deviceId.equals(binding.deviceId()));
         if (removed) {
            writeScreenBindings(stack, bindings);
         }

         return removed;
      } else {
         return false;
      }
   }

   public static boolean clearBoundMedia(ItemStack stack, MediaBindingData.MediaSource source) {
      if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || source == null) {
         return false;
      } else if (source.isMediaDevice()) {
         return clearBoundMediaDevice(stack, source.deviceId());
      } else {
         List<HolographicGlassesItem.ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
         boolean removed = bindings.removeIf(binding -> source.equals(binding.source()));
         if (removed) {
            writeScreenBindings(stack, bindings);
         }

         return removed;
      }
   }

   public static HolographicGlassesItem.ScreenConfig defaultScreenConfig() {
      return defaultScreenConfig(0);
   }

   public static HolographicGlassesItem.ScreenConfig defaultScreenConfig(int index) {
      float offsetX = switch (Math.max(0, index)) {
         case 1 -> -0.85F;
         case 2 -> 0.85F;
         case 3 -> 0.0F;
         default -> 0.0F;
      };
      float offsetY = Math.max(0, index) == 3 ? 0.75F : 0.05F;
      return new HolographicGlassesItem.ScreenConfig(2.2F, offsetX, offsetY, 0.75F, 1.7777778F, 0.0F);
   }

   public static HolographicGlassesItem.ScreenConfig readScreenConfig(ItemStack stack) {
      List<HolographicGlassesItem.ScreenBinding> bindings = readScreenBindings(stack);
      return !bindings.isEmpty() ? bindings.getFirst().config() : defaultScreenConfig();
   }

   public static void writeScreenConfig(ItemStack stack, HolographicGlassesItem.ScreenConfig config) {
      writeScreenConfigs(stack, List.of(config));
   }

   public static List<HolographicGlassesItem.ScreenBinding> readScreenBindings(ItemStack stack) {
      List<HolographicGlassesItem.ScreenBinding> result = new ArrayList<>();
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         CompoundTag tag = customData.copyTag();

         for (Tag entry : LinkHelper.childrenListOrEmpty(tag, "holographic_glasses_mp4_screens")) {
            if (entry instanceof CompoundTag compound) {
               MediaBindingData.MediaSource source = MediaBindingData.readSource(compound);
               if (source != null) {
                  result.add(new HolographicGlassesItem.ScreenBinding(source, readConfig(compound, defaultScreenConfig(result.size()))));
                  if (result.size() >= 4) {
                     break;
                  }
               }
            }
         }
      }

      return List.copyOf(result);
   }

   public static int readPersistenceSchemaVersion(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return customData != null && !customData.isEmpty() ? LinkHelper.getIntOr(customData.copyTag(), "holographic_glasses_schema_version", 1) : 1;
   }

   public static List<HolographicGlassesItem.ScreenConfig> readScreenConfigs(ItemStack stack) {
      List<HolographicGlassesItem.ScreenConfig> result = new ArrayList<>();
      List<HolographicGlassesItem.ScreenBinding> bindings = readScreenBindings(stack);
      if (!bindings.isEmpty()) {
         for (HolographicGlassesItem.ScreenBinding binding : bindings) {
            result.add(binding.config());
         }
      }

      return List.copyOf(result);
   }

   public static void writeScreenConfigs(ItemStack stack, List<HolographicGlassesItem.ScreenConfig> configs) {
      if (!stack.isEmpty() && HolographicGlassesAbility.has(stack) && configs != null) {
         List<HolographicGlassesItem.ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
         int count = Math.min(Math.min(bindings.size(), configs.size()), 4);
         List<HolographicGlassesItem.ScreenBinding> updated = new ArrayList<>();

         for (int i = 0; i < count; i++) {
            updated.add(new HolographicGlassesItem.ScreenBinding(bindings.get(i).source(), configs.get(i).clamped()));
         }

         if (!updated.isEmpty()) {
            writeScreenBindings(stack, updated);
         }
      }
   }

   private static HolographicGlassesItem.ScreenConfig readConfig(CompoundTag tag, HolographicGlassesItem.ScreenConfig defaults) {
      return new HolographicGlassesItem.ScreenConfig(
            LinkHelper.getFloatOr(tag, "holographic_screen_distance", defaults.distance()),
            LinkHelper.getFloatOr(tag, "holographic_screen_offset_x", defaults.offsetX()),
            LinkHelper.getFloatOr(tag, "holographic_screen_offset_y", defaults.offsetY()),
            LinkHelper.getFloatOr(tag, "holographic_screen_height", defaults.height()),
            LinkHelper.getFloatOr(tag, "holographic_screen_aspect", defaults.aspect()),
            LinkHelper.getFloatOr(tag, "holographic_screen_roll", defaults.roll())
         )
         .clamped();
   }

   private static void writeScreenBindings(ItemStack stack, List<HolographicGlassesItem.ScreenBinding> bindings) {
      ListTag list = new ListTag();
      int count = Math.min(bindings.size(), 4);

      for (int i = 0; i < count; i++) {
         HolographicGlassesItem.ScreenBinding binding = bindings.get(i);
         CompoundTag entry = new CompoundTag();
         MediaBindingData.writeSource(entry, binding.source());
         writeConfig(entry, binding.config().clamped());
         list.add(entry);
      }

      stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
         tag.putInt("holographic_glasses_schema_version", 1);
         tag.put("holographic_glasses_mp4_screens", list);
      }));
   }

   private static void writeConfig(CompoundTag tag, HolographicGlassesItem.ScreenConfig config) {
      tag.putFloat("holographic_screen_distance", config.distance());
      tag.putFloat("holographic_screen_offset_x", config.offsetX());
      tag.putFloat("holographic_screen_offset_y", config.offsetY());
      tag.putFloat("holographic_screen_height", config.height());
      tag.putFloat("holographic_screen_aspect", config.aspect());
      tag.putFloat("holographic_screen_roll", config.roll());
   }

   public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag flag) {
      tooltipComponents.add(Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.protection").withStyle(ChatFormatting.GRAY));
      List<HolographicGlassesItem.ScreenBinding> bindings = readScreenBindings(stack);
      if (!bindings.isEmpty()) {
         MediaBindingData.MediaSource source = bindings.getFirst().source();
         if (source != null) {
            tooltipComponents.add(
               Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.media", new Object[]{source.shortName()})
                  .withStyle(ChatFormatting.GRAY)
            );
         }

         int count = bindings.size();
         if (count > 1) {
            tooltipComponents.add(
               Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.media_count", new Object[]{count}).withStyle(ChatFormatting.GRAY)
            );
         }
      }
   }

   public record ScreenBinding(MediaBindingData.MediaSource source, HolographicGlassesItem.ScreenConfig config) {
      public UUID deviceId() {
         return this.source != null && this.source.isMediaDevice() ? this.source.deviceId() : null;
      }
   }

   public record ScreenConfig(float distance, float offsetX, float offsetY, float height, float aspect, float roll) {
      private HolographicGlassesItem.ScreenConfig clamped() {
         return new HolographicGlassesItem.ScreenConfig(
            HolographicScreenSettings.clampDistance(this.distance),
            HolographicScreenSettings.clampOffsetX(this.offsetX),
            HolographicScreenSettings.clampOffsetY(this.offsetY),
            HolographicScreenSettings.clampHeight(this.height),
            HolographicScreenSettings.clampAspect(this.aspect),
            HolographicScreenSettings.clampRoll(this.roll)
         );
      }
   }
}
