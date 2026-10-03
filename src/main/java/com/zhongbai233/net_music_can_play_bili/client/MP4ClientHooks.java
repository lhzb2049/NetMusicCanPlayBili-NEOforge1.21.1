package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class MP4ClientHooks {
   private MP4ClientHooks() {
   }

   public static void openFocusScreen(InteractionHand hand) {
      if (FMLEnvironment.dist == Dist.CLIENT) {
         MP4ClientHooks.ClientOnly.openFocusScreen(hand);
      }
   }

   public static int selectedQueueIndex(ItemStack stack) {
      return FMLEnvironment.dist == Dist.CLIENT ? MP4ClientHooks.ClientOnly.selectedQueueIndex(stack) : 0;
   }

   private static final class ClientOnly {
      private static void openFocusScreen(InteractionHand hand) {
         MP4Client.openFocusScreen(hand);
      }

      private static int selectedQueueIndex(ItemStack stack) {
         return MP4Client.cachedStateFor(stack).selectedQueueIndex();
      }
   }
}
