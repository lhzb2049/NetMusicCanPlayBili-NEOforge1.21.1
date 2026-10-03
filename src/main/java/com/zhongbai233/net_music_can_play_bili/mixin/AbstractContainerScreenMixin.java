package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({MouseHandler.class})
public abstract class AbstractContainerScreenMixin {
   @Inject(
      method = {"onScroll"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private void net_music_can_play_bili$selectMp4QueueItem(long window, double xOffset, double yOffset, CallbackInfo ci) {
      if (yOffset != 0.0) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.screen instanceof AbstractContainerScreen<?> screen) {
            Slot hoveredSlot = screen.getSlotUnderMouse();
            if (hoveredSlot != null) {
               ItemStack stack = hoveredSlot.getItem();
               if (stack.getItem() instanceof MP4Item) {
                  int queueSize = MP4Item.queueSize(stack);
                  if (queueSize > 0) {
                     MP4Item.State state = MP4Client.cachedStateFor(stack);
                     int selected = Math.max(0, Math.min(queueSize - 1, state.selectedQueueIndex() + (yOffset < 0.0 ? 1 : -1)));
                     MP4Client.selectQueueIndexLocally(stack, selected);
                     ci.cancel();
                  }
               }
            }
         }
      }
   }
}
