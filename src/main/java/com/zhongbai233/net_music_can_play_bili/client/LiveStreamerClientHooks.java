package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.LiveStreamerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class LiveStreamerClientHooks {
   private LiveStreamerClientHooks() {
   }

   public static void openLiveStreamerScreen(BlockPos pos) {
      if (FMLEnvironment.dist == Dist.CLIENT) {
         LiveStreamerClientHooks.ClientOnly.openLiveStreamerScreen(pos);
      }
   }

   private static final class ClientOnly {
      private static void openLiveStreamerScreen(BlockPos pos) {
         Minecraft.getInstance().setScreen(new LiveStreamerScreen(pos));
      }
   }
}
