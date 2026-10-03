package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

public interface ClientMediaSoundLifecyclePolicy {
   void registerSound(UUID var1, String var2, ClientMediaSoundHandle var3);

   default boolean tryRegisterSound(UUID deviceId, String sessionId, ClientMediaSoundHandle sound) {
      this.registerSound(deviceId, sessionId, sound);
      return true;
   }

   boolean recoverAfterStreamFailure(UUID var1, String var2, Throwable var3);

   void onCompleted(UUID var1, String var2);

   void finish(UUID var1, String var2);
}
