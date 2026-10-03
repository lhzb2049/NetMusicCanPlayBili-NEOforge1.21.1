package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record PlaybackSessionId(String value) {
   private static final int MAX_LENGTH = 128;
   private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:~-]+");

   public PlaybackSessionId(String value) {
      value = Objects.requireNonNull(value, "value").trim();
      if (!value.isEmpty() && value.length() <= 128 && SAFE_VALUE.matcher(value).matches()) {
         this.value = value;
      } else {
         throw new IllegalArgumentException("invalid playback session id");
      }
   }

   public static PlaybackSessionId of(String value) {
      return new PlaybackSessionId(value);
   }

   public static Optional<PlaybackSessionId> parse(String raw) {
      if (raw != null && !raw.isBlank()) {
         try {
            return Optional.of(new PlaybackSessionId(raw));
         } catch (IllegalArgumentException var2) {
            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   @Override
   public String toString() {
      return this.value;
   }
}
