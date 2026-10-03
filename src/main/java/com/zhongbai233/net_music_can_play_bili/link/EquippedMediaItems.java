package com.zhongbai233.net_music_can_play_bili.link;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class EquippedMediaItems {
   private static final EquippedMediaItems.CuriosBridge CURIOS = EquippedMediaItems.CuriosBridge.create();

   private EquippedMediaItems() {
   }

   public static ItemStack firstHeadphones(Player player) {
      return firstEquipped(player, HeadphoneAbility::has);
   }

   public static ItemStack firstHolographicGlasses(Player player) {
      return firstEquipped(player, HolographicGlassesAbility::has);
   }

   public static ItemStack firstMediaGear(Player player) {
      return firstEquipped(player, stack -> HeadphoneAbility.has(stack) || HolographicGlassesAbility.has(stack));
   }

   public static ItemStack firstCuriosEquipped(Player player, Predicate<ItemStack> filter) {
      return player != null && filter != null ? CURIOS.first(player, filter) : ItemStack.EMPTY;
   }

   public static ItemStack firstEquipped(Player player, Predicate<ItemStack> filter) {
      if (player != null && filter != null) {
         ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
         return filter.test(head) ? head : CURIOS.first(player, filter);
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static void forEachEquipped(Player player, Consumer<ItemStack> consumer) {
      if (player != null && consumer != null) {
         Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
         acceptOnce(seen, consumer, player.getItemBySlot(EquipmentSlot.HEAD));
         CURIOS.all(player, stack -> true).forEach(stack -> acceptOnce(seen, consumer, stack));
      }
   }

   private static void acceptOnce(Set<ItemStack> seen, Consumer<ItemStack> consumer, ItemStack stack) {
      if (!stack.isEmpty() && seen.add(stack)) {
         consumer.accept(stack);
      }
   }

   private static final class CuriosBridge {
      private final Method getCuriosInventory;
      private final Method findFirstCurio;
      private final Method findCurios;
      private final Method slotResultStack;

      private CuriosBridge(Method getCuriosInventory, Method findFirstCurio, Method findCurios, Method slotResultStack) {
         this.getCuriosInventory = getCuriosInventory;
         this.findFirstCurio = findFirstCurio;
         this.findCurios = findCurios;
         this.slotResultStack = slotResultStack;
      }

      static EquippedMediaItems.CuriosBridge create() {
         try {
            Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> itemHandler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Class<?> slotResult = Class.forName("top.theillusivec4.curios.api.SlotResult");
            return new EquippedMediaItems.CuriosBridge(
               curiosApi.getMethod("getCuriosInventory", LivingEntity.class),
               itemHandler.getMethod("findFirstCurio", Predicate.class),
               itemHandler.getMethod("findCurios", Predicate.class),
               slotResult.getMethod("stack")
            );
         } catch (RuntimeException | LinkageError | ReflectiveOperationException var3) {
            return new EquippedMediaItems.CuriosBridge(null, null, null, null);
         }
      }

      ItemStack first(Player player, Predicate<ItemStack> filter) {
         Object handler = this.handler(player);
         if (handler != null && this.findFirstCurio != null) {
            try {
               if (this.findFirstCurio.invoke(handler, filter) instanceof Optional<?> optional && optional.isPresent()) {
                  return this.stack(optional.get());
               }
            } catch (InvocationTargetException | RuntimeException | IllegalAccessException var6) {
               return ItemStack.EMPTY;
            }

            return ItemStack.EMPTY;
         } else {
            return ItemStack.EMPTY;
         }
      }

      List<ItemStack> all(Player player, Predicate<ItemStack> filter) {
         Object handler = this.handler(player);
         if (handler != null && this.findCurios != null) {
            try {
               if (this.findCurios.invoke(handler, filter) instanceof List<?> list && !list.isEmpty()) {
                  List<ItemStack> stacks = new ArrayList<>();

                  for (Object slotResult : list) {
                     ItemStack stack = this.stack(slotResult);
                     if (!stack.isEmpty()) {
                        stacks.add(stack);
                     }
                  }

                  return List.copyOf(stacks);
               } else {
                  return List.of();
               }
            } catch (InvocationTargetException | RuntimeException | IllegalAccessException var10) {
               return List.of();
            }
         } else {
            return List.of();
         }
      }

      private Object handler(Player player) {
         if (player != null && this.getCuriosInventory != null) {
            try {
               return this.getCuriosInventory.invoke(null, player) instanceof Optional<?> optional && optional.isPresent() ? optional.get() : null;
            } catch (InvocationTargetException | RuntimeException | IllegalAccessException var4) {
               return null;
            }
         } else {
            return null;
         }
      }

      private ItemStack stack(Object slotResult) {
         if (slotResult != null && this.slotResultStack != null) {
            try {
               return this.slotResultStack.invoke(slotResult) instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
            } catch (InvocationTargetException | RuntimeException | IllegalAccessException var4) {
               return ItemStack.EMPTY;
            }
         } else {
            return ItemStack.EMPTY;
         }
      }
   }
}
