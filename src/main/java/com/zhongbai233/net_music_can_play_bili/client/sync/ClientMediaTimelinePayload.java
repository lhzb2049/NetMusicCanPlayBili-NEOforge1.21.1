package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;
import java.util.UUID;

public interface ClientMediaTimelinePayload {
   UUID sourceId();

   String sessionId();

   default Optional<PlaybackSessionId> playbackSessionId() {
      return PlaybackSessionId.parse(this.sessionId());
   }

   long elapsedMillis();

   int volumePerMille();

   boolean headphoneRouted();

   default AreaAudioZone areaAudioZone() {
      return AreaAudioZone.unrestricted();
   }
}
