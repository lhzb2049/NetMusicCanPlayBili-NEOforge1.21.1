package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PlaybackSourceId(UUID value) {
   public PlaybackSourceId(UUID value) {
      Objects.requireNonNull(value, "value");
      this.value = value;
   }

   public static PlaybackSourceId of(UUID value) {
      return new PlaybackSourceId(value);
   }

   public static Optional<PlaybackSourceId> parse(String raw) {
      if (raw != null && !raw.isBlank()) {
         try {
            return Optional.of(new PlaybackSourceId(UUID.fromString(raw.trim())));
         } catch (IllegalArgumentException var2) {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   @Override
   public String toString() {
      return this.value.toString();
   }
}
