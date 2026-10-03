package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.concurrent.TimeUnit;

final class TimelineProperties {
   static final String HARD_SYNC_MILLIS = "ncpb.media.timeline.hard_sync_ms";
   static final String LEGACY_HARD_SYNC_MILLIS = "bili.media.timeline.hard_sync_ms";
   static final String DEPRECATED_TURNTABLE_HARD_SYNC_MILLIS = "ncpb.turntable.timeline.hard_sync_ms";
   static final String MAX_SMOOTH_CORRECTION_MILLIS = "ncpb.media.timeline.max_smooth_correction_ms";
   static final String LEGACY_MAX_SMOOTH_CORRECTION_MILLIS = "bili.media.timeline.max_smooth_correction_ms";
   static final String DEPRECATED_TURNTABLE_MAX_SMOOTH_CORRECTION_MILLIS = "ncpb.turntable.timeline.max_smooth_correction_ms";
   static final String SMOOTH_CORRECTION_RATIO = "ncpb.media.timeline.smooth_correction_ratio";
   static final String LEGACY_SMOOTH_CORRECTION_RATIO = "ncpb.turntable.timeline.smooth_correction_ratio";
   static final String TURNTABLE_AUDIO_ANCHOR = "ncpb.turntable.timeline.audio_anchor";
   static final String TURNTABLE_AUDIO_ANCHOR_MAX_LAG_MILLIS = "ncpb.turntable.timeline.audio_anchor_max_lag_ms";
   static final String TURNTABLE_AUDIO_ANCHOR_MAX_LEAD_MILLIS = "ncpb.turntable.timeline.audio_anchor_max_lead_ms";
   static final String TURNTABLE_CLOCK_PRUNE_INTERVAL_MILLIS = "ncpb.turntable.timeline.clock_prune_interval_ms";
   static final String TURNTABLE_VISUAL_HARD_SYNC_MILLIS = "ncpb.turntable.timeline.visual_hard_sync_ms";
   static final String TURNTABLE_VISUAL_MAX_CORRECTION_MILLIS = "ncpb.turntable.timeline.visual_max_correction_ms";
   static final String TURNTABLE_VISUAL_CORRECTION_RATIO = "ncpb.turntable.timeline.visual_correction_ratio";
   static final String HANDHELD_AUDIO_ANCHOR_MAX_LAG_MILLIS = "ncpb.media.timeline.audio_anchor_max_lag_ms";
   static final String HANDHELD_AUDIO_ANCHOR_MAX_LEAD_MILLIS = "ncpb.media.timeline.audio_anchor_max_lead_ms";

   private TimelineProperties() {
   }

   static TimelineProperties.Clock clock() {
      long deprecatedHardSync = NcpbSystemProperties.longValue("ncpb.turntable.timeline.hard_sync_ms", 1500L);
      long deprecatedMaxCorrection = NcpbSystemProperties.longValue("ncpb.turntable.timeline.max_smooth_correction_ms", 80L);
      return new TimelineProperties.Clock(
         NcpbSystemProperties.longValue("ncpb.media.timeline.hard_sync_ms", "bili.media.timeline.hard_sync_ms", deprecatedHardSync),
         NcpbSystemProperties.longValue("ncpb.media.timeline.max_smooth_correction_ms", "bili.media.timeline.max_smooth_correction_ms", deprecatedMaxCorrection),
         NcpbSystemProperties.doubleValue("ncpb.media.timeline.smooth_correction_ratio", "ncpb.turntable.timeline.smooth_correction_ratio", 0.12)
      );
   }

