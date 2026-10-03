package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import java.net.URL;
import java.util.UUID;
import net.minecraft.client.resources.sounds.SoundInstance;

public interface ClientMediaPreparePolicy {
   long prepareTimeoutSeconds();

   boolean canHear(UUID var1, boolean var2);

   void stop(UUID var1);

   default boolean allowDolby(ClientMediaSyncPayload payload, UUID sourceId) {
      return true;
   }

   boolean shouldLoadLyrics(ClientMediaSyncPayload var1, UUID var2);

   default long startOffsetMillis(ClientMediaSyncPayload payload, ClientMediaPlaybackRegistry.ActivePlayback current) {
      return current != null ? current.elapsedMillis() : Math.max(0L, payload.elapsedMillis());
   }

   default long totalMillis(ClientMediaSyncPayload payload, ClientMediaPlaybackRegistry.ActivePlayback current) {
      long currentTotal = current != null ? current.durationMillis() : 0L;
      return currentTotal > 0L ? currentTotal : Math.max(0L, (long)payload.durationSeconds()) * 1000L;
   }

   String lyricLogLabel();

   SoundInstance createSound(UUID var1, ClientMediaSyncPayload var2, URL var3, LyricRecord var4, long var5);

   default void onPrepareDuplicate(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onPrepareScheduled(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onPrepareStarted(ClientMediaSyncPayload payload, UUID sourceId, boolean loadLyrics) {
   }

   default void onPrepareCompleted(ClientMediaSyncPayload payload, UUID sourceId, ClientMediaPreparer.PreparedMedia prepared, long costMillis) {
   }

   default void onPrepareFailed(ClientMediaSyncPayload payload, UUID sourceId, Throwable error) {
   }

   default void onPrepareTimeout(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onPrepareCancelledCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
   }

   default void onLaunch(ClientMediaSyncPayload payload, UUID sourceId, long startOffsetMillis, String playUrl) {
   }
}
