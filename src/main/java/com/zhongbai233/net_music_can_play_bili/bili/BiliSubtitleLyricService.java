package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.util.List;
import org.slf4j.Logger;

public final class BiliSubtitleLyricService {
   private static final Logger LOGGER = LogUtils.getLogger();

   private BiliSubtitleLyricService() {
   }

   public static LyricRecord tryBuildLyricRecord(String rawInput, String songName) {
      return tryBuildLyricRecord(rawInput, songName, false);
   }

   public static LyricRecord tryBuildLyricRecord(String rawInput, String songName, boolean allowAi) {
      BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(PlaybackSync.strip(rawInput));
      if (selection == null) {
         return null;
      } else {
         try {
            BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
            String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(info, allowAi);
            if (lyricJson != null && !lyricJson.isBlank()) {
               LyricRecord record = LyricParser.parseLyric(lyricJson, songName);
               if (record != null) {
                  return record;
               }

               LOGGER.warn("B站 CC 字幕解析失败：LyricParser 返回 null");
            }

            boolean hasAnySubtitle = false;

            try {
               List<BiliApiClient.SubtitleInfo> rawSubs = BiliApiClient.getAllSubtitles(info);
               hasAnySubtitle = rawSubs != null && !rawSubs.isEmpty();
            } catch (Exception var10) {
               LOGGER.debug("B站字幕轨道列表查询失败，将按无字幕生成占位歌词: video={} page={}", new Object[]{selection.videoId(), selection.page(), var10});
            }

            String note;
            if (BiliApiClient.sessdata.isBlank()) {
               note = "字幕需登录B站账号";
            } else if (hasAnySubtitle) {
               note = "无可用CC字幕";
            } else {
               note = "无CC字幕";
            }

            String placeholderJson = BiliApiClient.buildPlaceholderNetEaseLyric(info, note);
            LyricRecord record = LyricParser.parseLyric(placeholderJson, songName);
            if (record != null) {
               LOGGER.debug(
                  "B站字幕摘要: title='{}' page={} allowAi={} result=placeholder reason={} hasAnySubtitle={} sessdata={}",
                  new Object[]{info.displayTitle(), info.page(), allowAi, note, hasAnySubtitle, !BiliApiClient.sessdata.isBlank()}
               );
               return record;
            } else {
               return null;
            }
         } catch (Exception var11) {
            LOGGER.warn("B站 CC 字幕获取失败: {}", var11.getMessage());
            return null;
         }
      }
   }

   public static LyricRecord buildAiLyricRecord(String rawInput, String songName) throws Exception {
      String stored = PlaybackSync.strip(rawInput);
      BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(stored);
      if (selection == null) {
         return null;
      } else {
         BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
         String lyricJson = BiliApiClient.getBilingualSubtitleAsNetEaseLyric(info, BiliApiClient.SubtitlePreference.AI_ONLY);
         if (lyricJson != null && !lyricJson.isBlank()) {
            LyricRecord record = LyricParser.parseLyric(lyricJson, songName);
            if (record == null) {
               throw new IllegalStateException("B站 AI CC 字幕解析失败：LyricParser 返回 null");
            } else {
               return record;
            }
         } else {
            return null;
         }
      }
   }
}
