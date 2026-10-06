package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricParser;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSubtitleLyricService;
import com.zhongbai233.net_music_can_play_bili.client.media.LocalVideoSources;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPrepareProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.net.URI;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

public final class ClientMediaPreparer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Pattern NET_EASE_MP3_URL = Pattern.compile("^.*?\\?id=(\\d+)\\.mp3$");
   private static final int AUDIO_PREPARE_THREADS = ClientMediaPrepareProperties.settings().audioPrepareThreads();
   private static final ExecutorService AUDIO_PREPARE_EXECUTOR = Executors.newFixedThreadPool(
      AUDIO_PREPARE_THREADS, NetMusicThreadFactory.daemon("BiliAudioPrepare")
   );

   private ClientMediaPreparer() {
   }

   public static ClientMediaPreparer.PreparedMedia prepare(String rawUrl, String playUrl, String songName, boolean allowDolby, boolean enableLyrics) {
      ClientMediaPreparer.AudioResolution audio = resolveAudio(rawUrl, playUrl, songName, allowDolby);
      LyricRecord lyricRecord = enableLyrics ? buildLyric(rawUrl, songName) : null;
      return new ClientMediaPreparer.PreparedMedia(audio.playUrl(), lyricRecord, audio.presence());
   }

   public static ClientMediaPreparer.PreparedMedia prepareAudioOnly(String rawUrl, String playUrl, String songName, boolean allowDolby) {
      ClientMediaPreparer.AudioResolution audio = resolveAudio(rawUrl, playUrl, songName, allowDolby);
      return new ClientMediaPreparer.PreparedMedia(audio.playUrl(), null, audio.presence());
   }

   public static CancellableTaskFuture<ClientMediaPreparer.PreparedMedia> prepareAudioOnlyAsync(
      String rawUrl, String playUrl, String songName, boolean allowDolby
   ) {
      return CancellableTaskFuture.submit(AUDIO_PREPARE_EXECUTOR, () -> prepareAudioOnly(rawUrl, playUrl, songName, allowDolby));
   }

   public static CancellableTaskFuture<LyricRecord> buildLyricAsync(String rawUrl, String songName) {
      return CancellableTaskFuture.submit(AUDIO_PREPARE_EXECUTOR, () -> buildLyric(rawUrl, songName));
   }

   public static CancellableTaskFuture<LyricRecord> buildAiSubtitleAsync(String rawUrl, String songName) {
      return CancellableTaskFuture.submit(AUDIO_PREPARE_EXECUTOR, () -> {
         try {
            return BiliSubtitleLyricService.buildAiLyricRecord(rawUrl, songName);
         } catch (Exception var3) {
            throw new CompletionException(var3);
         }
      });
   }

   public static boolean hasStoredBiliSelection(String rawUrl, String playUrl) {
      return storedBiliSelection(rawUrl, playUrl) != null;
   }

   public static String resolvePlayableUrl(String rawUrl, String playUrl, String songName, boolean allowDolby) {
      return resolveAudio(rawUrl, playUrl, songName, allowDolby).playUrl();
   }

   private static ClientMediaPreparer.AudioResolution resolveAudio(String rawUrl, String playUrl, String songName, boolean allowDolby) {
      String storedSelection = storedBiliSelection(rawUrl, playUrl);
      if (storedSelection == null) {
         // 阶段 2：本地视频的音频走重封装出来的回环 fMP4 地址（本方法跑在音频准备线程上，可以等重封装）
         LocalVideoSources.AudioRouting routing = LocalVideoSources.routeAudio(rawUrl, playUrl);
         if (routing.handled()) {
            return routing.audioAvailable()
               ? new ClientMediaPreparer.AudioResolution(routing.url(), ClientMediaPreparer.AudioPresence.PRESENT)
               : new ClientMediaPreparer.AudioResolution(playUrl, ClientMediaPreparer.AudioPresence.ABSENT);
         }

         return new ClientMediaPreparer.AudioResolution(playUrl, ClientMediaPreparer.AudioPresence.PRESENT);
      } else {
         try {
            String resolvedUrl = BiliAudioResolver.resolvePlayableUrl(storedSelection, allowDolby);
            String syncedUrl = PlaybackSync.transferSync(playUrl, resolvedUrl);
            LOGGER.debug("客户端刷新 B站 直链: song='{}' host={} stored={} allowDolby={}", new Object[]{songName, hostOf(syncedUrl), storedSelection, allowDolby});
            return new ClientMediaPreparer.AudioResolution(syncedUrl, ClientMediaPreparer.AudioPresence.PRESENT);
         } catch (BiliApiClient.NoAudioStreamException var7) {
            LOGGER.debug("B站媒体确认没有可用音频流: stored={}", storedSelection);
            return new ClientMediaPreparer.AudioResolution(playUrl, ClientMediaPreparer.AudioPresence.ABSENT);
         } catch (Exception var8) {
            NetMusic.LOGGER.error("B站客户端本地解析播放直链失败: {}", storedSelection, var8);
            return new ClientMediaPreparer.AudioResolution("", ClientMediaPreparer.AudioPresence.FAILED);
         }
      }
   }

   public static LyricRecord buildLyric(String rawUrl, String songName) {
      LyricRecord lyricRecord = tryBuildNetEaseLyric(rawUrl, songName);
      return lyricRecord != null ? lyricRecord : BiliSubtitleLyricService.tryBuildLyricRecord(rawUrl, songName);
   }

   public static String hostOf(String value) {
      try {
         String stripped = PlaybackSync.strip(value);
         String host = URI.create(stripped != null ? stripped : value).getHost();
         return host != null ? host : "unknown";
      } catch (Exception var3) {
         return "unknown";
      }
   }

   private static String storedBiliSelection(String rawUrl, String playUrl) {
      String cleanRawUrl = PlaybackSync.strip(rawUrl);
      String cleanPlayUrl = PlaybackSync.strip(playUrl);
      if (BiliApiClient.isStoredVideoSelection(cleanRawUrl)) {
         return cleanRawUrl;
      } else {
         return BiliApiClient.isStoredVideoSelection(cleanPlayUrl) ? cleanPlayUrl : null;
      }
   }

   private static LyricRecord tryBuildNetEaseLyric(String rawUrl, String songName) {
      if (rawUrl != null && rawUrl.startsWith("https://music.163.com/")) {
         Matcher matcher = NET_EASE_MP3_URL.matcher(rawUrl);
         if (!matcher.find()) {
            return null;
         } else {
            try {
               long songId = Long.parseLong(matcher.group(1));
               String lyricJson = NetMusic.NET_EASE_WEB_API.lyric(songId);
               return LyricParser.parseLyric(lyricJson, songName);
            } catch (Exception var6) {
               NetMusic.LOGGER.error(var6);
               return null;
            }
         }
      } else {
         return null;
      }
   }

   public static enum AudioPresence {
      UNKNOWN,
      PRESENT,
      ABSENT,
      FAILED;
   }

   private record AudioResolution(String playUrl, ClientMediaPreparer.AudioPresence presence) {
   }

   public record PreparedMedia(String playUrl, LyricRecord lyricRecord, ClientMediaPreparer.AudioPresence audioPresence) {
      public PreparedMedia(String playUrl, LyricRecord lyricRecord) {
         this(playUrl, lyricRecord, ClientMediaPreparer.AudioPresence.UNKNOWN);
      }
   }
}
