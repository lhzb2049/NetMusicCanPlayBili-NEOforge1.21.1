package com.zhongbai233.net_music_can_play_bili.media.audio;

final class OpenAlPauseStatePolicy {
   private OpenAlPauseStatePolicy() {
   }

   static OpenAlPauseStatePolicy.Action action(boolean pause, OpenAlPauseStatePolicy.SourceState sourceState, int queuedBuffers) {
      if (pause) {
         return sourceState == OpenAlPauseStatePolicy.SourceState.PLAYING ? OpenAlPauseStatePolicy.Action.PAUSE : OpenAlPauseStatePolicy.Action.NONE;
      } else {
         return sourceState != OpenAlPauseStatePolicy.SourceState.PAUSED && (sourceState != OpenAlPauseStatePolicy.SourceState.STOPPED || queuedBuffers <= 0)
            ? OpenAlPauseStatePolicy.Action.NONE
            : OpenAlPauseStatePolicy.Action.PLAY;
      }
   }

   static enum Action {
      NONE,
      PAUSE,
      PLAY;
   }

   static enum SourceState {
      PLAYING,
      PAUSED,
      STOPPED,
      OTHER;
   }
}
