package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;
import java.util.UUID;

public interface ClientMediaSyncPayload {
   int SOURCE_PLAYER = 0;
   int SOURCE_ITEM = 1;
   int SOURCE_BLOCK = 2;
   int SOURCE_CONTAINER_ENTITY = 3;

   UUID ownerId();

   UUID sourceId();

   int sourceType();

   int sourceEntityId();

   double sourceX();

   double sourceY();

   double sourceZ();

   boolean playing();

   int queueIndex();

   String playUrl();

   String rawUrl();

   String songName();

   int durationSeconds();

   int volumePerMille();

   String sessionId();

   default Optional<PlaybackSessionId> playbackSessionId() {
      return PlaybackSessionId.parse(this.sessionId());
   }

   long elapsedMillis();

   boolean headphoneRouted();

   default AreaAudioZone areaAudioZone() {
      return AreaAudioZone.unrestricted();
   }
}
