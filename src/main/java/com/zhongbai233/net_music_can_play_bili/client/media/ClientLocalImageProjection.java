package com.zhongbai233.net_music_can_play_bili.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * 本地图片 → 投影仪：把登记为本地图片的媒体源加载成一张动态纹理，并作为静态帧交给投影仪渲染。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只做**客户端本地**：只有本地玩家填的路径会被 {@link MediaSourceClassifier} 判定为允许（服务端同步来的
 *       {@code file:} 同样要过白名单，且白名单默认只覆盖游戏目录与用户媒体目录）。</li>
 *   <li>与唱片机播放状态无关：图片是海报式的静态画面，不需要 {@code isPlaying()}。</li>
 *   <li>纹理加载（{@code NativeImage.read} + {@code DynamicTexture.upload}）统一排到客户端线程队列里执行，
 *       避免在方块实体渲染（BER）过程中做 GL 上传；当帧先返回 null，下一帧起就有画面。</li>
 *   <li>失败只记一次（走 {@link MediaLogThrottle}），不会每帧重试刷屏。</li>
 * </ul>
 */
public final class ClientLocalImageProjection {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_TEXTURES = 8;
   private static final Map<BlockPos, ResourceLocation> PROJECTOR_TEXTURES = new ConcurrentHashMap<>();
   private static final Map<String, ClientLocalImageProjection.Entry> TEXTURES = new LinkedHashMap<>();
   private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
   private static Object boundLevel;

   private ClientLocalImageProjection() {
   }

   private static final class Entry {
      final ResourceLocation id;
      final int width;
      final int height;
      final String reason;

      Entry(ResourceLocation id, int width, int height, String reason) {
         this.id = id;
         this.width = width;
         this.height = height;
         this.reason = reason;
      }
   }

   /**
    * 每帧调用（投影仪 BER 内，幂等）：判定该唱片机的媒体源是不是"允许的本地图片"，
    * 是则登记/加载，不是则清掉该投影仪的映射。分类判定带 5 秒 TTL 缓存，且这里不做任何文件 IO。
    */
   public static void syncFromTurntable(ModernTurntableBlockEntity turntable, BlockPos projectorPos) {
      if (turntable == null || projectorPos == null) {
         return;
      }

      ensureSameLevel();
      String rawUrl = turntable.getRawUrl();
      MediaSourceClassifier.Classification classification = MediaSourceClassifier.classifyThrottled(
         rawUrl, Minecraft.getInstance().gameDirectory.getAbsolutePath()
      );
      if (classification.kind() != SourceKind.LOCAL_IMAGE || !classification.allowed()) {
         PROJECTOR_TEXTURES.remove(projectorPos);
         return;
      }

      String pathText = classification.path();
      Entry ready = textureFor(pathText);
      if (ready == null) {
         PROJECTOR_TEXTURES.remove(projectorPos);
         requestLoad(pathText, classification.reason());
      } else {
         PROJECTOR_TEXTURES.put(projectorPos.immutable(), ready.id);
         if (MediaLogThrottle.shouldLog("local-image-ready|" + pathText, ready.reason)) {
            LOGGER.info(
               "本地图片已交给画面消费端: file={} 纹理={}x{} 位置={}（{}）",
               new Object[]{pathText, ready.width, ready.height, projectorPos, ready.reason}
            );
         }
      }
   }

   /**
    * 本地图片的静态帧快照。
    *
    * <p>{@code emissiveRgba} 必须为 **false**：这个标志决定渲染类型 ——
    * {@code true} 会走 {@code entityTranslucentEmissive}（半透明、**不写深度**），
    * 于是天空的云（也在半透明批次、且在更远处）会因为深度缓冲里没有这张图而画到图**前面**去
    * （实测：开光影后云穿在图片上）。{@code false} 走 {@code YuvVideoRenderTypes.videoRgbaEntity}
    * = {@code entityCutout}（写深度、alpha 裁剪），与视频 RGBA 帧完全同一条路 —— 视频在光影下正常，
    * 图片也就正常了。亮度不受影响：顶点本来就写满亮（{@code RenderVertexUtils} 的 {@code FULL_BRIGHT}）。
    */
   static VideoBillboardState.ProjectorFrameSnapshot imageSnapshot(ResourceLocation id, int width, int height) {
      return new VideoBillboardState.ProjectorFrameSnapshot(
         true,
         false,
         id,
         null,
         null,
         null,
         Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
         width,
         height,
         false,
         false,
         0.0F
      );
   }

