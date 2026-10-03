package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

public final class VideoSurfacePrivacyPolicy {
   private VideoSurfacePrivacyPolicy() {
   }

   public static boolean hideVideo(boolean privacyActive, VideoSurfacePrivacyPolicy.SurfaceKind surfaceKind) {
      return privacyActive && surfaceKind != VideoSurfacePrivacyPolicy.SurfaceKind.PRIVATE_HOLOGRAPHIC;
   }

   public static enum SurfaceKind {
      PUBLIC_PROJECTOR,
      CONTROL_CONSOLE,
      PRIVATE_HOLOGRAPHIC;
   }
}
