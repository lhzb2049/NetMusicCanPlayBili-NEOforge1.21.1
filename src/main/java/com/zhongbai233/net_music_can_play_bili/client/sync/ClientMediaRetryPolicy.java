package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

public interface ClientMediaRetryPolicy {
   long retryDelayMillis();

   void scheduleRetry(UUID var1, String var2, ClientMediaPlaybackRegistry.ActivePlayback var3, Throwable var4);

   default boolean tryScheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
      this.scheduleRetry(deviceId, sessionId, active, error);
      return true;
   }

   default void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
   }
}
