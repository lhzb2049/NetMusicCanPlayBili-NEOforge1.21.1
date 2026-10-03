package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareLauncher;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPreparePolicy;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundHandle;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSoundRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPolicy;
import java.util.UUID;
import org.slf4j.Logger;

final class Mp4ClientMediaSyncPolicy implements ClientMediaSyncPolicy {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean PAD_VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();

   @Override
   public ClientMediaPreparePolicy preparePolicy(ClientMediaSyncPayload payload) {
      return (ClientMediaPreparePolicy)(PadClientMediaSessionIds.isPadSession(payload.sessionId())
         ? PadClientMediaPreparePolicy.INSTANCE
         : Mp4ClientMediaPreparePolicy.INSTANCE);
   }

   @Override
   public boolean canHear(UUID sourceId, boolean headphoneRouted) {
      return ClientMediaAudioRouting.canHear(sourceId, headphoneRouted);
   }

   @Override
   public void stop(UUID sourceId) {
      ClientMediaPlaybackSessions.stop(sourceId, deviceId -> MP4HandheldVideoClient.stop(deviceId, "播放已停止"));
   }

   @Override
   public void updateVolume(UUID sourceId, float volume) {
      if (sourceId != null) {
         float clamped = Math.max(0.0F, Math.min(1.0F, volume));
         ClientMediaPlaybackRegistry.computeIfPresent(sourceId, (ignored, active) -> active.withVolume(clamped));
         ClientAudioOutputRegistry.setOwnerVolume(sourceId, ClientMediaPlayback.perceivedGain(clamped));
         ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
         if (sound != null) {
            sound.setMediaVolume(clamped);
         }
      }
   }

   @Override
   public boolean shouldRebuildSound(UUID sourceId, ClientMediaSyncPayload payload) {
      ClientMediaSoundHandle sound = ClientMediaSoundRegistry.get(sourceId);
      if (sound == null || payload.playbackSessionId().isEmpty() || !payload.playbackSessionId().equals(sound.playbackSession()) || sound.stopped()) {
         return true;
      } else if (sound.headphoneRouted() != payload.headphoneRouted()) {
         sound.discardWithoutFinishing();
         ClientMediaSoundRegistry.remove(sourceId);
         return true;
      } else {
         return false;
      }
   }

   @Override
   public void preparePlayback(ClientMediaSyncPayload payload, UUID sourceId) {
      ClientMediaPrepareLauncher.preparePlaybackAsync(
         payload,
         sourceId,
         (ClientMediaPreparePolicy)(PadClientMediaSessionIds.isPadSession(payload.sessionId())
            ? PadClientMediaPreparePolicy.INSTANCE
            : Mp4ClientMediaPreparePolicy.INSTANCE)
      );
   }

   @Override
   public void onSyncReceived(ClientMediaSyncPayload payload, UUID sourceId) {
      if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(payload.sessionId())) {
         LOGGER.info(
            "Pad playback sync received: owner={} source={} type={} playing={} session={} elapsed={}ms headphoneRouted={}",
            new Object[]{
               payload.ownerId(), sourceId, payload.sourceType(), payload.playing(), payload.sessionId(), payload.elapsedMillis(), payload.headphoneRouted()
            }
         );
      }
   }

   @Override
   public void onIgnoredCannotHear(ClientMediaSyncPayload payload, UUID sourceId) {
      LOGGER.trace(
         "MP4 客户端忽略非当前耳机绑定播放: source={} session={} headphoneRouted={} equipped={}",
         new Object[]{sourceId, payload.sessionId(), payload.headphoneRouted(), HeadphoneClientState.equipped()}
      );
   }

   @Override
   public void beforeRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
      if (MP4HandheldVideoClient.isMp4DeviceProfile(sourceId) && !MP4HandheldVideoClient.isDeviceInHotbar(sourceId)) {
         MP4HandheldVideoClient.stop(sourceId, "等待快捷栏");
      }
   }

   @Override
   public void afterRegisterPlayback(ClientMediaSyncPayload payload, UUID sourceId) {
      if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(payload.sessionId())) {
         LOGGER.info(
            "Pad playback active registered: source={} session={} raw='{}' playUrlBlank={} headphoneRouted={}",
            new Object[]{sourceId, payload.sessionId(), payload.rawUrl(), payload.playUrl().isBlank(), payload.headphoneRouted()}
         );
      }
   }

   @Override
   public void onRebuildSound(ClientMediaSyncPayload payload, UUID sourceId) {
      LOGGER.debug(
         "MP4 客户端重建声音实例: source={} session={} headphoneRouted={} elapsed={}ms",
         new Object[]{sourceId, payload.sessionId(), payload.headphoneRouted(), payload.elapsedMillis()}
      );
   }
}
