package com.zhongbai233.net_music_can_play_bili.media.audio;

public final class PcmFrameAlignment {
   private PcmFrameAlignment() {
   }

   public static long alignDown(long bytes, int frameSize) {
      long safeBytes = Math.max(0L, bytes);
      int safeFrameSize = Math.max(1, frameSize);
      return safeBytes - safeBytes % safeFrameSize;
   }

   public static int alignedRequest(long remaining, int bufferLength, int frameSize) {
      if (remaining > 0L && bufferLength > 0) {
         int safeFrameSize = Math.max(1, frameSize);
         long bounded = Math.min(remaining, (long)bufferLength);
         int aligned = (int)alignDown(bounded, safeFrameSize);
         return aligned > 0 ? aligned : (int)bounded;
      } else {
         return 0;
      }
   }
}
