package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;

public final class ControlConsoleAudioRoutePolicy {
   private ControlConsoleAudioRoutePolicy() {
   }

   public static boolean takesOverMainOutput(ControlConsoleDocument document) {
      return document != null && document.hasSourceBinding() && document.sourceKind() == ControlConsoleDocument.SourceKind.TURNTABLE;
   }
}
