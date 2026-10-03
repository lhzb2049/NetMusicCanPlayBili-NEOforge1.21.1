package com.zhongbai233.net_music_can_play_bili.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class NetworkRateLimiter {
   private static final long WINDOW_NANOS = 1000000000L;
   private static final Map<NetworkRateLimiter.Key, NetworkRateLimiter.Window> WINDOWS = new ConcurrentHashMap<>();

   private NetworkRateLimiter() {
   }

   static boolean allow(UUID playerId, String channel, int maxPerSecond) {
      if (playerId != null && channel != null && !channel.isBlank()) {
         int limit = Math.max(1, maxPerSecond);
         long now = System.nanoTime();
         NetworkRateLimiter.Window window = WINDOWS.computeIfAbsent(
            new NetworkRateLimiter.Key(playerId, channel), ignored -> new NetworkRateLimiter.Window(now)
         );
         synchronized (window) {
            if (now - window.windowStartNanos >= 1000000000L) {
               window.windowStartNanos = now;
               window.count = 0;
            }

            if (window.count >= limit) {
               return false;
            } else {
               window.count++;
               return true;
            }
         }
      } else {
         return false;
      }
   }

   static void removePlayer(UUID playerId) {
      if (playerId != null) {
         WINDOWS.keySet().removeIf(key -> playerId.equals(key.playerId()));
      }
   }

   private record Key(UUID playerId, String channel) {
   }

   private static final class Window {
      private long windowStartNanos;
      private int count;

      private Window(long windowStartNanos) {
         this.windowStartNanos = windowStartNanos;
      }
   }
}
