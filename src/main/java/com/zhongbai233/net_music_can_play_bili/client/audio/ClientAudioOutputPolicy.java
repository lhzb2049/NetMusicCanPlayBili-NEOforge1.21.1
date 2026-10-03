package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.blockentity.PlaybackAudioSource;
import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackRuntimeProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

final class ClientAudioOutputPolicy {
   private static final long AUDIO_SYNC_AHEAD_TOLERANCE_TICKS = PlaybackRuntimeProperties.audioSyncAheadToleranceTicks();

   private ClientAudioOutputPolicy() {
   }

   static boolean isCurrentSession(BlockPos sourcePos, String sessionId) {
      if (isWorldPosition(sourcePos) && sessionId != null && !sessionId.isBlank()) {
         String current = ModernTurntablePlaybackTracker.currentSessionId(sourcePos, sessionId);
         return current.isBlank() || current.equals(sessionId);
      } else {
         return true;
      }
   }

   static float volume(BlockPos sourcePos) {
      PlaybackAudioSource source = source(sourcePos);
      return source != null ? source.getVolume() : 1.0F;
   }

   static long targetRelativeTicks(BlockPos sourcePos, String sessionId, long startOffsetTicks) {
      if (ClientMinecartAudioAnchors.isMoving(sessionId)) {
         return Long.MAX_VALUE;
      } else {
         PlaybackAudioSource source = source(sourcePos);
         Minecraft minecraft = Minecraft.getInstance();
         if (source != null && minecraft != null && minecraft.level != null && source.isPlaying()) {
            long elapsedMillis = source.getPlaybackElapsedMillis();
            if (elapsedMillis < 0L) {
               return Long.MAX_VALUE;
            } else {
               long targetTicks = elapsedMillis / 50L;
               return Math.max(0L, targetTicks - startOffsetTicks + AUDIO_SYNC_AHEAD_TOLERANCE_TICKS);
            }
         } else {
            return Long.MAX_VALUE;
         }
      }
   }

   private static PlaybackAudioSource source(BlockPos sourcePos) {
      if (!isWorldPosition(sourcePos)) {
         return null;
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.level != null) {
            return minecraft.level.getBlockEntity(sourcePos) instanceof PlaybackAudioSource source ? source : null;
         } else {
            return null;
         }
      }
   }

   private static boolean isWorldPosition(BlockPos pos) {
      return pos != null && pos.getX() > -2147483646;
   }
}
