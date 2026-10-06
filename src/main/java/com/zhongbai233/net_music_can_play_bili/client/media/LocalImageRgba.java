package com.zhongbai233.net_music_can_play_bili.client.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 本地图片 → RGBA 像素字节（给「需要像素而不是 GL 纹理」的消费端用：Pad / MP4 物品 / 全息眼镜的设备屏）。
 *
 * <p>与阶段 1 的投影仪通路分工：投影仪要的是**纹理**（{@code NativeImage} → {@code DynamicTexture}），
 * 这里要的是**裸像素**（现有的 {@code MP4RgbaVideoLayer} 正是从 RGBA 字节上传纹理）。因此这里用 JDK 自带的
 * {@code ImageIO}，好处是整条链路（读头 → 限规模 → 缩放 → RGBA 字节）**不依赖 Minecraft 类，可离线验证**。
 *
 * <p>两道保护与阶段 1 一致：先只读文件头拿到宽高（不解码像素）挡掉超大图，再把超出上限的图按
 * {@link LocalImageScaler} 等比缩到上限内 —— 避免为了显示一张 2 万像素见方的图先分配几百 MB。
 */
public final class LocalImageRgba {
   private LocalImageRgba() {
   }

   /** RGBA 结果：{@code rgba} 长度恒为 {@code width * height * 4}，顺序 R,G,B,A（与现有视频帧一致）。 */
   public record Pixels(byte[] rgba, int width, int height, int sourceWidth, int sourceHeight) {
      public int byteLength() {
         return this.rgba.length;
      }

      public boolean scaled() {
         return this.width != this.sourceWidth || this.height != this.sourceHeight;
      }

      public String describe() {
         return this.scaled()
            ? "%dx%d（源 %dx%d）".formatted(this.width, this.height, this.sourceWidth, this.sourceHeight)
            : "%dx%d".formatted(this.width, this.height);
      }
   }

   /**
    * 读文件 → 必要时等比缩小 → RGBA 字节。
    *
    * @throws IOException 读不了 / 不是能识别的图片 / 源图像素超过上限
    */
   public static Pixels load(Path file, int maxWidth, int maxHeight, long maxPixels) throws IOException {
      int[] dimensions = dimensions(file);
      int sourceWidth = dimensions[0];
      int sourceHeight = dimensions[1];
      if (sourceWidth <= 0 || sourceHeight <= 0) {
         throw new IOException("图片宽高非法: " + sourceWidth + "x" + sourceHeight);
      }

      long pixels = (long)sourceWidth * sourceHeight;
      if (maxPixels > 0L && pixels > maxPixels) {
         throw new IOException("源图 %dx%d 超过像素上限 %d".formatted(sourceWidth, sourceHeight, maxPixels));
      }

      BufferedImage image = read(file);
      if (image == null) {
         throw new IOException("无法解码图片: " + file.getFileName());
      }

      int[] argb = new int[sourceWidth * sourceHeight];
      image.getRGB(0, 0, sourceWidth, sourceHeight, argb, 0, sourceWidth);
      int[] fit = LocalImageScaler.fit(sourceWidth, sourceHeight, maxWidth, maxHeight);
      int[] scaled = LocalImageScaler.scale(argb, sourceWidth, sourceHeight, fit[0], fit[1]);
      return new Pixels(toRgbaBytes(scaled), fit[0], fit[1], sourceWidth, sourceHeight);
   }

   /** 只读文件头拿宽高（不解码像素）：用于在真正解码前挡住超大图。 */
   public static int[] dimensions(Path file) throws IOException {
      try (InputStream raw = Files.newInputStream(file); ImageInputStream input = ImageIO.createImageInputStream(raw)) {
         if (input == null) {
            throw new IOException("无法读取图片头: " + file.getFileName());
         }

         Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
         if (!readers.hasNext()) {
            throw new IOException("不是可识别的图片格式: " + file.getFileName());
         }

         ImageReader reader = readers.next();

         try {
            reader.setInput(input, true, true);
            return new int[]{reader.getWidth(0), reader.getHeight(0)};
         } finally {
            reader.dispose();
         }
      }
   }

   private static BufferedImage read(Path file) throws IOException {
      try (InputStream raw = Files.newInputStream(file)) {
         return ImageIO.read(raw);
      }
   }

   /** ARGB（{@code BufferedImage.getRGB} 的排布）→ RGBA 字节流。 */
   static byte[] toRgbaBytes(int[] argb) {
      byte[] out = new byte[argb.length * 4];

      for (int i = 0; i < argb.length; i++) {
         int pixel = argb[i];
         int offset = i * 4;
         out[offset] = (byte)(pixel >> 16 & 0xFF);
         out[offset + 1] = (byte)(pixel >> 8 & 0xFF);
         out[offset + 2] = (byte)(pixel & 0xFF);
         out[offset + 3] = (byte)(pixel >>> 24);
      }

      return out;
   }
}
