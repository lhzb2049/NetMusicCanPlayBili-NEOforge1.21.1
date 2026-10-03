package com.zhongbai233.net_music_can_play_bili.client.sync;

import java.util.UUID;

public interface ClientMediaSyncPolicy {
   default ClientMediaPreparePolicy preparePolicy(ClientMediaSyncPayload payload) {
      return null;
   }

   boolean canHear(UUID var1, boolean var2);

   void stop(UUID var1);

   void updateVolume(UUID var1, float var2);

   boolean shouldRebuildSound(UUID var1, ClientMediaSyncPayload var2);

   void preparePlayback(ClientMediaSyncPayload var1, UUID var2);

   default void onSyncReceived(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onIgnoredCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void beforeRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void afterRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onRebuildSound(ClientMediaSyncPayload payload, UUID sourceId) {
   }
}
