package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class PlaybackRuntimeProperties {
   static final String LIVE_WATCHDOG_STALL_MILLIS = "ncpb.bili.live.watchdog.stall_ms";
   static final String AUDIO_WATCHDOG_STARTUP_STALL_MILLIS = "ncpb.bili.audio.watchdog.startup_stall_ms";
   static final String AUDIO_WATCHDOG_NO_PROGRESS_MILLIS = "ncpb.bili.audio.watchdog.no_progress_ms";
   static final String AUDIO_WATCHDOG_END_GRACE_MILLIS = "ncpb.bili.audio.watchdog.end_grace_ms";
   static final String AUDIO_SYNC_AHEAD_TOLERANCE_TICKS = "ncpb.bili.audio.openal.ahead_tolerance_ticks";
   static final String LEGACY_AUDIO_SYNC_AHEAD_TOLERANCE_TICKS = "bili.audio.openal.ahead_tolerance_ticks";
   static final String WARN_DRIFT_MILLIS = "ncpb.playback.diagnostics.warn_drift_ms";
   static final String DEBUG_AV_DRIFT_MILLIS = "ncpb.playback.diagnostics.debug_av_drift_ms";
   static final String LEGACY_DEBUG_AV_DRIFT_MILLIS = "bili.playback.diagnostics.debug_av_drift_ms";

   private PlaybackRuntimeProperties() {
   }

   public static PlaybackRuntimeProperties.Watchdog watchdog() {
      return new PlaybackRuntimeProperties.Watchdog(
         millisToTicks(NcpbSystemProperties.intValue("ncpb.bili.live.watchdog.stall_ms", 45000)),
         millisToTicks(NcpbSystemProperties.intValue("ncpb.bili.audio.watchdog.startup_stall_ms", 15000)),
         millisToTicks(NcpbSystemProperties.intValue("ncpb.bili.audio.watchdog.no_progress_ms", 12000)),
         NcpbSystemProperties.longValue("ncpb.bili.audio.watchdog.end_grace_ms", 2000L)
      );
   }

   public static long audioSyncAheadToleranceTicks() {
      return NcpbSystemProperties.longValue("ncpb.bili.audio.openal.ahead_tolerance_ticks", "bili.audio.openal.ahead_tolerance_ticks", 0L);
   }

   public static PlaybackRuntimeProperties.Diagnostics diagnostics() {
      return new PlaybackRuntimeProperties.Diagnostics(
         NcpbSystemProperties.longValue("ncpb.playback.diagnostics.warn_drift_ms", 2000L),
         NcpbSystemProperties.longValue("ncpb.playback.diagnostics.debug_av_drift_ms", "bili.playback.diagnostics.debug_av_drift_ms", 250L)
      );
   }

   private static int millisToTicks(int millis) {
      return millis / 50;
   }

   public record Diagnostics(long warnDriftMillis, long debugAvDriftMillis) {
      public Diagnostics(long warnDriftMillis, long debugAvDriftMillis) {
         warnDriftMillis = Math.max(0L, warnDriftMillis);
         debugAvDriftMillis = Math.max(0L, debugAvDriftMillis);
         this.warnDriftMillis = warnDriftMillis;
         this.debugAvDriftMillis = debugAvDriftMillis;
      }
   }

   public record Watchdog(int liveStallTicks, int audioStartupStallTicks, int audioNoProgressTicks, long audioEndGraceMillis) {
      public Watchdog(int liveStallTicks, int audioStartupStallTicks, int audioNoProgressTicks, long audioEndGraceMillis) {
         liveStallTicks = Math.max(100, liveStallTicks);
         audioStartupStallTicks = Math.max(20, audioStartupStallTicks);
         audioNoProgressTicks = Math.max(20, audioNoProgressTicks);
         audioEndGraceMillis = Math.max(0L, audioEndGraceMillis);
         this.liveStallTicks = liveStallTicks;
         this.audioStartupStallTicks = audioStartupStallTicks;
         this.audioNoProgressTicks = audioNoProgressTicks;
         this.audioEndGraceMillis = audioEndGraceMillis;
      }
   }
}
