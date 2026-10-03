package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class VideoRestartSuppressionPolicy {
   private VideoRestartSuppressionPolicy() {
   }

   static boolean shouldPauseOffscreen(boolean liveSource, boolean pauseEnabled) {
      return pauseEnabled;
   }

   static boolean shouldPauseDecodeOffscreen(boolean candidateCommitted, boolean liveSource, boolean pauseEnabled) {
      return candidateCommitted && shouldPauseOffscreen(liveSource, pauseEnabled);
   }

   static boolean allowsRestart(boolean liveSource, boolean restartInProgress, long sinceDecoderStartMillis, long stabilizationMillis) {
      return !liveSource && !restartInProgress ? stabilizationMillis <= 0L || sinceDecoderStartMillis >= stabilizationMillis : false;
   }
}
