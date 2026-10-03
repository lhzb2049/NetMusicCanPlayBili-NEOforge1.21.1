package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.network.NetworkHandler;
import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.compat.areacontrol.AreaControlAudioCompat;
import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.AudioPlaybackIndexSavedData;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.AudioEndpointSnapshotPacket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

final class ModernTurntableAudienceSync {
   private static final AudioEndpointSubscriptionTracker ENDPOINT_SUBSCRIPTIONS = new AudioEndpointSubscriptionTracker();

   private ModernTurntableAudienceSync() {
   }

   static Set<UUID> syncNearbyPlayers(
      ServerLevel serverLevel,
      Level anchorLevel,
      BlockPos sourcePos,
      PlaybackSourceId sourceId,
      Set<UUID> previouslySynced,
      String playUrl,
      String rawUrl,
      String songName,
      String sessionId,
      long elapsedMillis,
      long durationMillis,
      int remainingSeconds,
      int range
   ) {
      Set<UUID> nearby = new HashSet<>();
      List<AudioPlaybackIndexSavedData.EndpointEntry> endpoints = AudioLinkIndex.speakerEndpointsFor(serverLevel, sourceId);
      AudioEndpointSubscriptionTracker.SpatialSnapshot spatialEndpoints = AudioEndpointSubscriptionTracker.snapshot(
         endpoints.stream().map(ModernTurntableAudienceSync::subscriptionEndpoint).toList()
      );
      String synchronizedUrl = synchronizedUrl(anchorLevel, playUrl, sourceId, sessionId, elapsedMillis, durationMillis);

      for (ServerPlayer player : serverLevel.players()) {
         AudioEndpointSubscriptionTracker.Update update = ENDPOINT_SUBSCRIPTIONS.update(
            player.getUUID(), sourceId, sessionId, sourcePos.asLong(), player.getX(), player.getY(), player.getZ(), range, spatialEndpoints
         );
         if (update.packetRequired()) {
            sendEndpointDelta(serverLevel, player, sourceId, sourcePos, update);
         }

         if (update.playbackRecipient()) {
            nearby.add(player.getUUID());
            sendPlayback(player, sourcePos, synchronizedUrl, rawUrl, playUrl, songName, remainingSeconds);
         }
      }

      return nearby;
   }

   static Set<UUID> syncNearbySpectators(
      ServerLevel serverLevel,
      Level anchorLevel,
      BlockPos sourcePos,
      PlaybackSourceId sourceId,
      Set<UUID> previouslySynced,
      String playUrl,
      String rawUrl,
      String songName,
      String sessionId,
      long elapsedMillis,
      long durationMillis,
      int remainingSeconds,
      int range
   ) {
      Set<UUID> nearbySpectators = new HashSet<>();
      Set<UUID> retained = new HashSet<>(previouslySynced);
      List<AudioPlaybackIndexSavedData.EndpointEntry> endpoints = AudioLinkIndex.speakerEndpointsFor(serverLevel, sourceId);
      AudioEndpointSubscriptionTracker.SpatialSnapshot spatialEndpoints = AudioEndpointSubscriptionTracker.snapshot(
         endpoints.stream().map(ModernTurntableAudienceSync::subscriptionEndpoint).toList()
      );
      String synchronizedUrl = synchronizedUrl(anchorLevel, playUrl, sourceId, sessionId, elapsedMillis, durationMillis);

      for (ServerPlayer player : serverLevel.players()) {
         if (player.isSpectator()) {
            AudioEndpointSubscriptionTracker.Update update = ENDPOINT_SUBSCRIPTIONS.update(
               player.getUUID(), sourceId, sessionId, sourcePos.asLong(), player.getX(), player.getY(), player.getZ(), range, spatialEndpoints
            );
            if (update.packetRequired()) {
               sendEndpointDelta(serverLevel, player, sourceId, sourcePos, update);
            }

            if (update.playbackRecipient()) {
               nearbySpectators.add(player.getUUID());
               if (retained.add(player.getUUID())) {
                  sendPlayback(player, sourcePos, synchronizedUrl, rawUrl, playUrl, songName, remainingSeconds);
               }
            }
         }
      }

      retained.removeIf(playerId -> {
         ServerPlayer playerx = serverLevel.getServer().getPlayerList().getPlayer(playerId);
         return playerx == null || playerx.level() != serverLevel || playerx.isSpectator() && !nearbySpectators.contains(playerId);
      });
      return retained;
   }

   private static String synchronizedUrl(
      Level anchorLevel, String playUrl, PlaybackSourceId sourceId, String sessionId, long elapsedMillis, long durationMillis
   ) {
      String result = PlaybackSync.withSync(playUrl, sessionId, elapsedMillis, durationMillis);
      result = PlaybackSync.withSourceId(result, sourceId);
      Entity hostMinecart = MinecartTurntableCompat.hostMinecart(anchorLevel);
      return hostMinecart != null ? PlaybackSync.withMinecartAnchor(result, hostMinecart.getId(), hostMinecart.getUUID()) : result;
   }

   private static void sendEndpointDelta(
      ServerLevel level, ServerPlayer player, PlaybackSourceId sourceId, BlockPos sourcePos, AudioEndpointSubscriptionTracker.Update update
   ) {
      List<AudioEndpointSnapshotPacket.Endpoint> upserts = update.upserts()
         .stream()
         .map(
            endpoint -> new AudioEndpointSnapshotPacket.Endpoint(
               endpoint.endpointId(),
               BlockPos.of(endpoint.endpointPos()),
               endpoint.channelIndex(),
               endpoint.volume(),
               endpoint.autoMixJoc(),
               endpoint.maxDistance(),
               endpoint.revision(),
               AreaControlAudioCompat.zoneAt(level, BlockPos.of(endpoint.endpointPos()))
            )
         )
         .toList();
      PacketDistributor.sendToPlayer(
         player,
         new AudioEndpointSnapshotPacket(
            sourceId.value(),
            sourcePos,
            AreaControlAudioCompat.zoneAt(level, sourcePos),
            update.generation(),
            update.reset(),
            update.subscribed(),
            upserts,
            update.removals()
         ),
         new CustomPacketPayload[0]
      );
   }

   private static AudioEndpointSubscriptionTracker.Endpoint subscriptionEndpoint(AudioPlaybackIndexSavedData.EndpointEntry endpoint) {
      return new AudioEndpointSubscriptionTracker.Endpoint(
         endpoint.endpointId(),
         endpoint.endpointPos(),
         endpoint.channelIndex(),
         endpoint.volume(),
         endpoint.autoMixJoc(),
         endpoint.maxDistance(),
         endpoint.revision()
      );
   }

   private static void sendPlayback(
      ServerPlayer player, BlockPos sourcePos, String synchronizedUrl, String rawUrl, String playUrl, String songName, int remainingSeconds
   ) {
      MusicToClientMessage message = new MusicToClientMessage(
         sourcePos, synchronizedUrl, rawUrl.isBlank() ? playUrl : rawUrl, Math.max(1, remainingSeconds), songName
      );
      NetworkHandler.sendToClientPlayer(message, player);
   }

   static void forgetPlayer(UUID playerId) {
      ENDPOINT_SUBSCRIPTIONS.forgetPlayer(playerId);
   }

   static void forgetSource(PlaybackSourceId sourceId) {
      ENDPOINT_SUBSCRIPTIONS.forgetSource(sourceId);
   }

   static void clearSubscriptions() {
      ENDPOINT_SUBSCRIPTIONS.clear();
   }
}
