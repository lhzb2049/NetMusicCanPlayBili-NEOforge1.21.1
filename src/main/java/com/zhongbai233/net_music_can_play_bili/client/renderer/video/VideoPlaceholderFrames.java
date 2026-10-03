package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.resources.ResourceLocation;

final class VideoPlaceholderFrames {
   static final int WIDTH = 320;
   static final int HEIGHT = 180;
   static final double IRIS_VIEW_DEPTH_OFFSET = VideoPipelineProperties.presentation().irisWarningViewDepthOffset();
   static final float IRIS_LOCAL_DEPTH_OFFSET = VideoPipelineProperties.presentation().irisWarningLocalDepthOffset();
   static final boolean NETWORK_ERROR_ENABLED = VideoPipelineProperties.networkErrorPlaceholderEnabled();
   private static final ResourceLocation[] LOADING_TEXTURES = new ResourceLocation[]{
      texture("loading_base_phase0.png"), texture("loading_base_phase1.png"), texture("loading_base_phase2.png"), texture("loading_base_phase3.png")
   };
   private static final ResourceLocation IRIS_WARNING = texture("iris_translucent_warning_base.png");
   private static final ResourceLocation NETWORK_ERROR = texture("network_error_base.png");
   private static final ResourceLocation IDLE = texture("idle_base.png");

   private VideoPlaceholderFrames() {
   }

   static VideoBillboardState.ProjectorFrameSnapshot snapshot(VideoPlaceholderFrames.Kind kind, long startedNanoTime) {
      if (kind == VideoPlaceholderFrames.Kind.LOADING) {
         return loading(startedNanoTime);
      } else {
         boolean irisWarning = kind == VideoPlaceholderFrames.Kind.IRIS_WARNING;
         return new VideoBillboardState.ProjectorFrameSnapshot(
            true,
            false,
            texture(kind, startedNanoTime),
            null,
            null,
            null,
            Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
            320,
            180,
            !irisWarning,
            kind == VideoPlaceholderFrames.Kind.LOADING,
            irisWarning ? IRIS_LOCAL_DEPTH_OFFSET : 0.0F
         );
      }
   }

   static VideoBillboardState.ProjectorFrameSnapshot loading(long startedNanoTime) {
      return new VideoBillboardState.ProjectorFrameSnapshot(
         true,
         false,
         texture(VideoPlaceholderFrames.Kind.LOADING, startedNanoTime),
         null,
         null,
         null,
         Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
         320,
         180,
         true,
         true,
         0.0F
      );
   }

   static VideoBillboardState.ProjectorFrameSnapshot idle() {
      return new VideoBillboardState.ProjectorFrameSnapshot(
         true, false, IDLE, null, null, null, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, 320, 180, true, false, 0.0F
      );
   }

   static ResourceLocation texture(VideoPlaceholderFrames.Kind kind, long startedNanoTime) {
      if (kind == VideoPlaceholderFrames.Kind.NETWORK_ERROR) {
         return NETWORK_ERROR;
      } else if (kind == VideoPlaceholderFrames.Kind.IRIS_WARNING) {
         return IRIS_WARNING;
      } else {
         long elapsedNs = Math.max(0L, System.nanoTime() - startedNanoTime);
         return LOADING_TEXTURES[(int)(elapsedNs / 300000000L % LOADING_TEXTURES.length)];
      }
   }

   private static ResourceLocation texture(String name) {
      return ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "textures/gui/video_loading/" + name);
   }

   static enum Kind {
      LOADING,
      IRIS_WARNING,
      NETWORK_ERROR;
   }
}
