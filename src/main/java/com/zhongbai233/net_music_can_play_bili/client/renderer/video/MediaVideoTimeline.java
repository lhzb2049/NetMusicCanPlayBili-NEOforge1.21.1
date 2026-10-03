package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;

interface MediaVideoTimeline {
   MediaVideoTimeline EMPTY = new MediaVideoTimeline() {
      @Override
      public long mediaMillis() {
         return -1L;
      }

      @Override
      public long visualMillis() {
         return -1L;
      }

      @Override
      public long pacingMillis() {
         return -1L;
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         return -1L;
      }

      @Override
      public long totalMillis() {
         return 0L;
      }

      @Override
      public Optional<PlaybackSessionId> playbackSessionId() {
         return Optional.empty();
      }
   };

   long mediaMillis();

   long visualMillis();

   long pacingMillis();

   long relativeNanos(long var1);

   long totalMillis();

   Optional<PlaybackSessionId> playbackSessionId();

   default String sessionId() {
      return this.playbackSessionId().map(session -> session.value()).orElse("");
   }
}