   static TimelineProperties.Turntable turntable() {
      long pruneMillis = Math.max(1000L, NcpbSystemProperties.longValue("ncpb.turntable.timeline.clock_prune_interval_ms", 30000L));
      return new TimelineProperties.Turntable(
         NcpbSystemProperties.booleanValue("ncpb.turntable.timeline.audio_anchor", true),
         NcpbSystemProperties.longValue("ncpb.turntable.timeline.audio_anchor_max_lag_ms", 2000L),
         NcpbSystemProperties.longValue("ncpb.turntable.timeline.audio_anchor_max_lead_ms", 500L),
         TimeUnit.MILLISECONDS.toNanos(pruneMillis),
         NcpbSystemProperties.longValue("ncpb.turntable.timeline.visual_hard_sync_ms", 500L),
         NcpbSystemProperties.longValue("ncpb.turntable.timeline.visual_max_correction_ms", 20L),
         NcpbSystemProperties.doubleValue("ncpb.turntable.timeline.visual_correction_ratio", 0.2)
      );
   }

   static TimelineProperties.Handheld handheld() {
      return new TimelineProperties.Handheld(
         NcpbSystemProperties.longValue("ncpb.media.timeline.audio_anchor_max_lag_ms", 2000L),
         NcpbSystemProperties.longValue("ncpb.media.timeline.audio_anchor_max_lead_ms", 500L)
      );
   }

   private static double clampRatio(double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }

   record Clock(long hardSyncThresholdMillis, long maxSmoothCorrectionMillis, double smoothCorrectionRatio) {
      Clock(long hardSyncThresholdMillis, long maxSmoothCorrectionMillis, double smoothCorrectionRatio) {
         hardSyncThresholdMillis = Math.max(0L, hardSyncThresholdMillis);
         maxSmoothCorrectionMillis = Math.max(0L, maxSmoothCorrectionMillis);
         smoothCorrectionRatio = TimelineProperties.clampRatio(smoothCorrectionRatio);
         this.hardSyncThresholdMillis = hardSyncThresholdMillis;
         this.maxSmoothCorrectionMillis = maxSmoothCorrectionMillis;
         this.smoothCorrectionRatio = smoothCorrectionRatio;
      }
   }

   record Handheld(long audioAnchorMaxLagMillis, long audioAnchorMaxLeadMillis) {
      Handheld(long audioAnchorMaxLagMillis, long audioAnchorMaxLeadMillis) {
         audioAnchorMaxLagMillis = Math.max(0L, audioAnchorMaxLagMillis);
         audioAnchorMaxLeadMillis = Math.max(0L, audioAnchorMaxLeadMillis);
         this.audioAnchorMaxLagMillis = audioAnchorMaxLagMillis;
         this.audioAnchorMaxLeadMillis = audioAnchorMaxLeadMillis;
      }
   }

   record Turntable(
      boolean audioAnchored,
      long audioAnchorMaxLagMillis,
      long audioAnchorMaxLeadMillis,
      long clockPruneIntervalNanos,
      long visualHardSyncMillis,
      long visualMaxCorrectionMillis,
      double visualCorrectionRatio
   ) {
      Turntable(
         boolean audioAnchored,
         long audioAnchorMaxLagMillis,
         long audioAnchorMaxLeadMillis,
         long clockPruneIntervalNanos,
         long visualHardSyncMillis,
         long visualMaxCorrectionMillis,
         double visualCorrectionRatio
      ) {
         audioAnchorMaxLagMillis = Math.max(0L, audioAnchorMaxLagMillis);
         audioAnchorMaxLeadMillis = Math.max(0L, audioAnchorMaxLeadMillis);
         clockPruneIntervalNanos = Math.max(1L, clockPruneIntervalNanos);
         visualHardSyncMillis = Math.max(0L, visualHardSyncMillis);
         visualMaxCorrectionMillis = Math.max(0L, visualMaxCorrectionMillis);
         visualCorrectionRatio = TimelineProperties.clampRatio(visualCorrectionRatio);
         this.audioAnchored = audioAnchored;
         this.audioAnchorMaxLagMillis = audioAnchorMaxLagMillis;
         this.audioAnchorMaxLeadMillis = audioAnchorMaxLeadMillis;
         this.clockPruneIntervalNanos = clockPruneIntervalNanos;
         this.visualHardSyncMillis = visualHardSyncMillis;
         this.visualMaxCorrectionMillis = visualMaxCorrectionMillis;
         this.visualCorrectionRatio = visualCorrectionRatio;
      }
   }
}
