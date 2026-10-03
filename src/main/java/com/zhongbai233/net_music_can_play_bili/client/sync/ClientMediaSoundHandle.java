package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;

public interface ClientMediaSoundHandle {
   String sessionId();

   default Optional<PlaybackSessionId> playbackSession() {
      return PlaybackSessionId.parse(this.sessionId());
   }

   boolean headphoneRouted();

   boolean stopped();

   void discardWithoutFinishing();

   void setMediaVolume(float var1);
}
