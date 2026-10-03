package com.zhongbai233.net_music_can_play_bili.client;

import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class PadClientHooks {
   private PadClientHooks() {
   }

   public static void openFocusScreen(InteractionHand hand) {
      if (FMLEnvironment.dist == Dist.CLIENT) {
         PadClientHooks.ClientOnly.openFocusScreen(hand);
      }
   }

   private static final class ClientOnly {
      private static void openFocusScreen(InteractionHand hand) {
         PadClient.openFocusScreen(hand);
      }
   }
}
