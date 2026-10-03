package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

public class BiliAudioResolver implements IAsyncSongUrlResolver {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ExecutorService RESOLVE_EXECUTOR = Executors.newFixedThreadPool(2, NetMusicThreadFactory.daemon("BiliSongResolve"));

   public static SongInfo resolveBiliSongInfo(String rawInput) throws Exception {
      return resolveBiliSongInfo(rawInput, 1);
   }

   public static SongInfo resolveBiliSongInfo(String rawInput, int page) throws Exception {
      BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(rawInput);
      if (selection == null) {
         throw new IllegalArgumentException("请输入 B站 BV 号或 AV 号: " + rawInput);
      } else {
         BiliApiClient.VideoId videoId = selection.videoId();
         int selectedPage = page > 1 ? page : selection.page();
         BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId, selectedPage);
         SongInfo songInfo = new SongInfo(
            Objects.requireNonNull(BiliApiClient.formatStoredVideoSelection(videoId, info.page())),
            Objects.requireNonNull(info.displayTitle()),
            info.duration(),
            false
         );
         if (info.staffNames() != null && !info.staffNames().isEmpty()) {
            songInfo.artists = new ArrayList<>(info.staffNames());
         }

         LOGGER.debug(
            "B站唱片解析摘要: input='{}' stored='{}' title='{}' page={}/{} cid={} duration={}s staff={}",
            new Object[]{rawInput, songInfo.songUrl, info.displayTitle(), info.page(), info.totalPages(), info.cid(), info.duration(), info.staffNames()}
         );
         return songInfo;
      }
   }

   public boolean canResolve(SongInfo songInfo) {
      return BiliApiClient.isStoredVideoSelection(songInfo.songUrl);
   }

   public static String resolvePlayableUrl(String storedSelection) throws Exception {
      return resolvePlayableUrl(storedSelection, true);
   }

   public static String resolvePlayableUrl(String storedSelection, boolean allowDolby) throws Exception {
      BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(storedSelection);
      if (selection == null) {
         throw new IllegalArgumentException("无法从唱片存储的内容提取 B站 ID: " + storedSelection);
      } else {
         BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
         return BiliApiClient.getBestAudioUrl(selection.videoId(), info.cid(), allowDolby);
      }
   }

   public static SongInfo resolvePlayableSongInfo(SongInfo songInfo) throws Exception {
      BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(songInfo.songUrl);
      if (selection == null) {
         throw new IllegalArgumentException("无法从唱片存储的内容提取 B站 ID: " + songInfo.songUrl);
      } else {
         BiliApiClient.VideoInfo info = null;
         String title = songInfo.songName;
         if (title == null || title.isBlank() || songInfo.songTime <= 0) {
            info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
         }

         if ((title == null || title.isBlank()) && info != null) {
            title = info.displayTitle();
         }

         title = Objects.requireNonNull(title, "标题不能为空");
         String storedSelection = Objects.requireNonNull(songInfo.songUrl, "B站存储选集不能为空");
         SongInfo resolved = new SongInfo(storedSelection, title, songInfo.songTime > 0 ? songInfo.songTime : (info != null ? info.duration() : 0), false);
         if ((songInfo.artists == null || songInfo.artists.isEmpty()) && info != null && info.staffNames() != null && !info.staffNames().isEmpty()) {
            resolved.artists = new ArrayList<>(info.staffNames());
         } else {
            resolved.artists = songInfo.artists;
         }

         return resolved;
      }
   }

   public CompletableFuture<SongInfo> resolve(SongInfo songInfo) {
      return CancellableTaskFuture.submit(RESOLVE_EXECUTOR, () -> {
         try {
            return resolvePlayableSongInfo(songInfo);
         } catch (Exception var2) {
            LOGGER.error("B站音频解析失败: {}", var2.getMessage());
            return songInfo;
         }
      });
   }

   public int getPriority() {
      return 50;
   }
}
