package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import org.joml.Vector3d;
import org.joml.Vector3f;

public final class ControlConsoleElementPosition {
   public static final double CONSOLE_ELEMENT_BASE_Y = 1.55;

   private ControlConsoleElementPosition() {
   }

   public static Vector3d worldPosition(int consoleX, int consoleY, int consoleZ, ControlConsoleElement element) {
      Vector3f local = element.editorTransform().matrix().transformPosition(new Vector3f());
      return new Vector3d(consoleX + 0.5 + local.x, consoleY + 1.55 + local.y, consoleZ + 0.5 + local.z);
   }
}
