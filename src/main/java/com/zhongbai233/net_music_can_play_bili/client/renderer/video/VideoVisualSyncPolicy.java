package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

public final class VideoVisualSyncPolicy {
   private VideoVisualSyncPolicy() {
   }

   public static boolean active(boolean running, boolean terminalFailure, boolean prewarmVisible, boolean offscreenPaused) {
      return running && !terminalFailure && prewarmVisible && !offscreenPaused;
   }

   public static String debugStatus(boolean active) {
      return active ? "ACTIVE" : "SUSPENDED_OFFSCREEN";
   }
}
