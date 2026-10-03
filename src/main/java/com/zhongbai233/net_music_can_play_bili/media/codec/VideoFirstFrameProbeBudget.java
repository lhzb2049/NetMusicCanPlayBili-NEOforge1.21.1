package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.util.concurrent.TimeUnit;

final class VideoFirstFrameProbeBudget {
   private VideoFirstFrameProbeBudget() {
   }

   static VideoFirstFrameProbeBudget.Outcome evaluate(long elapsedNanos, long timeoutMillis, int sentPackets, int maxPackets) {
      return evaluate(false, elapsedNanos, timeoutMillis, sentPackets, maxPackets);
   }

   static VideoFirstFrameProbeBudget.Outcome evaluate(boolean firstFrameReady, long elapsedNanos, long timeoutMillis, int sentPackets, int maxPackets) {
      long safeElapsedNanos = Math.max(0L, elapsedNanos);
      if (timeoutMillis > 0L && safeElapsedNanos >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
         return VideoFirstFrameProbeBudget.Outcome.TIME_EXHAUSTED;
      } else if (firstFrameReady) {
         return VideoFirstFrameProbeBudget.Outcome.WITHIN_BUDGET;
      } else {
         return maxPackets > 0 && Math.max(0, sentPackets) >= maxPackets
            ? VideoFirstFrameProbeBudget.Outcome.PACKET_EXHAUSTED
            : VideoFirstFrameProbeBudget.Outcome.WITHIN_BUDGET;
      }
   }

   static enum Outcome {
      WITHIN_BUDGET,
      TIME_EXHAUSTED,
      PACKET_EXHAUSTED;
   }
}
