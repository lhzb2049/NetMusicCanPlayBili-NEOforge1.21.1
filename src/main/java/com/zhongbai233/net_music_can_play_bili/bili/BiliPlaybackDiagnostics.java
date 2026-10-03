package com.zhongbai233.net_music_can_play_bili.bili;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;

public final class BiliPlaybackDiagnostics {
   private static volatile BiliPlaybackDiagnostics.Snapshot current = BiliPlaybackDiagnostics.Snapshot.empty();

   private BiliPlaybackDiagnostics() {
   }

   public static void beginPlayback(String songName, String sourceUrl, String resolvedUrl) {
      String cleanSongName = clean(songName, "未知歌曲");
      current = new BiliPlaybackDiagnostics.Snapshot(
         cleanSongName,
         clean(sourceUrl, "unknown"),
         clean(resolvedUrl, "unknown"),
         "resolving",
         "unknown",
         null,
         "",
         System.currentTimeMillis(),
         System.currentTimeMillis(),
         false,
         ""
      );
   }

   public static void updateFormat(URL url, String container, String codec, AudioFormat format, String detail) {
      BiliPlaybackDiagnostics.Snapshot old = current;
      String cleanContainer = clean(container, "unknown");
      String cleanCodec = clean(codec, "unknown");
      String cleanDetail = detail != null ? detail : "";
      current = new BiliPlaybackDiagnostics.Snapshot(
         old.songName(),
         old.sourceUrl(),
         url != null ? url.toString() : old.resolvedUrl(),
         cleanContainer,
         cleanCodec,
         format,
         cleanDetail,
         old.startedAtMillis(),
         System.currentTimeMillis(),
         false,
         old.lastError()
      );
   }

   public static void markClosed(URL url) {
      BiliPlaybackDiagnostics.Snapshot old = current;
      String resolvedUrl = url != null ? url.toString() : old.resolvedUrl();
      current = new BiliPlaybackDiagnostics.Snapshot(
         old.songName(),
         old.sourceUrl(),
         resolvedUrl,
         old.container(),
         old.codec(),
         old.format(),
         old.detail(),
         old.startedAtMillis(),
         System.currentTimeMillis(),
         true,
         old.lastError()
      );
   }

   public static void markFailed(URL url, Throwable error) {
      BiliPlaybackDiagnostics.Snapshot old = current;
      String resolvedUrl = url != null ? url.toString() : old.resolvedUrl();
      String message = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage();
      current = new BiliPlaybackDiagnostics.Snapshot(
         old.songName(),
         old.sourceUrl(),
         resolvedUrl,
         old.container(),
         old.codec(),
         old.format(),
         old.detail(),
         old.startedAtMillis(),
         System.currentTimeMillis(),
         true,
         clean(message, "unknown")
      );
   }

   public static List<String> describeCurrentPlayback() {
      BiliPlaybackDiagnostics.Snapshot s = current;
      List<String> lines = new ArrayList<>();
      if (s.startedAtMillis() == 0L) {
         lines.add("当前没有播放诊断信息");
         return lines;
      } else {
         lines.add("歌曲: " + s.songName());
         lines.add("状态: " + (s.closed() ? "已关闭/最近播放" : "播放中"));
         lines.add("容器/编码: " + s.container() + " / " + s.codec());
         if (s.format() != null) {
            AudioFormat f = s.format();
            lines.add(String.format("音频格式: %s, %.0f Hz, %d ch, %d bit", f.getEncoding(), f.getSampleRate(), f.getChannels(), f.getSampleSizeInBits()));
         } else {
            lines.add("音频格式: 尚未识别");
         }

         if (!s.detail().isBlank()) {
            lines.add("细节: " + s.detail());
         }

         lines.add("直链主机: " + hostOf(s.resolvedUrl()));
         if (!s.lastError().isBlank()) {
            lines.add("最近错误: " + s.lastError());
         }

         return lines;
      }
   }

   public static String currentCodecSummary() {
      BiliPlaybackDiagnostics.Snapshot s = current;
      return s.container() + " / " + s.codec();
   }

   private static String clean(String value, String fallback) {
      return value != null && !value.isBlank() ? value : fallback;
   }

   private static String hostOf(String value) {
      if (value != null && !value.isBlank() && !"unknown".equals(value)) {
         try {
            String host = URI.create(value).getHost();
            return host != null ? host : value;
         } catch (Exception var2) {
            return value;
         }
      } else {
         return "unknown";
      }
   }

   private record Snapshot(
      String songName,
      String sourceUrl,
      String resolvedUrl,
      String container,
      String codec,
      AudioFormat format,
      String detail,
      long startedAtMillis,
      long updatedAtMillis,
      boolean closed,
      String lastError
   ) {
      static BiliPlaybackDiagnostics.Snapshot empty() {
         return new BiliPlaybackDiagnostics.Snapshot("", "", "", "unknown", "unknown", null, "", 0L, 0L, true, "");
      }
   }
}
