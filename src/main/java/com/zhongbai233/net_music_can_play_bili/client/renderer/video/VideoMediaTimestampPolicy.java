package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class VideoMediaTimestampPolicy {
   private VideoMediaTimestampPolicy() {
   }

   static long absoluteMillis(long frameBaseOffsetMillis, long framePtsNanos, long latencyCompensationMillis, long totalMillis) {
      if (frameBaseOffsetMillis >= 0L && framePtsNanos >= 0L) {
         long value = Math.max(0L, frameBaseOffsetMillis + framePtsNanos / 1000000L - latencyCompensationMillis);
         return totalMillis > 0L ? Math.min(totalMillis, value) : value;
      } else {
         return -1L;
      }
   }
}
