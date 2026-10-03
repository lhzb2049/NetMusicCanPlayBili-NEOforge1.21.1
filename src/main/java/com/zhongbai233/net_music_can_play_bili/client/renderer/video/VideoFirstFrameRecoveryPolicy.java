package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class VideoFirstFrameRecoveryPolicy {
   private VideoFirstFrameRecoveryPolicy() {
   }

   static VideoFirstFrameRecoveryPolicy.Decision decide(
      boolean running, boolean hasFrame, boolean restartInProgress, long waitingMillis, long timeoutMillis, int recoveryAttempts, int maxRecoveryAttempts
   ) {
      if (running && !hasFrame && !restartInProgress && timeoutMillis > 0L && waitingMillis >= timeoutMillis) {
         return recoveryAttempts < Math.max(0, maxRecoveryAttempts)
            ? VideoFirstFrameRecoveryPolicy.Decision.RESTART
            : VideoFirstFrameRecoveryPolicy.Decision.FAIL;
      } else {
         return VideoFirstFrameRecoveryPolicy.Decision.WAIT;
      }
   }

   static enum Decision {
      WAIT,
      RESTART,
      FAIL;
   }
}
