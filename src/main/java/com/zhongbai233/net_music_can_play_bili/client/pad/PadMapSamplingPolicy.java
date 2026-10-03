package com.zhongbai233.net_music_can_play_bili.client.pad;

final class PadMapSamplingPolicy {
   private PadMapSamplingPolicy() {
   }

   static boolean isSurfaceHeightReady(int minY, int surfaceY) {
      return surfaceY >= minY;
   }

   static int cellSizeForZoom(float zoom) {
      return zoom >= 0.75F ? 1 : 4;
   }
}
