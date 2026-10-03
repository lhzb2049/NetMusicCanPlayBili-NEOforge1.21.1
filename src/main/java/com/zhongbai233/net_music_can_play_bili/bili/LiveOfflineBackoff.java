package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.concurrent.ConcurrentHashMap;

public final class LiveOfflineBackoff {
   private static final long RETRY_MILLIS = BiliApiProperties.liveOfflineRetryMillis();
   private static final ConcurrentHashMap<String, Long> BLOCKED_UNTIL = new ConcurrentHashMap<>();

   private LiveOfflineBackoff() {
   }

   public static void recordOffline(String roomId) {
      recordOffline(roomId, System.currentTimeMillis());
   }

   static void recordOffline(String roomId, long nowMillis) {
      if (roomId != null && !roomId.isBlank()) {
         BLOCKED_UNTIL.entrySet().removeIf(entry -> nowMillis >= entry.getValue());
         BLOCKED_UNTIL.put(roomId, saturatedAdd(nowMillis, RETRY_MILLIS));
      }
   }

   public static boolean isBlocked(String roomId) {
      return isBlocked(roomId, System.currentTimeMillis());
   }

   static boolean isBlocked(String roomId, long nowMillis) {
      if (roomId != null && !roomId.isBlank()) {
         Long until = BLOCKED_UNTIL.get(roomId);
         if (until == null) {
            return false;
         } else if (nowMillis >= until) {
            BLOCKED_UNTIL.remove(roomId, until);
            return false;
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public static void clear(String roomId) {
      if (roomId != null && !roomId.isBlank()) {
         BLOCKED_UNTIL.remove(roomId);
      }
   }

   public static long retryMillis() {
      return RETRY_MILLIS;
   }

   private static long saturatedAdd(long value, long increment) {
      try {
         return Math.addExact(value, increment);
      } catch (ArithmeticException var5) {
         return increment >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
      }
   }
}
