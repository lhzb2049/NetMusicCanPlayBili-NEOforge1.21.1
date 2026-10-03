package com.zhongbai233.net_music_can_play_bili.media.sync;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;

public record PlaybackRequest(
   String mediaUrl,
   BlockPos pos,
   Optional<PlaybackSessionId> playbackSessionId,
   long elapsedMillis,
   long totalMillis,
   UUID ownerId,
   UUID minecartUuid,
   long capturedNanos
) {
   public PlaybackRequest(
      String mediaUrl,
      BlockPos pos,
      Optional<PlaybackSessionId> playbackSessionId,
      long elapsedMillis,
      long totalMillis,
      UUID ownerId,
      UUID minecartUuid,
      long capturedNanos
   ) {
      if (mediaUrl != null && !mediaUrl.isBlank()) {
         mediaUrl = PlaybackSync.strip(mediaUrl);
         pos = AudioUtils.copyPos(pos);
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         elapsedMillis = Math.max(0L, elapsedMillis);
         totalMillis = Math.max(0L, totalMillis);
         capturedNanos = capturedNanos > 0L ? capturedNanos : System.nanoTime();
         this.mediaUrl = mediaUrl;
         this.pos = pos;
         this.playbackSessionId = playbackSessionId;
         this.elapsedMillis = elapsedMillis;
         this.totalMillis = totalMillis;
         this.ownerId = ownerId;
         this.minecartUuid = minecartUuid;
         this.capturedNanos = capturedNanos;
      } else {
         throw new IllegalArgumentException("mediaUrl must not be blank");
      }
   }

   public PlaybackRequest(
      String mediaUrl, BlockPos pos, String sessionId, long elapsedMillis, long totalMillis, UUID ownerId, UUID minecartUuid, long capturedNanos
   ) {
      this(mediaUrl, pos, PlaybackSessionId.parse(sessionId), elapsedMillis, totalMillis, ownerId, minecartUuid, capturedNanos);
   }

   public static PlaybackRequest now(String mediaUrl, BlockPos pos, String sessionId, long elapsedMillis, long totalMillis, UUID ownerId, UUID minecartUuid) {
      return new PlaybackRequest(mediaUrl, pos, sessionId, elapsedMillis, totalMillis, ownerId, minecartUuid, System.nanoTime());
   }

   public String sessionId() {
      return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
   }

   public float startOffsetSeconds() {
      return (float)this.elapsedMillis / 1000.0F;
   }
}
