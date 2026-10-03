package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;

public interface AudioOutputHandle extends AutoCloseable {
   default void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks, boolean followLocalPlayerFront) {
      this.tick(machinePos, listenerPos, targetRelativeTicks, followLocalPlayerFront, followLocalPlayerFront);
   }

   void tick(float[] var1, float[] var2, long var3, boolean var5, boolean var6);

   void setUserVolume(float var1);

   default void setPaused(boolean paused) {
   }

   void addRelay(SpeakerAudioRelay var1);

   void removeRelay(SpeakerAudioRelay var1);

   default void setConsoleRouteSuppressed(boolean suppressed) {
   }

   float audioLevel();

   long getPositionTicks();

   long getPositionMillis();

   long getFedPositionMillis();

   long getOutputDelayMillis();

   void hardStopOutput();

   void cleanup();

   @Override
   default void close() {
      this.cleanup();
   }
}
