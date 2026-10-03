package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class PadRenderProperties {
   static final String OFFSCREEN_SCALE = "ncpb.pad.offscreen_scale";
   static final String LEGACY_MP4_OFFSCREEN_SCALE = "ncpb.mp4.offscreen_scale";
   static final String GUI_PAN_RENDER_BLOCKS = "ncpb.pad.gui_pan_render_blocks";
   static final String GUI_YAW_RENDER_DEGREES = "ncpb.pad.gui_yaw_render_degrees";
   static final String GUI_PLAYBACK_REFRESH_TICKS = "ncpb.pad.gui_playback_refresh_ticks";
   static final String GUI_MAX_FPS = "ncpb.pad.gui_max_fps";
   static final String HANDHELD_LEFT_SHIFT = "ncpb.pad.handheld_left_shift";
   static final String MAP_CELL_PIXELS = "ncpb.pad.map_cell_pixels";
   static final String MAP_LAYER_SCALE = "ncpb.pad.map_layer_scale";
   static final String MAP_MIN_BAKE_INTERVAL_MILLIS = "ncpb.pad.map_min_bake_interval_ms";
   static final String MAP_LAYER_TICK_INTERVAL_TICKS = "ncpb.pad.map_layer_tick_interval_ticks";
   static final String PERF_LOG = "ncpb.pad.perf_log";
   static final String PERF_SLOW_WARN_MILLIS = "ncpb.pad.perf_slow_warn_ms";
   static final String PERF_SLOW_WARN_COOLDOWN_MILLIS = "ncpb.pad.perf_slow_warn_cooldown_ms";
   static final String VIDEO_RENDERDOC_PROBE = "ncpb.pad.video.renderdoc_probe";

   private PadRenderProperties() {
   }

   public static PadRenderProperties.Offscreen offscreen() {
      return new PadRenderProperties.Offscreen(
         NcpbSystemProperties.intValue("ncpb.pad.offscreen_scale", "ncpb.mp4.offscreen_scale", 2),
         Math.max(0.05F, NcpbSystemProperties.floatValue("ncpb.pad.gui_pan_render_blocks", 0.5F)),
         Math.max(0.5F, NcpbSystemProperties.floatValue("ncpb.pad.gui_yaw_render_degrees", 4.0F)),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.pad.gui_playback_refresh_ticks", 20)),
         NcpbSystemProperties.longValue("ncpb.pad.gui_max_fps", 60L)
      );
   }

   public static PadRenderProperties.MapLayer mapLayer() {
      return new PadRenderProperties.MapLayer(
         NcpbSystemProperties.intValue("ncpb.pad.map_cell_pixels", 1),
         NcpbSystemProperties.intValue("ncpb.pad.map_layer_scale", 1),
         NcpbSystemProperties.longValue("ncpb.pad.map_min_bake_interval_ms", 500L),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.pad.map_layer_tick_interval_ticks", 5))
      );
   }

   public static PadRenderProperties.Performance performance() {
      return new PadRenderProperties.Performance(
         NcpbSystemProperties.booleanValue("ncpb.pad.perf_log", false),
         NcpbSystemProperties.longValue("ncpb.pad.perf_slow_warn_ms", 12L),
         NcpbSystemProperties.longValue("ncpb.pad.perf_slow_warn_cooldown_ms", 5000L)
      );
   }

   public static float handheldLeftShift() {
      return NcpbSystemProperties.floatValue("ncpb.pad.handheld_left_shift", 0.05F);
   }

   public static boolean videoRenderdocProbeEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.pad.video.renderdoc_probe", false);
   }

   public record MapLayer(int cellPixels, int scale, long minBakeIntervalMillis, int tickIntervalTicks) {
   }

   public record Offscreen(int scale, float mapPanRenderBlocks, float mapYawRenderDegrees, int playbackRefreshTicks, long maxFps) {
   }

   public record Performance(boolean explicitEnabled, long slowWarnMillis, long slowWarnCooldownMillis) {
   }
}
