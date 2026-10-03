package com.zhongbai233.net_music_can_play_bili.server;

import com.zhongbai233.net_music_can_play_bili.compat.areacontrol.AreaControlAudioCompat;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.network.AreaAudioListenerPacket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AreaAudioListenerSync {
   private static final Map<UUID, AreaAudioZone> LAST_SENT = new ConcurrentHashMap<>();

   private AreaAudioListenerSync() {
   }

   public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         sync(player, true);
      }
   }

   public static void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         LAST_SENT.remove(player.getUUID());
      }
   }

   public static void onServerTick(Post event) {
      for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
         sync(player, false);
      }
   }

   public static void onServerStopping(ServerStoppingEvent event) {
      LAST_SENT.clear();
   }

   private static void sync(ServerPlayer player, boolean force) {
      AreaAudioZone zone = AreaControlAudioCompat.zoneAt((ServerLevel)player.level(), BlockPos.containing(player.position()));
      AreaAudioZone previous = LAST_SENT.put(player.getUUID(), zone);
      if (force || !zone.equals(previous)) {
         PacketDistributor.sendToPlayer(player, new AreaAudioListenerPacket(zone), new CustomPacketPayload[0]);
      }
   }
}
