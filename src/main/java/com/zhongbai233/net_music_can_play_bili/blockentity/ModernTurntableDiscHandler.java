package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

final class ModernTurntableDiscHandler extends ItemStackHandler {
   private final Supplier<ItemStack> stackGetter;
   private final Consumer<ItemStack> stackSetter;
   private final BooleanSupplier extractionAllowed;
   private final Consumer<ItemStack> commitListener;

   ModernTurntableDiscHandler(
      Supplier<ItemStack> stackGetter, Consumer<ItemStack> stackSetter, BooleanSupplier extractionAllowed, Consumer<ItemStack> commitListener
   ) {
      super(1);
      this.stackGetter = stackGetter;
      this.stackSetter = stackSetter;
      this.extractionAllowed = extractionAllowed;
      this.commitListener = commitListener;
   }

   public int getSlots() {
      return 1;
   }

   public ItemStack getStackInSlot(int slot) {
      this.validateSlotIndex(slot);
      return this.stackGetter.get();
   }

   public void setStackInSlot(int slot, ItemStack stack) {
      this.validateSlotIndex(slot);
      ItemStack previous = this.stackGetter.get();
      this.stackSetter.accept(stack);
      if (!ItemStack.matches(previous, stack)) {
         this.commitListener.accept(previous);
      }
   }

   public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
      if (!stack.isEmpty() && this.isItemValid(slot, stack)) {
         this.validateSlotIndex(slot);
         ItemStack previous = this.stackGetter.get();
         int limit = this.getSlotLimit(slot);
         if (!previous.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(stack, previous)) {
               return stack;
            }

            limit -= previous.getCount();
         }

         if (limit <= 0) {
            return stack;
         } else {
            boolean reachedLimit = stack.getCount() > limit;
            if (!simulate) {
               ItemStack next = reachedLimit ? stack.copyWithCount(limit) : stack;
               this.stackSetter.accept(next);
               this.commitListener.accept(previous);
            }

            return reachedLimit ? stack.copyWithCount(stack.getCount() - limit) : ItemStack.EMPTY;
         }
      } else {
         return stack;
      }
   }

   public ItemStack extractItem(int slot, int amount, boolean simulate) {
      if (amount == 0) {
         return ItemStack.EMPTY;
      } else {
         this.validateSlotIndex(slot);
         ItemStack existing = this.stackGetter.get();
         if (!existing.isEmpty() && this.extractionAllowed.getAsBoolean()) {
            int toExtract = Math.min(amount, existing.getMaxStackSize());
            if (existing.getCount() <= toExtract) {
               if (!simulate) {
                  this.stackSetter.accept(ItemStack.EMPTY);
                  this.commitListener.accept(existing);
                  return existing;
               } else {
                  return existing.copy();
               }
            } else {
               if (!simulate) {
                  this.stackSetter.accept(existing.copyWithCount(existing.getCount() - toExtract));
                  this.commitListener.accept(existing);
               }

               return existing.copyWithCount(toExtract);
            }
         } else {
            return ItemStack.EMPTY;
         }
      }
   }

   public int getSlotLimit(int slot) {
      return 1;
   }

   public boolean isItemValid(int slot, ItemStack stack) {
      return stack != null && !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
   }
}
