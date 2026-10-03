package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

public final class ModernTurntableStopPolicy {
   private ModernTurntableStopPolicy() {
   }

   public static ModernTurntableStopPolicy.Decision decide(String stoppedSessionId, String activeSessionId) {
      PlaybackSessionId stopped = PlaybackSessionId.parse(stoppedSessionId).orElse(null);
      PlaybackSessionId active = PlaybackSessionId.parse(activeSessionId).orElse(null);
      if (stopped != null && active != null) {
         return stopped.equals(active) ? ModernTurntableStopPolicy.Decision.STOP_EXACT : ModernTurntableStopPolicy.Decision.IGNORE_STALE;
      } else {
         return ModernTurntableStopPolicy.Decision.IGNORE_INVALID;
      }
   }

   public static enum Decision {
      STOP_EXACT,
      IGNORE_STALE,
      IGNORE_INVALID;
   }
}
