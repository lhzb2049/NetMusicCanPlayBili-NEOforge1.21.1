package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class VideoBerConsumerVisibilityPolicy {
   private VideoBerConsumerVisibilityPolicy() {
   }

   static boolean usesBerSubmission(boolean berManagedProjector, boolean recentlySubmittedByBer) {
      return berManagedProjector || recentlySubmittedByBer;
   }
}
