package com.zhongbai233.net_music_can_play_bili.client.media;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * 本地图片 → **静态视频帧**：给 Pad / MP4 物品 / 全息眼镜的设备屏用。
 *
 * <p>为什么走「假的一帧」而不是改渲染器：这些消费端的画面都来自同一个入口
 * （{@code MP4HandheldVideoClient.latestFrame(deviceId)} → {@code MP4RgbaVideoLayer} 上传纹理），
 * 只要这里按需交出一帧 RGBA，现有渲染链路（GUI 画布、物品表面、全息眼镜的设备绑定）全都原样工作 ——
 * 与阶段 1「投影仪复用静态帧通路」是同一个思路。
 *
 * <p>与视频帧的区分：静态图片的帧序号固定（按图片内容分配，且与视频的小整数序号错开），
 * 于是纹理只上传一次，之后每帧都直接复用，不会反复上传同一张图。
 *
 * <p>加载仍然排在客户端线程队列（读图 + 缩放），当帧先返回 null，下一帧起就有画面。
 */
public final class ClientLocalImageFrames {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_ENTRIES = 8;
   /** 图片帧序号基址：与视频帧序号（从 0 递增的小整数）刻意错开，避免纹理上传被误判为「同一帧」。 */
   private static final long SEQUENCE_BASE = 1_000_000_000L;
   private static final Map<String, ClientLocalImageFrames.Entry> FRAMES = new LinkedHashMap<>();
   private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
   private static final AtomicLong SEQUENCE = new AtomicLong(SEQUENCE_BASE);
   private static Object boundLevel;

   private ClientLocalImageFrames() {
   }

   private static final class Entry {
      final HandheldVideoFrame frame;
      final long sequence;
      final int width;
      final int height;

      Entry(HandheldVideoFrame frame, long sequence, int width, int height) {
         this.frame = frame;
         this.sequence = sequence;
         this.width = width;
         this.height = height;
      }
   }

   /**
    * 该设备当前要显示的本地图片静态帧；没有则返回 null（调用方回落到原有视频/状态画面）。
    * 每帧调用，幂等：判定走 5 秒 TTL 缓存，帧本身带缓存。
    */
   public static HandheldVideoFrame frameForDevice(UUID deviceId) {
      if (deviceId == null) {
         return null;
      }

      ensureSameLevel();
      String rawUrl = deviceRawUrl(deviceId);
      if (rawUrl == null || rawUrl.isBlank()) {
         return null;
      }

      LocalMediaAdmission.LocalKind kind = LocalMediaAdmission.localKind(rawUrl);
      if (kind != LocalMediaAdmission.LocalKind.LOCAL_IMAGE) {
         return null;
      }

      MediaSourceClassifier.Classification classification = LocalMediaAdmission.classification(
         rawUrl, Minecraft.getInstance().gameDirectory.getAbsolutePath()
      );
      String pathText = classification.path();
      if (pathText == null) {
         return null;
      }

      Entry ready = frameFor(pathText);
      if (ready == null) {
         requestLoad(pathText, classification.reason());
      }

      return ready != null ? ready.frame : null;
   }

   public static boolean hasImage(UUID deviceId) {
      return frameForDevice(deviceId) != null;
   }

   /** 静态图片的帧序号（固定值）；没有图片时返回 -1（调用方回落到视频帧序号）。 */
   public static long sequenceForDevice(UUID deviceId) {
      if (deviceId == null) {
         return -1L;
      }

      String rawUrl = deviceRawUrl(deviceId);
      if (rawUrl == null || rawUrl.isBlank() || LocalMediaAdmission.localKind(rawUrl) != LocalMediaAdmission.LocalKind.LOCAL_IMAGE) {
         return -1L;
      }

      MediaSourceClassifier.Classification classification = LocalMediaAdmission.classification(
         rawUrl, Minecraft.getInstance().gameDirectory.getAbsolutePath()
      );
      Entry entry = classification.path() != null ? frameFor(classification.path()) : null;
      return entry != null ? entry.sequence : -1L;
   }

