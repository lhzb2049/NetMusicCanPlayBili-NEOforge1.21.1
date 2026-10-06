package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.media.LocalVideoSources;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class ClientMediaPlayback {
   private ClientMediaPlayback() {
   }

   public static boolean hasPlayback(UUID deviceId) {
      return ClientMediaPlaybackRegistry.contains(deviceId);
   }

   public static HandheldMediaPlayback videoPlayback(UUID deviceId) {
      return videoPlayback(deviceId, false);
   }

   public static HandheldMediaPlayback videoPlayback(UUID deviceId, boolean allowAiSubtitle) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      if (active == null) {
         return HandheldMediaPlayback.EMPTY;
      }

      // 阶段 3：本地视频的真实时长只有客户端知道（服务端只会给出 NetMusic 唱片里的时长，
      // 对本地文件通常是 0 或 1 秒）。重封装完成后用真实时长替换，否则设备会「播一下就停」。
      MediaTimelineClock.TimelineSnapshot timeline = active.timelineSnapshot();
      long localMillis = LocalVideoSources.knownDurationMillisForPath(active.rawUrl());
      if (localMillis > 0L && timeline.totalMillis() != localMillis) {
         timeline = new MediaTimelineClock.TimelineSnapshot(
            timeline.playbackSessionId(),
            timeline.mediaMillis(),
            timeline.visualMillis(),
            timeline.pacingMillis(),
            timeline.serverMillis(),
            localMillis,
            timeline.mediaDriftMillis()
         );
      }

      return new HandheldMediaPlayback(active.playbackSessionId(), active.rawUrl(), active.songName(), timeline, allowAiSubtitle);
   }

   public static boolean hasAudioStarted(UUID deviceId, String sessionId) {
      return ClientMediaPlaybackRegistry.hasAudioStarted(deviceId, sessionId);
   }

   public static boolean isCurrent(UUID deviceId, String sessionId) {
      return ClientMediaPlaybackRegistry.isCurrent(deviceId, sessionId);
   }

   public static void markAudioStarted(UUID deviceId, String sessionId, long startOffsetMillis, long totalMillis) {
      ClientMediaPlaybackRegistry.markAudioStarted(deviceId, sessionId, startOffsetMillis, totalMillis);
   }

   public static long elapsedMillis(UUID deviceId, String sessionId, long fallbackMillis) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null && sessionId != null && sessionId.equals(active.sessionId()) ? active.elapsedMillis() : Math.max(0L, fallbackMillis);
   }

   public static Vec3 sourcePosition(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.sourceLocation().position() : null;
   }

   public static boolean headphoneRouted(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null && active.headphoneRouted();
   }

   public static boolean followsLocalPlayerFront(UUID deviceId) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return false;
      } else {
         ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
         return active == null ? false : active.headphoneRouted() || isLocalPlayerSource(deviceId);
      }
   }

   public static boolean isLocalPlayerSource(UUID deviceId) {
      Minecraft minecraft = Minecraft.getInstance();
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return minecraft.player != null
         && active != null
         && active.sourceLocation().sourceType() == 0
         && minecraft.player.getId() == active.sourceLocation().sourceEntityId();
   }

   public static float perceivedGain(float sliderValue) {
      float clamped = Math.max(0.0F, Math.min(1.0F, sliderValue));
      return clamped * clamped;
   }

   public static String songName(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.songName() : "";
   }

   public static int queueIndex(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.queueIndex() : -1;
   }

   public static long elapsedMillis(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.elapsedMillis() : -1L;
   }

   public static long durationMillis(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.durationMillis() : 0L;
   }

   public static String lyricLine(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.lyricLineAtCurrentTime(false) : "";
   }

   public static String translatedLyricLine(UUID deviceId) {
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
      return active != null ? active.lyricLineAtCurrentTime(true) : "";
   }
}
