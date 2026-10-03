package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.config.GeneralConfig;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPreparePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareProperties;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import java.net.URL;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.slf4j.Logger;

final class PadClientMediaPreparePolicy implements ClientMediaPreparePolicy {
   static final PadClientMediaPreparePolicy INSTANCE = new PadClientMediaPreparePolicy();
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean PAD_VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
   private static final long PREPARE_TIMEOUT_SECONDS = ClientMediaPrepareProperties.settings().padPrepareTimeoutSeconds();

   private PadClientMediaPreparePolicy() {
   }

   @Override
   public long prepareTimeoutSeconds() {
      return PREPARE_TIMEOUT_SECONDS;
   }

   @Override
   public boolean canHear(UUID sourceId, boolean headphoneRouted) {
      return ClientMediaAudioRouting.canHear(sourceId, headphoneRouted);
   }

   @Override
   public void stop(UUID sourceId) {
      ClientMediaPlaybackSessions.stop(sourceId, deviceId -> MP4HandheldVideoClient.stop(deviceId, "Pad 播放已停止"));
   }

   @Override
   public boolean shouldLoadLyrics(ClientMediaSyncPayload payload, UUID sourceId) {
      UUID localPlayerId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getUUID() : null;
      return localPlayerId != null && localPlayerId.equals(payload.ownerId()) && (Boolean)GeneralConfig.ENABLE_PLAYER_LYRICS.get();
   }

   @Override
   public String lyricLogLabel() {
      return "Pad";
   }

   @Override
   public SoundInstance createSound(UUID sourceId, ClientMediaSyncPayload payload, URL url, LyricRecord lyricRecord, long startOffsetMillis) {
      return new ClientMediaMovingSound(
         sourceId,
         url,
         payload.durationSeconds(),
         lyricRecord,
         payload.sessionId(),
         startOffsetMillis,
         payload.volumePerMille() / 1000.0F,
         payload.headphoneRouted(),
         PadMediaSoundLifecyclePolicy.INSTANCE,
         "Pad"
      );
   }

   @Override
   public void onPrepareDuplicate(ClientMediaSyncPayload payload, UUID sourceId) {
      if (PAD_VIDEO_DEBUG_LOG) {
         LOGGER.info(
            "Pad playback prepare skipped: duplicate source={} session={} headphoneRouted={}",
            new Object[]{sourceId, payload.sessionId(), payload.headphoneRouted()}
         );
      }
   }

   @Override
   public void onPrepareScheduled(ClientMediaSyncPayload payload, UUID sourceId) {
      if (PAD_VIDEO_DEBUG_LOG) {
         LOGGER.info(
            "Pad playback prepare scheduled: source={} session={} raw='{}' playUrlHost={} elapsed={}ms duration={}s",
            new Object[]{
               sourceId,
               payload.sessionId(),
               payload.rawUrl(),
               ClientMediaPreparer.hostOf(payload.playUrl()),
               payload.elapsedMillis(),
               payload.durationSeconds()
            }
         );
      }
   }

   @Override
   public void onPrepareCompleted(ClientMediaSyncPayload payload, UUID sourceId, ClientMediaPreparer.PreparedMedia prepared, long costMillis) {
      if (PAD_VIDEO_DEBUG_LOG) {
         LOGGER.info(
            "Pad playback prepare completed: source={} session={} cost={}ms host={}",
            new Object[]{sourceId, payload.sessionId(), costMillis, prepared != null ? ClientMediaPreparer.hostOf(prepared.playUrl()) : "unknown"}
         );
      }
   }
}
