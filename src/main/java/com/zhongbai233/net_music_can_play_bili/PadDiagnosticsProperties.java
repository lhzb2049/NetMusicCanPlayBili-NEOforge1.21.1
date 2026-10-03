package com.zhongbai233.net_music_can_play_bili;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class PadDiagnosticsProperties {
   static final String VIDEO_DEBUG_LOG = "ncpb.pad.video.debug_log";
   static final String MAP_SERVER_SELF_TEST = "ncpb.pad.map.server_self_test";

   private PadDiagnosticsProperties() {
   }

   public static boolean videoDebugLogEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.pad.video.debug_log", false);
   }

   public static boolean mapServerSelfTestEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.pad.map.server_self_test", false);
   }
}
