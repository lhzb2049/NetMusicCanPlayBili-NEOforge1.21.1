package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.util.ArrayList;
import java.util.List;

public final class BiliVideoStreamResolver {
   public static final int DEFAULT_FPS = 30;

   private BiliVideoStreamResolver() {
   }

   public static boolean isStoredVideoSelection(String rawUrl) {
      return BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawUrl)) != null;
   }

   public static BiliApiClient.VideoSelection selectionOrNull(String rawUrl) {
      return BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawUrl));
   }

   public static BiliVideoStreamResolver.ResolvedVideoStream resolve(String rawUrl, int qualityCeiling) throws Exception {
      return resolve(rawUrl, qualityCeiling, 30, "", false, false);
   }

   public static BiliVideoStreamResolver.ResolvedVideoStream resolve(String rawUrl, int qualityCeiling, int fallbackFps) throws Exception {
      return resolve(rawUrl, qualityCeiling, fallbackFps, "", false, false);
   }

   public static BiliVideoStreamResolver.ResolvedVideoStream resolveWithSubtitle(String rawUrl, int qualityCeiling, String title, boolean allowAiSubtitle) throws Exception {
      return resolve(rawUrl, qualityCeiling, 30, title, allowAiSubtitle, true);
   }

   private static BiliVideoStreamResolver.ResolvedVideoStream resolve(
      String rawUrl, int qualityCeiling, int fallbackFps, String fallbackTitle, boolean allowAiSubtitle, boolean includeSubtitle
   ) throws Exception {
      BiliApiClient.VideoSelection selection = selectionOrNull(rawUrl);
      if (selection == null) {
         throw new IllegalArgumentException("不是 B 站视频选择: " + rawUrl);
      } else {
         BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
         BiliApiClient.VideoStreamPlan plan = BiliApiClient.getVideoStreamPlan(selection.videoId(), info.cid(), qualityCeiling);
         BiliApiClient.PlannedVideoCandidate selected = plan.candidateOrder().get(0);
         BiliApiClient.VideoStream stream = selected.stream();
         String title = info.displayTitle() != null && !info.displayTitle().isBlank() ? info.displayTitle() : fallbackTitle;
         LyricRecord subtitle = includeSubtitle ? BiliSubtitleLyricService.tryBuildLyricRecord(rawUrl, title, allowAiSubtitle) : null;
         return new BiliVideoStreamResolver.ResolvedVideoStream(
            stream.baseUrl(),
            stream.codecId(),
            Math.max(1, stream.width()),
            Math.max(1, stream.height()),
            parseFrameRate(stream.frameRate(), fallbackFps),
            stream.quality(),
            title,
            subtitle,
            decodeMode(selected.decodePreference()),
            buildCandidates(plan, fallbackFps)
         );
      }
   }

   static List<BiliVideoStreamResolver.VideoCandidate> buildCandidates(BiliApiClient.VideoStreamPlan plan, int fallbackFps) {
      List<BiliVideoStreamResolver.VideoCandidate> candidates = new ArrayList<>();
      plan.candidateOrder().forEach(candidate -> candidates.add(toCandidate(candidate.stream(), fallbackFps, decodeMode(candidate.decodePreference()))));
      return List.copyOf(candidates);
   }

   private static BiliVideoStreamResolver.DecodeMode decodeMode(BiliApiClient.VideoDecodePreference preference) {
      return switch (preference) {
         case HARDWARE_REQUIRED -> BiliVideoStreamResolver.DecodeMode.HARDWARE_REQUIRED;
         case AUTO -> BiliVideoStreamResolver.DecodeMode.AUTO;
         case SOFTWARE_ONLY -> BiliVideoStreamResolver.DecodeMode.SOFTWARE_ONLY;
      };
   }

   private static BiliVideoStreamResolver.VideoCandidate toCandidate(BiliApiClient.VideoStream stream, int fallbackFps, BiliVideoStreamResolver.DecodeMode mode) {
      return new BiliVideoStreamResolver.VideoCandidate(
         stream.baseUrl(),
         stream.codecId(),
         Math.max(1, stream.width()),
         Math.max(1, stream.height()),
         parseFrameRate(stream.frameRate(), fallbackFps),
         stream.quality(),
         mode
      );
   }

   public static int parseFrameRate(String raw, int fallbackFps) {
      int fallback = Math.max(1, fallbackFps);
      if (raw != null && !raw.isBlank()) {
         String normalized = raw.trim();

         try {
            if (normalized.contains("/")) {
               String[] parts = normalized.split("/", 2);
               double numerator = Double.parseDouble(parts[0].trim());
               double denominator = Double.parseDouble(parts[1].trim());
               return denominator > 0.0 ? Math.max(1, (int)Math.round(numerator / denominator)) : fallback;
            } else {
               return Math.max(1, (int)Math.round(Double.parseDouble(normalized)));
            }
         } catch (NumberFormatException var9) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   public static enum DecodeMode {
      HARDWARE_REQUIRED,
      AUTO,
      SOFTWARE_ONLY;
   }

   public record ResolvedVideoStream(
      String url,
      int codecId,
      int sourceWidth,
      int sourceHeight,
      int fps,
      int quality,
      String title,
      LyricRecord subtitleRecord,
      BiliVideoStreamResolver.DecodeMode decodeMode,
      List<BiliVideoStreamResolver.VideoCandidate> candidates
   ) {
      public ResolvedVideoStream(
         String url,
         int codecId,
         int sourceWidth,
         int sourceHeight,
         int fps,
         int quality,
         String title,
         LyricRecord subtitleRecord,
         BiliVideoStreamResolver.DecodeMode decodeMode,
         List<BiliVideoStreamResolver.VideoCandidate> candidates
      ) {
         candidates = candidates != null && !candidates.isEmpty()
            ? List.copyOf(candidates)
            : List.of(new BiliVideoStreamResolver.VideoCandidate(url, codecId, sourceWidth, sourceHeight, fps, quality));
         this.url = url;
         this.codecId = codecId;
         this.sourceWidth = sourceWidth;
         this.sourceHeight = sourceHeight;
         this.fps = fps;
         this.quality = quality;
         this.title = title;
         this.subtitleRecord = subtitleRecord;
         this.decodeMode = decodeMode;
         this.candidates = candidates;
      }

      public BiliVideoStreamResolver.ResolvedVideoStream withCandidate(BiliVideoStreamResolver.VideoCandidate candidate) {
         return new BiliVideoStreamResolver.ResolvedVideoStream(
            candidate.url(),
            candidate.codecId(),
            candidate.sourceWidth(),
            candidate.sourceHeight(),
            candidate.fps(),
            candidate.quality(),
            this.title,
            this.subtitleRecord,
            candidate.decodeMode(),
            this.candidates
         );
      }
   }

   public record VideoCandidate(String url, int codecId, int sourceWidth, int sourceHeight, int fps, int quality, BiliVideoStreamResolver.DecodeMode decodeMode) {
      public VideoCandidate(String url, int codecId, int sourceWidth, int sourceHeight, int fps, int quality) {
         this(url, codecId, sourceWidth, sourceHeight, fps, quality, BiliVideoStreamResolver.DecodeMode.AUTO);
      }
   }
}
