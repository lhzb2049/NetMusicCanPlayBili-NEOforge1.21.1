package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;

public final class ControlConsoleRangeGate {
   public static final double FADE_BAND = 4.0;
   public static final double REENTRY_INSET = 2.0;

   private ControlConsoleRangeGate() {
   }

   public static ControlConsoleRangeGate.Result evaluate(
      boolean previouslyActive, double relativeX, double relativeY, double relativeZ, double halfX, double halfY, double halfZ
   ) {
      AudioPlaybackRange.ZoneResult result = AudioPlaybackRange.evaluateAabb(previouslyActive, relativeX, relativeY, relativeZ, halfX, halfY, halfZ);
      return new ControlConsoleRangeGate.Result(result.active(), result.gain());
   }

   public record Result(boolean active, float gain) {
   }
}