   /**
    * 该**消费端位置**上当前要显示的本地图片静态帧；没有则返回 null，调用方回落到原有视频/占位图逻辑。
    *
    * <p>{@code consumerPos} 可以是投影仪、中控台屏幕，或全息眼镜绑定的投影仪/唱片机位置 ——
    * 三者共用同一份「位置 → 纹理」映射（映射由 {@link #syncFromTurntable} 按消费端位置登记）。
    */
   public static VideoBillboardState.ProjectorFrameSnapshot frameForConsumer(BlockPos consumerPos) {
      if (consumerPos == null) {
         return null;
      }

      ResourceLocation id = PROJECTOR_TEXTURES.get(consumerPos);
      if (id == null) {
         return null;
      }

      for (Entry entry : TEXTURES.values()) {
         if (entry.id.equals(id)) {
            return imageSnapshot(id, entry.width, entry.height);
         }
      }

      PROJECTOR_TEXTURES.remove(consumerPos);
      return null;
   }

   public static void clearAll() {
      PROJECTOR_TEXTURES.clear();
      PENDING.clear();
      synchronized (TEXTURES) {
         for (Entry entry : TEXTURES.values()) {
            Minecraft.getInstance().getTextureManager().release(entry.id);
         }

         TEXTURES.clear();
      }

      boundLevel = null;
   }

   private static Entry textureFor(String pathText) {
      synchronized (TEXTURES) {
         return TEXTURES.get(keyOf(pathText));
      }
   }

   private static void requestLoad(String pathText, String policyReason) {
      String key = keyOf(pathText);
      if (PENDING.putIfAbsent(key, Boolean.TRUE) != null) {
         return;
      }

      Minecraft.getInstance().execute(() -> {
         PENDING.remove(key);
         load(pathText, policyReason);
      });
   }

   /** 客户端线程执行：读图 →（必要时）缩放 → 建动态纹理 → 注册。 */
   private static void load(String pathText, String policyReason) {
      Path file = Paths.get(pathText);
      String key = keyOf(pathText);
      synchronized (TEXTURES) {
         if (TEXTURES.containsKey(key)) {
            return;
         }
      }

      long size;
      try {
         size = Files.size(file);
      } catch (IOException error) {
         fail(pathText, "读取文件大小失败: " + error);
         return;
      }

      int maxWidth = LocalMediaProperties.maxImageWidth();
      int maxHeight = LocalMediaProperties.maxImageHeight();
      long maxPixels = LocalMediaProperties.maxImagePixels();
      int[] header = readHeaderDimensions(file);
      if (header != null && LocalImageHeaderProbe.exceeds(header[0], header[1], maxPixels)) {
         fail(pathText, "源图 " + header[0] + "x" + header[1] + " 超过像素上限 " + maxPixels);
         return;
      }

      NativeImage source = null;
      NativeImage target = null;
      try (InputStream in = Files.newInputStream(file)) {
         source = NativeImage.read(in);
         int srcWidth = source.getWidth();
         int srcHeight = source.getHeight();
         if (srcWidth <= 0 || srcHeight <= 0) {
            fail(pathText, "图片尺寸非法: " + srcWidth + "x" + srcHeight);
            return;
         }

         int[] fitted = LocalImageScaler.fit(srcWidth, srcHeight, maxWidth, maxHeight);
         int dstWidth = fitted[0];
         int dstHeight = fitted[1];
         int[] pixels = new int[srcWidth * srcHeight];
         for (int y = 0; y < srcHeight; y++) {
            for (int x = 0; x < srcWidth; x++) {
               pixels[y * srcWidth + x] = source.getPixelRGBA(x, y);
            }
         }

         int[] scaled = LocalImageScaler.scale(pixels, srcWidth, srcHeight, dstWidth, dstHeight);
         target = new NativeImage(dstWidth, dstHeight, false);
         for (int y = 0; y < dstHeight; y++) {
            for (int x = 0; x < dstWidth; x++) {
               target.setPixelRGBA(x, y, scaled[y * dstWidth + x]);
            }
         }

         ResourceLocation id = idFor(pathText, size);
         DynamicTexture texture = new DynamicTexture(target);
         target = null;
         Minecraft.getInstance().getTextureManager().register(id, texture);
         String detail = scaled == pixels
            ? policyReason + "；未缩放"
            : String.format(
               Locale.ROOT, "%s；%dx%d → %dx%d（上限 %dx%d）",
               policyReason, srcWidth, srcHeight, dstWidth, dstHeight, maxWidth, maxHeight
            );
         synchronized (TEXTURES) {
            evictIfNeeded();
            TEXTURES.put(key, new ClientLocalImageProjection.Entry(id, dstWidth, dstHeight, detail));
         }

         LOGGER.info("本地图片加载完成: file={} 尺寸={}x{} bytes={}", new Object[]{pathText, dstWidth, dstHeight, size});
      } catch (IOException | RuntimeException error) {
         fail(pathText, "读取或解码失败: " + error);
      } finally {
         if (source != null) {
            source.close();
         }

         if (target != null) {
            target.close();
         }
      }
   }

