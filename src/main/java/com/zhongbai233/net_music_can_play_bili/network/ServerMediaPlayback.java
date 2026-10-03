package com.zhongbai233.net_music_can_play_bili.network;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

public final class ServerMediaPlayback {
   private ServerMediaPlayback() {
   }

   public static void start(ServerPlayer owner, ServerMediaPlayback.StartRequest request) {
      if (owner != null && request != null) {
         start(
            owner,
            new MP4PlaybackSyncPacket(
               request.ownerId() != null ? request.ownerId() : owner.getUUID(),
               request.sourceId(),
               request.sourceType(),
               request.sourceEntityId(),
               request.sourceX(),
               request.sourceY(),
               request.sourceZ(),
               true,
               request.queueIndex(),
               request.playUrl(),
               request.rawUrl(),
               request.songName(),
               request.durationSeconds(),
               request.volumePerMille(),
               request.sessionId(),
               request.elapsedMillis(),
               request.headphoneRouted()
            )
         );
      }
   }

   public static void start(ServerPlayer owner, MP4PlaybackSyncPacket packet) {
      MP4PlaybackSyncManager.start(owner, packet);
   }

   public static void stop(ServerPlayer owner, UUID deviceId) {
      MP4PlaybackSyncManager.stop(owner, deviceId);
   }

   public static void stopAndBroadcast(ServerPlayer owner, UUID deviceId) {
      stop(owner, deviceId);
   }

   public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
      MP4PlaybackSyncManager.stopExternalPlaybackForLinkedHeadphones(actor, deviceId);
   }

   public record StartRequest(
      UUID ownerId,
      UUID sourceId,
      int sourceType,
      int sourceEntityId,
      double sourceX,
      double sourceY,
      double sourceZ,
      int queueIndex,
      String playUrl,
      String rawUrl,
      String songName,
      int durationSeconds,
      int volumePerMille,
      String sessionId,
      long elapsedMillis,
      boolean headphoneRouted
   ) {
      public static ServerMediaPlayback.StartRequest player(
         ServerPlayer player,
         UUID deviceId,
         int queueIndex,
         String playUrl,
         String rawUrl,
         String songName,
         int durationSeconds,
         int volumePerMille,
         String sessionId,
         long elapsedMillis
      ) {
         return player == null
            ? new ServerMediaPlayback.StartRequest(
               null, deviceId, 0, -1, 0.0, 0.0, 0.0, queueIndex, playUrl, rawUrl, songName, durationSeconds, volumePerMille, sessionId, elapsedMillis, false
            )
            : new ServerMediaPlayback.StartRequest(
               player.getUUID(),
               deviceId,
               0,
               player.getId(),
               player.getX(),
               player.getY() + 1.2,
               player.getZ(),
               queueIndex,
               playUrl,
               rawUrl,
               songName,
               durationSeconds,
               volumePerMille,
               sessionId,
               elapsedMillis,
               false
            );
      }
   }
}