   /** 设备当前的媒体源地址（Pad 与 MP4 物品共用同一份客户端播放状态）。 */
   private static String deviceRawUrl(UUID deviceId) {
      HandheldMediaPlayback playback = ClientMediaPlayback.videoPlayback(deviceId);
      return playback != null ? playback.rawUrl() : null;
   }

   public static void clearAll() {
      synchronized (FRAMES) {
         FRAMES.clear();
      }

      PENDING.clear();
      boundLevel = null;
   }

   private static Entry frameFor(String pathText) {
      synchronized (FRAMES) {
         return FRAMES.get(keyOf(pathText));
      }
   }

   private static void requestLoad(String pathText, String policyReason) {
      String key = keyOf(pathText);
      if (PENDING.add(key)) {
         Minecraft.getInstance().execute(() -> {
            PENDING.remove(key);
            load(pathText, policyReason);
         });
      }
   }

   /** 客户端线程执行：读图 →（必要时）缩放 → RGBA 字节 → 包成一帧。 */
   private static void load(String pathText, String policyReason) {
      String key = keyOf(pathText);
      synchronized (FRAMES) {
         if (FRAMES.containsKey(key)) {
            return;
         }
      }

      Path file = Paths.get(pathText);
      long size;
      try {
         size = Files.size(file);
      } catch (IOException error) {
         fail(pathText, "读取文件大小失败: " + error);
         return;
      }

      try {
         // 设备屏最长边也就 448 像素，没必要为了海报上传 2048² 的纹理：这里再收一道 1024 的上限
         // （用户仍可用 ncpb.local.max_image_width/height 把上限调得更小）。
         LocalImageRgba.Pixels pixels = LocalImageRgba.load(
            file,
            Math.min(1024, LocalMediaProperties.maxImageWidth()),
            Math.min(1024, LocalMediaProperties.maxImageHeight()),
            LocalMediaProperties.maxImagePixels()
         );
         HandheldVideoFrame frame = new HandheldVideoFrame(
            pixels.rgba(),
            null,
            pixels.byteLength(),
            Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
            pixels.width(),
            pixels.height(),
            0L,
            null
         );
         long sequence = SEQUENCE.incrementAndGet();
         synchronized (FRAMES) {
            evictIfNeeded();
            FRAMES.put(key, new ClientLocalImageFrames.Entry(frame, sequence, pixels.width(), pixels.height()));
         }

         LOGGER.info(
            "本地图片已交给设备屏: file={} 尺寸={} bytes={}（{}）",
            new Object[]{pathText, pixels.describe(), size, policyReason}
         );
      } catch (IOException | RuntimeException error) {
         fail(pathText, "读取或解码失败: " + error);
      }
   }

   private static void fail(String pathText, String reason) {
      if (MediaLogThrottle.shouldLog("local-image-frame-fail|" + pathText, reason)) {
         LOGGER.warn("本地图片无法显示在设备屏上: file={} 原因={}", pathText, reason);
      }
   }

   private static void evictIfNeeded() {
      while (FRAMES.size() > MAX_ENTRIES) {
         Iterator<Map.Entry<String, ClientLocalImageFrames.Entry>> iterator = FRAMES.entrySet().iterator();
         if (!iterator.hasNext()) {
            return;
         }

         iterator.next();
         iterator.remove();
      }
   }

   private static String keyOf(String pathText) {
      return pathText == null ? "" : pathText.toLowerCase(Locale.ROOT);
   }

   private static void ensureSameLevel() {
      Object level = Minecraft.getInstance().level;
      if (level != boundLevel) {
         boundLevel = level;
         PENDING.clear();
         synchronized (FRAMES) {
            if (!FRAMES.isEmpty()) {
               LOGGER.debug("切换世界，已清空 {} 张设备屏本地图片", FRAMES.size());
               FRAMES.clear();
            }
         }
      }
   }

   /** 仅用于日志/排查：当前缓存规模。 */
   public static int cachedFrames() {
      synchronized (FRAMES) {
         return FRAMES.size();
      }
   }
}
