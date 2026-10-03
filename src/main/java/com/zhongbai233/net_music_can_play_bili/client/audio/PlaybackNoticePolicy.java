package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;

public final class PlaybackNoticePolicy {
   private PlaybackNoticePolicy() {
   }

   public static boolean isWithinNoticeRange(float distance, float volume) {
      return AudioPlaybackRange.evaluateSphere(distance, 64.0F, volume, false).noticeActive();
   }
}
