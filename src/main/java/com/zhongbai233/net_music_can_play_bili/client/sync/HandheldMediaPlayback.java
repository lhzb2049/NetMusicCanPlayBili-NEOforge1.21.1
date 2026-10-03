package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;

public record HandheldMediaPlayback(
   Optional<PlaybackSessionId> playbackSessionId, String rawUrl, String title, MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle
) {
   public static final HandheldMediaPlayback EMPTY = new HandheldMediaPlayback(Optional.empty(), "", "", MediaTimelineClock.TimelineSnapshot.EMPTY, false);

   public HandheldMediaPlayback(
      Optional<PlaybackSessionId> playbackSessionId, String rawUrl, String title, MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle
   ) {
      playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
      this.playbackSessionId = playbackSessionId;
      this.rawUrl = rawUrl;
      this.title = title;
      this.timeline = timeline;
      this.allowAiSubtitle = allowAiSubtitle;
   }

   public HandheldMediaPlayback(String sessionId, String rawUrl, String title, MediaTimelineClock.TimelineSnapshot timeline, boolean allowAiSubtitle) {
      this(PlaybackSessionId.parse(sessionId), rawUrl, title, timeline, allowAiSubtitle);
   }

   public String sessionId() {
      return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
   }

   public boolean hasSession() {
      return this.playbackSessionId.isPresent();
   }

   public boolean hasPlayableVideoSource() {
      return this.hasSession() && this.rawUrl != null && !this.rawUrl.isBlank() && this.timeline != null && this.timeline.totalMillis() > 0L;
   }
}
