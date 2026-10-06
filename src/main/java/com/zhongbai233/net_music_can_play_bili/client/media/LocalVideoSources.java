package com.zhongbai233.net_music_can_play_bili.client.media;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.local.Fmp4TrackWriter;
import com.zhongbai233.net_music_can_play_bili.media.local.LocalVideoRemuxer;
import com.zhongbai233.net_music_can_play_bili.media.stream.LocalMediaHttpServer;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaIoExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * 本地视频的入口：把「用户填进唱片机的那串本地路径」变成一对**回环 http 地址**
 * （视频单轨 fMP4 + 音频单轨 fMP4），交给现有的视频解码与音频管线。
 *
 * <p>三条设计要点：
 * <ol>
 *   <li><b>一次重封装、两侧共用</b>：视频侧（唱片机/投影仪）与音频侧（NetMusic 声音）都会来问同一个地址，
 *       因此按「真实路径 + 大小 + 修改时间」做键去重，谁先到谁触发重封装，另一个等同一个 future；
 *   <li><b>不在客户端线程上干活</b>：重封装跑在 {@link MediaIoExecutor}，两侧调用点都只 await 有上限的时间；
 *   <li><b>生命周期跟世界走</b>：切换世界/退出时 {@link #clearAll()} 撤销回环 token 并删除产物
 *       （阶段 1 的图片纹理也是这个策略）。
 * </ol>
 *
 * <p>多人与服务端：地址是**每台客户端各自**把自己磁盘上的文件暴露给本机解码器，服务端只负责同步那串原始路径。
 * 因此同一个唱片机在不同玩家那里各放各的本地文件；别人机器上没有这个文件时自然播不出来（与阶段 1 的图片一致）。
 */
public final class LocalVideoSources {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String WORK_DIR_NAME = "ncpb-local-video";
   private static final long VIDEO_WAIT_MILLIS = 40_000L;
   private static final long AUDIO_WAIT_MILLIS = 20_000L;
   private static final long EVICTION_GRACE_MILLIS = 60_000L;
   private static final int MAX_CACHE_ENTRIES = 8;

   private static final Map<String, LocalVideoSources.Entry> BY_PATH = new ConcurrentHashMap<>();
   private static final Map<String, LocalVideoSources.Prepared> BY_URL = new ConcurrentHashMap<>();
   private static final AtomicBoolean WORK_DIR_CLEANED = new AtomicBoolean(false);
   /** {@link CompletableFuture#getNow} 的哨兵：未完成（区别于「完成了但失败」）。 */
   private static final LocalVideoSources.Resolution PENDING = LocalVideoSources.Resolution.failed("尚未完成");

   private LocalVideoSources() {
   }

   /** 重封装产物 + 回环地址。 */
   public record Prepared(
      String key,
      String sourcePath,
      String title,
      String videoUrl,
      String audioUrl,
      long durationMillis,
      int width,
      int height,
      int fps,
      int codecId,
      String audioCodec,
      long videoInitEnd,
      long videoIndexStart,
      long videoIndexEnd,
      long audioInitEnd,
      long audioIndexStart,
      long audioIndexEnd,
      Path videoFile,
      Path audioFile,
      String notes
   ) {
      public boolean hasAudio() {
         return this.audioUrl != null && !this.audioUrl.isBlank();
      }

      public long totalBytes() {
         long bytes = 0L;
         if (this.videoFile != null) {
            try {
               bytes += Files.size(this.videoFile);
            } catch (IOException ignored) {
            }
         }

         if (this.audioFile != null) {
            try {
               bytes += Files.size(this.audioFile);
            } catch (IOException ignored) {
            }
         }

         return bytes;
      }

      public String describe() {
         return "video=%s audio=%s %dx%d fps=%d 时长=%dms codecId=%d 音频=%s".formatted(
            this.videoFile.getFileName(),
            this.audioFile != null ? this.audioFile.getFileName() : "(无)",
            this.width,
            this.height,
            this.fps,
            this.durationMillis,
            this.codecId,
            this.audioCodec
         );
      }
   }

   /** 解析结果：{@code prepared} 非空即成功，否则 {@code reason} 说明为什么不行。 */
   public record Resolution(String reason, Prepared prepared) {
      public static Resolution failed(String reason) {
         return new Resolution(reason == null ? "未知原因" : reason, null);
      }
   }

   /** 音频侧的判定结果。 */
   public record AudioRouting(boolean handled, boolean audioAvailable, String url) {
      public static final LocalVideoSources.AudioRouting NOT_LOCAL = new LocalVideoSources.AudioRouting(false, false, null);
   }

   private static final class Entry {
      private final String key;
      private final String path;
      private final long bytesHint;
      private final long createdAtMillis;
      private final AtomicLong lastUsedMillis;
      private final CompletableFuture<LocalVideoSources.Resolution> future;

      private Entry(String key, String path, long bytesHint, CompletableFuture<LocalVideoSources.Resolution> future) {
         this.key = key;
         this.path = path;
         this.bytesHint = bytesHint;
         this.createdAtMillis = System.currentTimeMillis();
         this.lastUsedMillis = new AtomicLong(this.createdAtMillis);
         this.future = future;
      }
   }

   /** 逐帧路径用：这串地址是不是「允许读取的本地视频」。判定走 5 秒 TTL 记忆化，不做文件 I/O 之外的事。 */
   public static boolean isLocalVideoSource(String rawUrl) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.gameDirectory == null) {
         return false;
      }

      return isLocalVideoSourceFor(rawUrl, minecraft.gameDirectory.getAbsolutePath());
   }

   /** 纯逻辑版（离线验证直接调它）：只有「开关打开 + 判定为本地视频 + 白名单允许」才算数。 */
   static boolean isLocalVideoSourceFor(String rawUrl, String gameDirectory) {
      if (!LocalVideoProperties.enabled() || rawUrl == null || rawUrl.isBlank()) {
         return false;
      }

      MediaSourceClassifier.Classification classification = MediaSourceClassifier.classifyThrottled(rawUrl, gameDirectory);
      return classification.kind() == SourceKind.LOCAL_VIDEO && classification.allowed();
   }

   /** 视频侧：拿到可直接喂给 {@code VideoBillboardPreview} 的解析结果。 */
   public static Resolution resolveLocal(String rawUrl, long timeoutMillis) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.gameDirectory == null) {
         return Resolution.failed("客户端还没准备好（游戏目录未知）");
      }

      Path workDir = minecraft.gameDirectory.toPath().resolve(WORK_DIR_NAME);
      long timeout = timeoutMillis > 0L ? timeoutMillis : VIDEO_WAIT_MILLIS;
      return resolveFor(rawUrl, minecraft.gameDirectory.getAbsolutePath(), workDir, timeout);
   }

   public static Resolution resolveLocal(String rawUrl) {
      return resolveLocal(rawUrl, VIDEO_WAIT_MILLIS);
   }

   /**
    * 音频侧：本地视频的音频要换成回环 fMP4 地址；不是本地视频就返回 {@link AudioRouting#NOT_LOCAL}，
    * 让调用方保持原有行为。源里没有可用音频（例如纯视频或非 AAC 音轨）时 {@code audioAvailable=false}。
    */
   public static AudioRouting routeAudio(String rawUrl, String playUrl) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.gameDirectory == null) {
         return LocalVideoSources.AudioRouting.NOT_LOCAL;
      }

      Path workDir = minecraft.gameDirectory.toPath().resolve(WORK_DIR_NAME);
      return routeAudioFor(rawUrl, playUrl, minecraft.gameDirectory.getAbsolutePath(), workDir);
   }

   /** 纯逻辑版（离线验证直接调它）。 */
   static AudioRouting routeAudioFor(String rawUrl, String playUrl, String gameDirectory, Path workDir) {
      for (String candidate : candidates(playUrl, rawUrl)) {
         if (!isLocalVideoSourceFor(candidate, gameDirectory)) {
            continue;
         }

         Resolution resolution = resolveFor(candidate, gameDirectory, workDir, AUDIO_WAIT_MILLIS);
         if (resolution.prepared() == null) {
            LOGGER.warn("本地视频音频路由失败: value='{}' 原因={}", candidate, resolution.reason());
            return new LocalVideoSources.AudioRouting(true, false, null);
         }

         Prepared prepared = resolution.prepared();
         return new LocalVideoSources.AudioRouting(true, prepared.hasAudio(), prepared.audioUrl());
      }

      return LocalVideoSources.AudioRouting.NOT_LOCAL;
   }

   /**
    * 视频侧：把本地重封装结果包装成现有视频链路认识的解析结果（单候选，回环视频地址）。
    *
    * @throws IOException 判定不允许或重封装失败，消息里带可读原因，直接进投影仪的错误占位图与日志
    */
   public static BiliVideoStreamResolver.ResolvedVideoStream resolveLocalStream(String rawUrl, long timeoutMillis) throws IOException {
      Resolution resolution = resolveLocal(rawUrl, timeoutMillis);
      Prepared prepared = resolution.prepared();
      if (prepared == null) {
         throw new IOException("本地视频无法播放: " + resolution.reason());
      }

      BiliVideoStreamResolver.VideoCandidate candidate = new BiliVideoStreamResolver.VideoCandidate(
         prepared.videoUrl(), prepared.codecId(), prepared.width(), prepared.height(), prepared.fps(), 0
      );
      return new BiliVideoStreamResolver.ResolvedVideoStream(
         prepared.videoUrl(),
         prepared.codecId(),
         prepared.width(),
         prepared.height(),
         prepared.fps(),
         0,
         prepared.title(),
         null,
         BiliVideoStreamResolver.DecodeMode.AUTO,
         List.of(candidate)
      );
   }

   private static List<String> candidates(String first, String second) {
      List<String> values = new ArrayList<>(2);
      if (first != null && !first.isBlank()) {
         values.add(first);
      }

      if (second != null && !second.isBlank() && !second.equals(first)) {
         values.add(second);
      }

      return values;
   }

   /** 回环地址 → 该媒体已知的时长（服务端没有同步时长时用它兜底，避免 seek/找齐退化）。 */
   public static long knownDurationMillis(String url) {
      if (url == null || url.isBlank()) {
         return 0L;
      }

      LocalVideoSources.Prepared prepared = BY_URL.get(strip(url));
      return prepared != null ? prepared.durationMillis() : 0L;
   }

   /** 切换世界/退出：撤销所有回环地址并删除产物。 */
   public static void clearAll() {
      List<LocalVideoSources.Prepared> published = new ArrayList<>(BY_URL.values());
      BY_URL.clear();
      BY_PATH.clear();

      for (LocalVideoSources.Prepared prepared : published) {
         LocalMediaHttpServer server = LocalMediaHttpServer.instance();
         server.revoke(tokenOf(prepared.videoUrl()));
         server.revoke(tokenOf(prepared.audioUrl()));
         deleteQuietly(prepared.videoFile());
         deleteQuietly(prepared.audioFile());
      }

      if (!published.isEmpty()) {
         LOGGER.debug("本地视频缓存已清空: {} 项", published.size());
      }
   }

   /** 纯逻辑入口（离线验证直接调它，避免依赖 Minecraft 类）。 */
   static Resolution resolveFor(String rawUrl, String gameDirectory, Path workDir, long timeoutMillis) {
      if (!LocalVideoProperties.enabled()) {
         return Resolution.failed("本地视频通路未启用（-Dncpb.local.video.enabled=false）");
      }

      if (rawUrl == null || rawUrl.isBlank()) {
         return Resolution.failed("空地址");
      }

      MediaSourceClassifier.Classification classification = MediaSourceClassifier.classify(rawUrl, gameDirectory);
      if (classification.kind() != SourceKind.LOCAL_VIDEO) {
         return Resolution.failed("不是本地视频源（" + classification.kind() + "）");
      }

      if (!classification.allowed()) {
         return Resolution.failed("本地视频不可读取: " + classification.reason());
      }

      Path path;
      try {
         path = Path.of(classification.path());
      } catch (RuntimeException error) {
         return Resolution.failed("路径无法解析: " + error.getMessage());
      }

      String pathKey = path.toString();
      LocalVideoSources.Entry existing = BY_PATH.get(pathKey);
      if (existing != null) {
         LocalVideoSources.Prepared ready = settled(existing);
         if (ready != null) {
            existing.lastUsedMillis.set(System.currentTimeMillis());
            return new Resolution(null, ready);
         }

         if (!existing.future.isDone()) {
            existing.lastUsedMillis.set(System.currentTimeMillis());
            return await(existing, timeoutMillis);
         }

         BY_PATH.remove(pathKey, existing);
      }

      LocalVideoSources.Entry created = createEntry(path, pathKey, classification.path(), workDir);
      return await(created, timeoutMillis);
   }

   private static Entry createEntry(Path path, String pathKey, String sourcePath, Path workDir) {
      long size;
      long modified;
      try {
         size = Files.size(path);
         modified = Files.getLastModifiedTime(path).toMillis();
      } catch (IOException error) {
         size = -1L;
         modified = 0L;
      }

      String key = hashKey(sourcePath, size, modified);
      prune();
      CompletableFuture<LocalVideoSources.Resolution> future = MediaIoExecutor.supply(() -> {
         try {
            return new Resolution(null, prepare(path, sourcePath, key, workDir));
         } catch (Exception error) {
            String reason = describeFailure(error);
            LOGGER.warn("本地视频重封装失败: file={} 原因={}", path.getFileName(), reason);
            return Resolution.failed(reason);
         }
      });
      LocalVideoSources.Entry entry = new LocalVideoSources.Entry(key, pathKey, Math.max(0L, size), future);
      LocalVideoSources.Entry replaced = BY_PATH.put(pathKey, entry);
      if (replaced != null) {
         replaced.future.cancel(true);
      }

      return entry;
   }

   private static Resolution await(Entry entry, long timeoutMillis) {
      long timeout = timeoutMillis > 0L ? timeoutMillis : VIDEO_WAIT_MILLIS;

      try {
         return entry.future.get(timeout, TimeUnit.MILLISECONDS);
      } catch (TimeoutException error) {
         return Resolution.failed("重封装超时（%dms；文件较大时可用 -Dncpb.local.video.max_source_bytes= 调整上限）".formatted(timeout));
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         return Resolution.failed("等待重封装被中断");
      } catch (ExecutionException error) {
         Throwable cause = error.getCause() != null ? error.getCause() : error;
         return Resolution.failed(describeFailure(cause));
      }
   }

   private static Prepared settled(Entry entry) {
      Resolution resolution = entry.future.getNow(PENDING);
      return resolution == PENDING || resolution.prepared() == null ? null : resolution.prepared();
   }

   private static Prepared prepare(Path source, String sourcePath, String key, Path workDir) throws IOException {
      cleanWorkDirOnce(workDir);
      LocalVideoRemuxer.Settings settings = new LocalVideoRemuxer.Settings(
         LocalVideoProperties.fragmentMillis(),
         LocalVideoProperties.maxSourceBytes(),
         LocalVideoProperties.maxFragmentBytes(),
         LocalVideoProperties.includeAudio()
      );
      LocalVideoRemuxer.Result result = LocalVideoRemuxer.remux(source, workDir, key, settings);
      LocalMediaHttpServer server = LocalMediaHttpServer.instance();
      long ttl = LocalVideoProperties.tokenTtlMillis();
      String name = source.getFileName() == null ? "local.mp4" : source.getFileName().toString();
      LocalMediaHttpServer.Published video = server.publish(
         UUID.randomUUID().toString().replace("-", ""), result.videoFile(), "video/mp4", name, ttl
      );
      LocalMediaHttpServer.Published audio = null;
      if (result.audioFile() != null) {
         audio = server.publish(
            UUID.randomUUID().toString().replace("-", ""), result.audioFile(), "audio/mp4", name, ttl
         );
      }

      Fmp4TrackWriter.Result videoLayout = result.videoLayout();
      Fmp4NativeVideoDecoder.registerSegmentBase(
         video.url(), 0L, videoLayout.initEnd(), videoLayout.indexStart(), videoLayout.indexEnd()
      );
      Fmp4TrackWriter.Result audioLayout = result.audioLayout();
      if (audio != null && audioLayout != null) {
         HttpAudioStreamHandler.registerSegmentBase(
            audio.url(), 0L, audioLayout.initEnd(), audioLayout.indexStart(), audioLayout.indexEnd()
         );
      }

      String title = name.toLowerCase(Locale.ROOT).endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
      Prepared prepared = new Prepared(
         key,
         sourcePath,
         title,
         video.url(),
         audio != null ? audio.url() : null,
         result.durationMillis(),
         result.width(),
         result.height(),
         Math.max(1, result.fps()),
         result.codecId(),
         result.audioCodec(),
         videoLayout.initEnd(),
         videoLayout.indexStart(),
         videoLayout.indexEnd(),
         audioLayout != null ? audioLayout.initEnd() : -1L,
         audioLayout != null ? audioLayout.indexStart() : -1L,
         audioLayout != null ? audioLayout.indexEnd() : -1L,
         result.videoFile(),
         result.audioFile(),
         result.notes()
      );
      BY_URL.put(strip(prepared.videoUrl()), prepared);
      if (prepared.hasAudio()) {
         BY_URL.put(strip(prepared.audioUrl()), prepared);
      }

      LOGGER.debug(
         "本地视频重封装完成: file={} {} 分片=%d/%d 回环端口={} 备注={}",
         name,
         prepared.describe(),
         videoLayout.fragmentCount(),
         audioLayout != null ? audioLayout.fragmentCount() : 0,
         LocalMediaHttpServer.instance().port(),
         prepared.notes()
      );
      return prepared;
   }

   /** 产物目录是本模组自己的缓存目录：本次 JVM 第一次用到时清空（此时不可能有正在使用的文件）。 */
   private static void cleanWorkDirOnce(Path workDir) {
      if (!WORK_DIR_CLEANED.compareAndSet(false, true)) {
         return;
      }

      if (!Files.isDirectory(workDir)) {
         return;
      }

      int removed = 0;

      try (DirectoryStream<Path> files = Files.newDirectoryStream(workDir, "*.mp4")) {
         for (Path file : files) {
            if (Files.deleteIfExists(file)) {
               removed++;
            }
         }
      } catch (IOException error) {
         LOGGER.debug("清理本地视频缓存目录失败: {}", error.getMessage());
      }

      if (removed > 0) {
         LOGGER.debug("已清理上次遗留的本地视频缓存: {} 个文件", removed);
      }
   }

   /** 缓存上限与条目数上限：按最近使用时间回收（刚发布的 60 秒内不动，避免踩到正在播放的会话）。 */
   private static void prune() {
      long budget = LocalVideoProperties.cacheMaxBytes();
      long now = System.currentTimeMillis();
      List<LocalVideoSources.Entry> settled = new ArrayList<>();

      for (LocalVideoSources.Entry entry : BY_PATH.values()) {
         if (entry.future.isDone()) {
            settled.add(entry);
         }
      }

      long total = 0L;

      for (LocalVideoSources.Entry entry : settled) {
         LocalVideoSources.Prepared prepared = settled(entry);
         total += prepared != null ? prepared.totalBytes() : entry.bytesHint;
      }

      settled.sort(Comparator.comparingLong(entry -> entry.lastUsedMillis.get()));
      int index = 0;

      while ((total > budget || BY_PATH.size() > MAX_CACHE_ENTRIES) && index < settled.size()) {
         LocalVideoSources.Entry victim = settled.get(index++);
         if (now - victim.createdAtMillis < EVICTION_GRACE_MILLIS) {
            continue;
         }

         if (BY_PATH.remove(victim.path, victim)) {
            LocalVideoSources.Prepared prepared = settled(victim);
            if (prepared != null) {
               total -= prepared.totalBytes();
               LocalMediaHttpServer server = LocalMediaHttpServer.instance();
               server.revoke(tokenOf(prepared.videoUrl()));
               server.revoke(tokenOf(prepared.audioUrl()));
               BY_URL.remove(strip(prepared.videoUrl()), prepared);
               if (prepared.hasAudio()) {
                  BY_URL.remove(strip(prepared.audioUrl()), prepared);
               }

               deleteQuietly(prepared.videoFile());
               deleteQuietly(prepared.audioFile());
            } else {
               total -= victim.bytesHint;
            }

            victim.future.cancel(true);
         }
      }
   }

   private static String describeFailure(Throwable error) {
      Throwable cause = error;
      while (cause.getCause() != null && cause.getCause() != cause) {
         cause = cause.getCause();
      }

      String message = cause.getMessage();
      return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
   }

   private static String hashKey(String sourcePath, long size, long modified) {
      String seed = sourcePath + '|' + size + '|' + modified;
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         byte[] bytes = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
         StringBuilder hex = new StringBuilder(16);
         for (int i = 0; i < 8; i++) {
            hex.append(String.format("%02x", bytes[i] & 0xFF));
         }

         return hex.toString();
      } catch (NoSuchAlgorithmException error) {
         return Integer.toHexString(seed.hashCode());
      }
   }

   private static String tokenOf(String url) {
      if (url == null) {
         return null;
      }

      int marker = url.indexOf("/ncpb/");
      if (marker < 0) {
         return null;
      }

      String rest = url.substring(marker + 6);
      int slash = rest.indexOf(47);
      return slash >= 0 ? rest.substring(0, slash) : rest;
   }

   private static String strip(String url) {
      try {
         java.net.URL stripped = PlaybackSync.strip(java.net.URI.create(url).toURL());
         return stripped.toString();
      } catch (Exception error) {
         return url;
      }
   }

   private static void deleteQuietly(Path file) {
      if (file != null) {
         try {
            Files.deleteIfExists(file);
         } catch (IOException ignored) {
         }
      }
   }
}
