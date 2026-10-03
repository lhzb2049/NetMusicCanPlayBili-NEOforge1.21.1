package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.network.PadMapScopeSavedData;
import com.zhongbai233.net_music_can_play_bili.network.PadMapWorldScopePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PadMapScopeSync {
   private PadMapScopeSync() {
   }

   public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         MinecraftServer server = player.level().getServer();
         if (server != null) {
            String worldScopeId = PadMapScopeSavedData.get(server.overworld()).worldScopeId();
            String worldName = server.getWorldData().getLevelName();
            PacketDistributor.sendToPlayer(player, new PadMapWorldScopePacket(worldScopeId, worldName), new CustomPacketPayload[0]);
         }
      }
   }
}
