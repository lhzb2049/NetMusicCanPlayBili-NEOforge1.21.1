package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

public interface HandheldMediaDeviceProfile {
   HandheldMediaScreenSpec screenSpec();

   HandheldMediaPlayback playback(UUID var1);

   HandheldMediaRenderState renderState(UUID var1);

   boolean hasStartedSound(UUID var1, String var2);

   default boolean canStartVideoDecode(UUID deviceId, HandheldMediaPlayback playback) {
      return playback != null && this.hasStartedSound(deviceId, playback.sessionId());
   }

   boolean isDeviceAvailable(UUID var1);

   String subtitleMode(UUID var1);
}
