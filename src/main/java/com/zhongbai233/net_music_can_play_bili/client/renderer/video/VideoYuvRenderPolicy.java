package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class VideoYuvRenderPolicy {
   static final String NO_DEPTH_WRITE_PROPERTY = "ncpb.video.yuv.no_depth_write";

   private VideoYuvRenderPolicy() {
   }

   static boolean useSolidFeatureStage() {
      return true;
   }

   static boolean disableDepthWrite() {
      return VideoPipelineProperties.yuv().depthWriteDisabled();
   }
}
