package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import java.util.Objects;

final class LiveStatusProbePolicy {
   static final long PROBE_INTERVAL_TICKS = 200L;

   private LiveStatusProbePolicy() {
   }

   static boolean shouldProbe(boolean autoResumeRequested, boolean probeInFlight, long gameTime, long nextProbeGameTime) {
      return autoResumeRequested && !probeInFlight && gameTime >= nextProbeGameTime;
   }

   static long nextProbeGameTime(long gameTime) {
      return gameTime > 9223372036854775607L ? Long.MAX_VALUE : gameTime + 200L;
   }

   static boolean acceptsResult(
      boolean autoResumeRequested,
      ResolveGeneration currentGeneration,
      ResolveGeneration capturedGeneration,
      long currentProbeId,
      long capturedProbeId,
      String currentRoomId,
      String requestedRoomId
   ) {
      return autoResumeRequested
         && Objects.requireNonNull(currentGeneration, "currentGeneration").equals(Objects.requireNonNull(capturedGeneration, "capturedGeneration"))
         && currentProbeId == capturedProbeId
         && requestedRoomId.equals(currentRoomId);
   }
}
