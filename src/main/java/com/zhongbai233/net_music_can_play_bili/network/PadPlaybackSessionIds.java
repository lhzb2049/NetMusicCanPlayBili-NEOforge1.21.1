package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.UUID;

public final class PadPlaybackSessionIds {
   private static final String MARKER = "-pad-";
   private static final int UUID_LENGTH = 36;
   private static final int POINT_START = 36 + "-pad-".length();
   private static final int GENERATION_SEPARATOR = POINT_START + 36;

   private PadPlaybackSessionIds() {
   }

   public static PlaybackSessionId create(UUID deviceId, UUID pointId, long generation) {
      if (deviceId != null && pointId != null) {
         return PlaybackSessionId.of(deviceId + "-pad-" + pointId + "-" + Math.max(0L, generation));
      } else {
         throw new IllegalArgumentException("Pad playback session requires device and point ids");
      }
   }

   public static boolean isPadSession(String sessionId) {
      return pointId(sessionId) != null;
   }

   public static UUID pointId(String sessionId) {
      if (!hasValidShape(sessionId)) {
         return null;
      } else {
         try {
            UUID.fromString(sessionId.substring(0, 36));
            UUID pointId = UUID.fromString(sessionId.substring(POINT_START, GENERATION_SEPARATOR));
            long generation = Long.parseLong(sessionId.substring(GENERATION_SEPARATOR + 1));
            return generation >= 0L ? pointId : null;
         } catch (IllegalArgumentException var4) {
            return null;
         }
      }
   }

   public static boolean matches(String sessionId, UUID deviceId, UUID pointId) {
      return sessionId != null && deviceId != null && pointId != null && sessionId.startsWith(deviceId + "-pad-") ? pointId.equals(pointId(sessionId)) : false;
   }

   private static boolean hasValidShape(String sessionId) {
      return sessionId != null
         && sessionId.length() > GENERATION_SEPARATOR + 1
         && sessionId.startsWith("-pad-", 36)
         && sessionId.charAt(GENERATION_SEPARATOR) == '-';
   }
}
