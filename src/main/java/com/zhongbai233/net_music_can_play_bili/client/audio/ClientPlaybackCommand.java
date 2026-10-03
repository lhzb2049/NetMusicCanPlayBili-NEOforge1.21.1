package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.util.Optional;

public record ClientPlaybackCommand(
   int sourceX,
   int sourceY,
   int sourceZ,
   String rawUrl,
   String playUrl,
   String songName,
   int remainingSeconds,
   Optional<PlaybackSessionId> playbackSessionId,
   long elapsedMillis,
   long totalMillis,
   PlaybackSync.MinecartAnchor minecartAnchor,
   boolean biliSelection,
   boolean loadLyrics
) {
   public ClientPlaybackCommand(
      int sourceX,
      int sourceY,
      int sourceZ,
      String rawUrl,
      String playUrl,
      String songName,
      int remainingSeconds,
      Optional<PlaybackSessionId> playbackSessionId,
      long elapsedMillis,
      long totalMillis,
      PlaybackSync.MinecartAnchor minecartAnchor,
      boolean biliSelection,
      boolean loadLyrics
   ) {
      rawUrl = normalize(rawUrl);
      playUrl = normalize(playUrl);
      songName = normalize(songName);
      remainingSeconds = Math.max(1, remainingSeconds);
      playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
      elapsedMillis = Math.max(0L, elapsedMillis);
      totalMillis = Math.max(0L, totalMillis);
      if (totalMillis > 0L) {
         elapsedMillis = Math.min(totalMillis, elapsedMillis);
      }

      this.sourceX = sourceX;
      this.sourceY = sourceY;
      this.sourceZ = sourceZ;
      this.rawUrl = rawUrl;
      this.playUrl = playUrl;
      this.songName = songName;
      this.remainingSeconds = remainingSeconds;
      this.playbackSessionId = playbackSessionId;
      this.elapsedMillis = elapsedMillis;
      this.totalMillis = totalMillis;
      this.minecartAnchor = minecartAnchor;
      this.biliSelection = biliSelection;
      this.loadLyrics = loadLyrics;
   }

   public ClientPlaybackCommand(
      int sourceX,
      int sourceY,
      int sourceZ,
      String rawUrl,
      String playUrl,
      String songName,
      int remainingSeconds,
      String sessionId,
      long elapsedMillis,
      long totalMillis,
      PlaybackSync.MinecartAnchor minecartAnchor,
      boolean biliSelection,
      boolean loadLyrics
   ) {
      this(
         sourceX,
         sourceY,
         sourceZ,
         rawUrl,
         playUrl,
         songName,
         remainingSeconds,
         PlaybackSessionId.parse(sessionId),
         elapsedMillis,
         totalMillis,
         minecartAnchor,
         biliSelection,
         loadLyrics
      );
   }

   public String sessionId() {
      return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
   }

   public boolean hasSession() {
      return this.playbackSessionId.isPresent();
   }

   public PlaybackSync.Metadata syncMetadata() {
      return this.hasSession() ? new PlaybackSync.Metadata(this.sessionId(), this.elapsedMillis, this.totalMillis) : new PlaybackSync.Metadata("", 0L, 0L);
   }

   public long durationMillis() {
      return this.totalMillis > 0L ? this.totalMillis : Math.max(0L, (long)this.remainingSeconds) * 1000L;
   }

   private static String normalize(String value) {
      return value != null ? value : "";
   }
}
