package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

record DecodedVideoFrame(long frameIndex, long ptsNanos, VideoBillboardState.DecodedFrame frame) {
   void close() {
      this.frame.close();
   }
}
