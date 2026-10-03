package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class OpenAlHrtfProperties {
   static final String FORCE_HRTF = "ncpb.dolby.force_hrtf";
   static final String LEGACY_FORCE_HRTF = "ncpb.dolby.forceHrtf";
   static final String DISABLE_HRTF = "ncpb.dolby.disable_hrtf";
   static final String LEGACY_DISABLE_HRTF = "ncpb.dolby.disableHrtf";
   static final String FORCE_HRTF_WITH_CHANNEL = "ncpb.dolby.force_hrtf_with_channel";
   static final String LEGACY_FORCE_HRTF_WITH_CHANNEL = "ncpb.dolby.forceHrtfWithChannel";

   private OpenAlHrtfProperties() {
   }

   static OpenAlHrtfProperties.Settings settings() {
      boolean forceHrtf = NcpbSystemProperties.booleanValue("ncpb.dolby.force_hrtf", "ncpb.dolby.forceHrtf", false);
      boolean disableHrtf = NcpbSystemProperties.booleanValue("ncpb.dolby.disable_hrtf", "ncpb.dolby.disableHrtf", false);
      boolean forceWithChannel = NcpbSystemProperties.booleanValue("ncpb.dolby.force_hrtf_with_channel", "ncpb.dolby.forceHrtfWithChannel", false);
      return new OpenAlHrtfProperties.Settings(forceHrtf && !disableHrtf, forceWithChannel);
   }

   record Settings(boolean forceHrtf, boolean forceHrtfWithChannel) {
   }
}
