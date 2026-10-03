package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

public final class ClientMediaRetryHandler {
   private static final ClientMediaRetryRegistry PENDING_RETRIES = new ClientMediaRetryRegistry();

   private ClientMediaRetryHandler() {
   }

   public static boolean retryAfterStreamFailure(UUID deviceId, String sessionId, Throwable error, ClientMediaRetryPolicy policy) {
      PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      return playbackSessionId != null && retryAfterStreamFailure(deviceId, playbackSessionId, error, policy);
   }

   public static boolean retryAfterStreamFailure(UUID deviceId, PlaybackSessionId sessionId, Throwable error, ClientMediaRetryPolicy policy) {
      if (deviceId != null && sessionId != null && policy != null) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null) {
            return false;
         } else {
            ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
            if (active == null || !active.playbackSessionId().filter(sessionId::equals).isPresent()) {
               return false;
            } else if (!markPending(deviceId, sessionId)) {
               return false;
            } else {
               ClientMediaPlaybackRegistry.ActivePlayback admitted = ClientMediaPlaybackRegistry.get(deviceId);
               if (admitted != null && admitted.playbackSessionId().filter(sessionId::equals).isPresent()) {
                  policy.onRetryScheduled(deviceId, sessionId.value(), admitted, error);
                  CompletableFuture.delayedExecutor(Math.max(0L, policy.retryDelayMillis()), TimeUnit.MILLISECONDS)
                     .execute(() -> Minecraft.getInstance().execute(() -> {
                        PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
                        PENDING_RETRIES.dispatchIfPending(sourceId, sessionId, () -> {
                           ClientMediaPlaybackRegistry.ActivePlayback current = ClientMediaPlaybackRegistry.get(deviceId);
                           if (current != null && current.playbackSessionId().filter(sessionId::equals).isPresent()) {
                              return ClientMediaRetryDispatch.dispatch(policy, deviceId, sessionId, current, error, () -> {
                                 if (PENDING_RETRIES.forget(sourceId, sessionId)) {
                                    ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
                                 }
                              });
                           } else {
                              PENDING_RETRIES.forget(sourceId, sessionId);
                              return false;
                           }
                        });
                     }));
                  return true;
               } else {
                  PENDING_RETRIES.forget(PlaybackSourceId.of(deviceId), sessionId);
                  return false;
               }
            }
         }
      } else {
         return false;
      }
   }

   public static boolean isPending(UUID deviceId, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      return parsedSessionId != null && isPending(deviceId, parsedSessionId);
   }

   public static boolean isPending(UUID deviceId, PlaybackSessionId sessionId) {
      return deviceId != null && sessionId != null && PENDING_RETRIES.contains(PlaybackSourceId.of(deviceId), sessionId);
   }

   static void onSessionAccepted(UUID deviceId, PlaybackSessionId sessionId) {
      if (deviceId != null && sessionId != null) {
         PENDING_RETRIES.forgetSource(PlaybackSourceId.of(deviceId));
      }
   }

   static boolean onSessionRefreshed(UUID deviceId, PlaybackSessionId sessionId) {
      return deviceId != null && sessionId != null && PENDING_RETRIES.forget(PlaybackSourceId.of(deviceId), sessionId);
   }

   static void removePendingForDevice(UUID deviceId) {
      if (deviceId != null) {
         PENDING_RETRIES.forgetSource(PlaybackSourceId.of(deviceId));
      }
   }

   static void clearPending() {
      PENDING_RETRIES.clear();
   }

   private static boolean markPending(UUID deviceId, PlaybackSessionId sessionId) {
      return sessionId != null && PENDING_RETRIES.tryMark(PlaybackSourceId.of(deviceId), sessionId);
   }
}
