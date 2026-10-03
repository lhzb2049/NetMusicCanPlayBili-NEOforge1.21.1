package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;

final class ControlConsoleVideoArtwork {
   private static final String ROOT = "textures/gui/control_console_video/";

   private ControlConsoleVideoArtwork() {
   }

   static String texturePath(ControlConsoleVideoStatePolicy.State state) {
      return switch (state) {
         case IDLE -> "textures/gui/control_console_video/idle.png";
         case BUFFERING -> "textures/gui/control_console_video/buffering.png";
         case ERROR -> "textures/gui/control_console_video/error.png";
         case ACTIVE -> throw new IllegalArgumentException("ACTIVE control-console video requires a real frame");
      };
   }

   static boolean loadingProgressOverlay(ControlConsoleVideoStatePolicy.State state) {
      return state == ControlConsoleVideoStatePolicy.State.BUFFERING;
   }
}