   private static void fail(String pathText, String reason) {
      if (MediaLogThrottle.shouldLog("local-image-fail|" + pathText, reason)) {
         LOGGER.warn("本地图片无法用作投影画面: file={} 原因={}", pathText, reason);
      }
   }

   /** 只读文件头拿宽高（解码前的保护性检查）：识别不出来返回 null，交给正常解码流程报错。 */
   private static int[] readHeaderDimensions(Path file) {
      try (InputStream in = Files.newInputStream(file)) {
         byte[] head = new byte[65536];
         int read = in.readNBytes(head, 0, head.length);
         if (read <= 0) {
            return null;
         }

         return LocalImageHeaderProbe.dimensions(java.util.Arrays.copyOf(head, read));
      } catch (IOException error) {
         return null;
      }
   }

   private static void evictIfNeeded() {
      while (TEXTURES.size() > MAX_TEXTURES) {
         Iterator<Map.Entry<String, ClientLocalImageProjection.Entry>> iterator = TEXTURES.entrySet().iterator();
         if (!iterator.hasNext()) {
            return;
         }

         Map.Entry<String, ClientLocalImageProjection.Entry> oldest = iterator.next();
         Minecraft.getInstance().getTextureManager().release(oldest.getValue().id);
         PROJECTOR_TEXTURES.values().removeIf(id -> id.equals(oldest.getValue().id));
         iterator.remove();
      }
   }

   private static ResourceLocation idFor(String pathText, long size) {
      return ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "local_image/" + digest(pathText, size));
   }

   private static String digest(String pathText, long size) {
      String raw = pathText + '|' + size;
      try {
         MessageDigest sha = MessageDigest.getInstance("SHA-256");
         byte[] bytes = sha.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
         StringBuilder out = new StringBuilder(16);
         for (int i = 0; i < 8 && i < bytes.length; i++) {
            out.append(String.format(Locale.ROOT, "%02x", bytes[i]));
         }

         return out.toString();
      } catch (java.security.NoSuchAlgorithmException error) {
         return Integer.toHexString(raw.hashCode());
      }
   }

   private static String keyOf(String pathText) {
      return pathText == null ? "" : pathText.toLowerCase(Locale.ROOT);
   }

   private static void ensureSameLevel() {
      Object level = Minecraft.getInstance().level;
      if (level != boundLevel) {
         List<BlockPos> projectors = new ArrayList<>(PROJECTOR_TEXTURES.keySet());
         PROJECTOR_TEXTURES.clear();
         boundLevel = level;
         if (!projectors.isEmpty()) {
            LOGGER.debug("切换世界，已清空 {} 个投影仪的本地图片映射", projectors.size());
         }
      }
   }
}
