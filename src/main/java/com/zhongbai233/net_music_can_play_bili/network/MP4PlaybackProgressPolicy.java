package com.zhongbai233.net_music_can_play_bili.network;

final class MP4PlaybackProgressPolicy {
   private MP4PlaybackProgressPolicy() {
   }

   static long currentElapsed(MP4PlaybackProgressPolicy.RuntimeProgress runtime, Long persistedElapsed, long fallback) {
      if (runtime != null) {
         return Math.max(0L, runtime.elapsedMillis());
      } else {
         return persistedElapsed != null ? Math.max(0L, persistedElapsed) : Math.max(0L, fallback);
      }
   }

   static long queueElapsed(int queueIndex, MP4PlaybackProgressPolicy.RuntimeProgress runtime, long persistedOrFallback) {
      return runtime != null && runtime.queueIndex() == queueIndex ? Math.max(0L, runtime.elapsedMillis()) : Math.max(0L, persistedOrFallback);
   }

   static long elapsedFromProgress(int durationSeconds, int progressPerMille) {
      if (durationSeconds <= 0) {
         return 0L;
      } else {
         long durationMillis = durationSeconds * 1000L;
         return Math.round(progressPerMille / 1000.0 * durationMillis);
      }
   }

   static long clampTarget(int durationSeconds, long elapsedMillis) {
      if (durationSeconds <= 0) {
         return 0L;
      } else {
         long max = Math.max(0L, durationSeconds * 1000L - 50L);
         return Math.max(0L, Math.min(max, elapsedMillis));
      }
   }

   static int progressPerMille(long elapsedMillis, int durationSeconds) {
      long durationMillis = Math.max(1L, Math.max(1, durationSeconds) * 1000L);
      long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
      return Math.max(0, Math.min(1000, (int)Math.round(elapsed * 1000.0 / durationMillis)));
   }

   record RuntimeProgress(int queueIndex, long elapsedMillis) {
   }
}
