package com.zhongbai233.net_music_can_play_bili.link;

import com.zhongbai233.net_music_can_play_bili.init.ModAttributes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public final class HolographicGlassesAbility {
   public static final ResourceLocation HOLOGRAPHIC_GLASSES_ID = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "holographic_glasses");
   public static final ResourceKey<Enchantment> HOLOGRAPHIC_GLASSES_KEY = ResourceKey.create(Registries.ENCHANTMENT, HOLOGRAPHIC_GLASSES_ID);
   public static final TagKey<Enchantment> HOLOGRAPHIC_GLASSES_ENCHANTMENT_TAG = TagKey.create(Registries.ENCHANTMENT, HOLOGRAPHIC_GLASSES_ID);

   private HolographicGlassesAbility() {
   }

   public static boolean has(ItemStack stack) {
      return stack.isEmpty() ? false : isHeadEquipment(stack) && hasHolographicAttribute(stack);
   }

   private static boolean isHeadEquipment(ItemStack stack) {
      Equipable equipable = Equipable.get(stack);
      return equipable != null && equipable.getEquipmentSlot() == EquipmentSlot.HEAD;
   }

   private static boolean hasHolographicAttribute(ItemStack stack) {
      boolean[] found = new boolean[]{false};
      stack.forEachModifier(EquipmentSlotGroup.HEAD, (attribute, modifier) -> {
         if (attribute.is(ModAttributes.HOLOGRAPHIC_GLASSES.getKey()) && modifier.amount() > 0.0) {
            found[0] = true;
         }
      });
      if (found[0]) {
         return true;
      } else {
         EnchantmentHelper.forEachModifier(stack, EquipmentSlotGroup.HEAD, (attribute, modifier) -> {
            if (attribute.is(ModAttributes.HOLOGRAPHIC_GLASSES.getKey()) && modifier.amount() > 0.0) {
               found[0] = true;
            }
         });
         return found[0];
      }
   }
}
