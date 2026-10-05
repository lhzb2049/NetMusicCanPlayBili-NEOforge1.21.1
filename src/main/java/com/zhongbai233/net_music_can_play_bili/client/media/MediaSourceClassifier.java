package com.zhongbai233.net_music_can_play_bili.client.media;

import com.zhongbai233.net_music_can_play_bili.client.audio.PlayableMediaUrl;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 媒体源识别层：把"用户填进唱片机/控制台的那串东西"归类成 http 源、本地视频、本地图片或不认识的东西。
 *
 * <p>为什么需要它：视频侧现在硬要求 {@code BiliVideoStreamResolver} 能解析出 B站视频，音频侧则有两道
 * {@code PlayableMediaUrl.isHttp} 闸门；两者对同一串输入各自为政，本地路径只会被反复拒绝并刷屏。
 * 这里把"这是什么源、能不能本地读"收敛成一次判定，供两侧共用。
 *
 * <p>刻意不依赖 Minecraft 类（游戏目录以参数传入），因此可以离线跑路径矩阵验证。
 */
public final class MediaSourceClassifier {
   private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "m4s");
   private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg");
   /** 每帧路径的记忆化有效期：同一地址在该窗口内复用判定结果，避免每帧做文件系统 I/O。 */
   private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5L);
   private static final int CACHE_MAX_ENTRIES = 256;
   private static final Map<String, MediaSourceClassifier.CachedClassification> CACHE = new ConcurrentHashMap<>();

   private MediaSourceClassifier() {
   }

   /**
    * 一次判定的结果。
    *
    * @param kind       源种类
    * @param path       本地绝对路径文本（非本地源为 null）
    * @param normalized 去掉 {@code #nmb_*} 同步参数后的地址
    * @param allowed    是否允许本地读取（非本地源恒为 false，本地源取决于白名单策略）
    * @param reason     人类可读的原因，直接进日志/GUI
    */
   public record Classification(SourceKind kind, String path, String normalized, boolean allowed, String reason) {
      public String summary() {
         return this.kind + "|allowed=" + this.allowed + "|" + this.reason;
      }
   }

   private record CachedClassification(MediaSourceClassifier.Classification classification, long expiresAtNanos) {
   }

   /**
    * 每帧路径专用入口：同一 (游戏目录, 地址) 在 {@link #CACHE_TTL_NANOS} 内复用上一次判定，
    * 让"每帧判定"退化成"每 5 秒最多一次文件系统检查"。判定结果本身会随文件出现/消失而自愈。
    */
   public static Classification classifyThrottled(String rawUrl, String gameDirectory) {
      String key = (gameDirectory == null ? "" : gameDirectory) + '\u0000' + (rawUrl == null ? "" : rawUrl);
      long now = System.nanoTime();
      MediaSourceClassifier.CachedClassification cached = CACHE.get(key);
      if (cached != null && now < cached.expiresAtNanos()) {
         return cached.classification();
      }

      Classification fresh = classify(rawUrl, gameDirectory);
      if (CACHE.size() > CACHE_MAX_ENTRIES) {
         CACHE.clear();
      }

      CACHE.put(key, new MediaSourceClassifier.CachedClassification(fresh, now + CACHE_TTL_NANOS));
      return fresh;
   }

   public static void clearCache() {
      CACHE.clear();
   }

   public static Classification classify(String rawUrl, String gameDirectory) {
      if (rawUrl == null || rawUrl.isBlank()) {
         return new Classification(SourceKind.UNSUPPORTED, null, "", false, "空地址");
      }

      String clean = PlaybackSync.strip(rawUrl);
      String trimmed = (clean != null && !clean.isBlank() ? clean : rawUrl).trim();
      if (PlayableMediaUrl.isHttp(trimmed)) {
         return new Classification(SourceKind.HTTP, null, trimmed, true, "http(s) 远端地址");
      }

      Path local = resolveLocalPath(trimmed);
      if (local == null) {
         return new Classification(
            SourceKind.UNSUPPORTED, null, trimmed, false,
            "既不是 http(s)，也不是绝对的本地路径（相对路径与关键字搜索不视为本地媒体）"
         );
      }

      SourceKind kind = kindOf(local);
      if (kind == SourceKind.LOCAL_OTHER) {
         return new Classification(
            kind, local.toString(), trimmed, false,
            "本地文件扩展名不在支持列表内（视频 mp4/m4v/mov/m4s；图片 png/jpg/jpeg）"
         );
      }

      LocalMediaPolicy.Decision decision = LocalMediaPolicy.check(local, gameDirectory);
      return new Classification(kind, local.toString(), trimmed, decision.allowed(), decision.reason());
   }

   /** 只接受绝对路径：{@code file:} URI、盘符绝对路径、UNC。相对路径/关键字一律返回 null。 */
   static Path resolveLocalPath(String value) {
      String lower = value.toLowerCase(Locale.ROOT);
      if (lower.startsWith("file:")) {
         try {
            URI uri = new URI(value);
            return Paths.get(uri);
         } catch (URISyntaxException | RuntimeException error) {
            return null;
         }
      }

      if (value.length() >= 3 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':' && isSeparator(value.charAt(2))) {
         try {
            return Paths.get(value);
         } catch (RuntimeException error) {
            return null;
         }
      }

      if (value.startsWith("\\\\") || value.startsWith("//")) {
         try {
            return Paths.get(value);
         } catch (RuntimeException error) {
            return null;
         }
      }

      return null;
   }

   static SourceKind kindOf(Path path) {
      Path name = path.getFileName();
      if (name == null) {
         return SourceKind.LOCAL_OTHER;
      }

      String fileName = name.toString();
      int dot = fileName.lastIndexOf(46);
      if (dot < 0 || dot == fileName.length() - 1) {
         return SourceKind.LOCAL_OTHER;
      }

      String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (VIDEO_EXTENSIONS.contains(extension)) {
         return SourceKind.LOCAL_VIDEO;
      } else {
         return IMAGE_EXTENSIONS.contains(extension) ? SourceKind.LOCAL_IMAGE : SourceKind.LOCAL_OTHER;
      }
   }

   private static boolean isSeparator(char c) {
      return c == '\\' || c == '/';
   }
}
