package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class ControlConsoleVideoStatePolicy {
   private ControlConsoleVideoStatePolicy() {
   }

   public static ControlConsoleVideoStatePolicy.State resolve(boolean sourcePlaying, boolean videoExpected, boolean failed, boolean realFrameAvailable) {
      if (!sourcePlaying || !videoExpected) {
         return ControlConsoleVideoStatePolicy.State.IDLE;
      } else if (failed) {
         return ControlConsoleVideoStatePolicy.State.ERROR;
      } else {
         return realFrameAvailable ? ControlConsoleVideoStatePolicy.State.ACTIVE : ControlConsoleVideoStatePolicy.State.BUFFERING;
      }
   }

   public static enum State {
      IDLE,
      BUFFERING,
      ERROR,
      ACTIVE;
   }
}
