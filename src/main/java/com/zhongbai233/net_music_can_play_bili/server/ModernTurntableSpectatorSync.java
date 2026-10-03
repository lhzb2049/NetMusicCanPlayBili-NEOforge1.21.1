package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber
public final class ModernTurntableSpectatorSync {
   private static final int SYNC_INTERVAL_TICKS = 20;

   private ModernTurntableSpectatorSync() {
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      MinecraftServer server = event.getServer();
      if (server != null && server.getTickCount() % 20 == 0) {
         for (ServerLevel level : server.getAllLevels()) {
            ModernTurntableBlockEntity.syncLoadedTurntablesToSpectators(level);
         }
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      ModernTurntableBlockEntity.clearLoadedServerTurntables();
   }
}
