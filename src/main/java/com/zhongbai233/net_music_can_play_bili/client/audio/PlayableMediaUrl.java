package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.URI;

public final class PlayableMediaUrl {
   private PlayableMediaUrl() {
   }

   public static boolean isHttp(String value) {
      if (value != null && !value.isBlank()) {
         try {
            String stripped = PlaybackSync.strip(value);
            URI uri = new URI(stripped != null ? stripped : value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && !uri.getHost().isBlank() && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
         } catch (Exception var4) {
            return false;
         }
      } else {
         return false;
      }
   }
}
