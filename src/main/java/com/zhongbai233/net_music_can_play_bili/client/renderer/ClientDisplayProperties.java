package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class ClientDisplayProperties {
   static final String CONTROL_CONSOLE_VIDEO_HEALTH_CHECK_MILLIS = "ncpb.control_console.video_health_check_ms";
   static final String HOLOGRAPHIC_WORLD_SCREEN_ENABLED = "ncpb.holographic_glasses.screen.enabled";
   static final String MP4_PROJECTED_INPUT_ENABLED = "ncpb.mp4.projected_input";

   private ClientDisplayProperties() {
   }

   public static long controlConsoleVideoHealthCheckMillis() {
      return Math.max(100L, NcpbSystemProperties.longValue("ncpb.control_console.video_health_check_ms", 1000L));
   }

   public static boolean holographicWorldScreenEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.holographic_glasses.screen.enabled", true);
   }

   public static boolean mp4ProjectedInputEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.mp4.projected_input", true);
   }
}
