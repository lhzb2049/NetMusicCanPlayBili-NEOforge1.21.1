package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.client.MP4BiliLoginOverlay;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

record MP4GuiViewState(
   UUID deviceId,
   boolean landscape,
   boolean transparentLandscapeVideoOverlay,
   boolean videoEnabled,
   boolean playing,
   boolean hasVideoFrame,
   boolean controlsVisible,
   boolean lyricsEnabled,
   boolean playlistOpen,
   boolean subtitleMenuOpen,
   boolean qualityMenuOpen,
   boolean subtitlePrimaryMode,
   boolean subtitleAiEnabled,
   boolean shuffle,
   int repeatMode,
   float mediaProgress,
   float volume,
   String quality,
   int queueSize,
   int selectedQueueIndex,
   int queueScrollOffset,
   int portraitQueueVisibleRows,
   int landscapeQueueVisibleRows,
   List<String> queueTitles,
   List<String> qualities,
   String songTitle,
   String songSubtitle,
   long elapsedMillis,
   long durationMillis,
   String lyricLine,
   String translatedLyricLine,
   boolean audioOnly,
   String videoStatusText,
   String videoSubtitle,
   String videoResolutionLabel,
   boolean biliLoginVisible,
   boolean biliLoginActive,
   BufferedImage biliQrImage,
   String biliLoginStatusText,
   String hoverControlName,
   int ticks
) {
   static MP4GuiViewState capture(UUID deviceId) {
      boolean landscape = MP4FocusState.visualLandscape(1.0F);
      boolean videoEnabled = MP4FocusState.videoEnabled();
      boolean playing = MP4FocusState.playing();
      boolean hasVideoFrame = videoEnabled && playing && MP4HandheldVideoClient.latestFrame(deviceId) != null;
      boolean biliLoginVisible = MP4BiliLoginOverlay.visible();
      boolean controlsVisible = MP4FocusState.controlsVisible();
      boolean lyricsEnabled = MP4FocusState.lyricsEnabled();
      int queueSize = MP4FocusState.queueSize();
      int selectedQueueIndex = MP4FocusState.selectedQueueIndex();
      int queueScrollOffset = MP4FocusState.queueScrollOffset();
      float mediaProgress = MP4FocusState.mediaProgress();
      String songTitle = currentSongTitle(deviceId, selectedQueueIndex);
      ClientMediaTimelineView timeline = currentTimeline(deviceId, mediaProgress);
      long durationMillis = timeline.totalMillis();
      return new MP4GuiViewState(
         deviceId,
         landscape,
         landscape && hasVideoFrame && !biliLoginVisible,
         videoEnabled,
         playing,
         hasVideoFrame,
         controlsVisible,
         lyricsEnabled,
         MP4FocusState.playlistOpen(),
         MP4FocusState.subtitleMenuOpen(),
         MP4FocusState.qualityMenuOpen(),
         MP4FocusState.subtitlePrimaryMode(),
         MP4FocusState.subtitleAiEnabled(),
         MP4FocusState.shuffle(),
         MP4FocusState.repeatMode(),
         mediaProgress,
         MP4FocusState.volume(),
         MP4FocusState.quality(),
         queueSize,
         selectedQueueIndex,
         queueScrollOffset,
         6,
         3,
         captureQueueTitles(queueSize, queueScrollOffset),
         List.of(MP4FocusState.QUALITIES),
         songTitle,
         currentSongSubtitle(queueSize, selectedQueueIndex),
         timeline.mediaMillis(),
         durationMillis,
         stringOrEmpty(ClientMediaPlayback.lyricLine(deviceId)),
         stringOrEmpty(ClientMediaPlayback.translatedLyricLine(deviceId)),
         MP4HandheldVideoClient.audioOnly(deviceId),
         stringOrEmpty(MP4HandheldVideoClient.statusText(deviceId)),
         stringOrEmpty(MP4HandheldVideoClient.currentSubtitle(deviceId)),
         currentVideoResolutionLabel(deviceId),
         biliLoginVisible,
         BiliApiClient.sessdata != null && !BiliApiClient.sessdata.isBlank(),
         MP4BiliLoginOverlay.qrImage(),
         stringOrEmpty(MP4BiliLoginOverlay.statusText()),
         stringOrEmpty(MP4FocusState.hoverControlName()),
         MP4FocusState.ticks()
      );
   }

   String queueTitle(int absoluteIndex) {
      int localIndex = absoluteIndex - this.queueScrollOffset;
      return localIndex >= 0 && localIndex < this.queueTitles.size() ? this.queueTitles.get(localIndex) : "";
   }

   boolean playbackModeActive() {
      return this.shuffle || this.repeatMode > 0;
   }

   String playbackModeLabel() {
      if (this.shuffle) {
         return "随机";
      } else {
         return switch (this.repeatMode) {
            case 1 -> "列表循环";
            case 2 -> "单曲循环";
            default -> "顺序";
         };
      }
   }

   private static String currentSongTitle(UUID deviceId, int selectedQueueIndex) {
      String active = ClientMediaPlayback.songName(deviceId);
      return active != null && !active.isBlank() ? active : MP4FocusState.queueTitle(selectedQueueIndex);
   }

   private static String currentSongSubtitle(int queueSize, int selectedQueueIndex) {
      return queueSize <= 0 ? "把 NetMusic 唱片放进 MP4" : "NetMusic 唱片 · " + (selectedQueueIndex + 1) + "/" + queueSize;
   }

   private static ClientMediaTimelineView currentTimeline(UUID deviceId, float mediaProgress) {
      long fallbackTotalMillis = MP4FocusState.selectedTrackDurationMillis();
      long fallbackMillis = Math.round(mediaProgress * (float)Math.max(0L, fallbackTotalMillis));
      return ClientMediaTimelineView.forHandheldOwner(deviceId, MP4HandheldMediaProfile.INSTANCE, fallbackMillis, fallbackTotalMillis);
   }

   private static String currentVideoResolutionLabel(UUID deviceId) {
      String resolution = MP4HandheldVideoClient.currentResolutionLabel(deviceId);
      return resolution.isBlank() ? MP4FocusState.quality() : resolution;
   }

   private static List<String> captureQueueTitles(int queueSize, int queueScrollOffset) {
      int rows = Math.max(6, 3);
      List<String> titles = new ArrayList<>(rows);

      for (int i = 0; i < rows; i++) {
         int index = queueScrollOffset + i;
         if (index >= queueSize) {
            break;
         }

         titles.add(MP4FocusState.queueTitle(index));
      }

      return List.copyOf(titles);
   }

   private static String stringOrEmpty(String value) {
      return value != null ? value : "";
   }
}
