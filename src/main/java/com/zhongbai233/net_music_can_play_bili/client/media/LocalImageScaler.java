package com.zhongbai233.net_music_can_play_bili.client.media;

/**
 * 纯逻辑图片缩放：把任意尺寸的 ARGB 像素缩到上限之内。
 *
 * <p>用盒式（box）平均：目标每个像素取源图对应矩形区域的平均值，比最近邻干净，且只需一遍遍历。
 * 刻意不依赖任何 Minecraft 类（只吃 {@code int[]}），因此可以离线跑矩阵验证。
 *
 * <p>通道说明：Minecraft 的 {@code NativeImage} 以 ABGR 位序存放像素，这里只做"同一位置的通道取平均"，
 * 读回写回用的是同一套位序，因此语义不受影响（不关心哪个字节是红还是蓝）。
 */
public final class LocalImageScaler {
   private LocalImageScaler() {
   }

   /**
    * 计算目标尺寸：保持宽高比缩到 {@code (maxWidth, maxHeight)} 之内；本来就够小则原样返回。
    *
    * @return {@code [width, height]}，两个分量都 &gt;= 1
    */
   public static int[] fit(int srcWidth, int srcHeight, int maxWidth, int maxHeight) {
      if (srcWidth <= 0 || srcHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
         return new int[]{Math.max(1, srcWidth), Math.max(1, srcHeight)};
      }

      if (srcWidth <= maxWidth && srcHeight <= maxHeight) {
         return new int[]{srcWidth, srcHeight};
      }

      double scale = Math.min((double) maxWidth / srcWidth, (double) maxHeight / srcHeight);
      int width = Math.min(maxWidth, Math.max(1, (int) Math.floor(srcWidth * scale)));
      int height = Math.min(maxHeight, Math.max(1, (int) Math.floor(srcHeight * scale)));
      return new int[]{width, height};
   }

   /**
    * 盒式缩放。{@code src} 为行优先 ARGB，长度需 &gt;= {@code srcWidth * srcHeight}。
    * 目标尺寸与源一致时原样返回同一数组（调用方可据此判断"没缩放"）。
    */
   public static int[] scale(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
      if (src == null || srcWidth <= 0 || srcHeight <= 0) {
         return src;
      }

      if (srcWidth == dstWidth && srcHeight == dstHeight) {
         return src;
      }

      if (dstWidth <= 0 || dstHeight <= 0 || src.length < srcWidth * srcHeight) {
         return src;
      }

      int[] out = new int[dstWidth * dstHeight];
      for (int dy = 0; dy < dstHeight; dy++) {
         int y0 = (int) ((long) dy * srcHeight / dstHeight);
         int y1 = (int) ((long) (dy + 1) * srcHeight / dstHeight);
         if (y1 <= y0) {
            y1 = Math.min(srcHeight, y0 + 1);
         }

         for (int dx = 0; dx < dstWidth; dx++) {
            int x0 = (int) ((long) dx * srcWidth / dstWidth);
            int x1 = (int) ((long) (dx + 1) * srcWidth / dstWidth);
            if (x1 <= x0) {
               x1 = Math.min(srcWidth, x0 + 1);
            }

            long alpha = 0L;
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            int count = 0;
            for (int sy = y0; sy < y1; sy++) {
               int row = sy * srcWidth;
               for (int sx = x0; sx < x1; sx++) {
                  int pixel = src[row + sx];
                  alpha += pixel >>> 24 & 0xFF;
                  red += pixel >>> 16 & 0xFF;
                  green += pixel >>> 8 & 0xFF;
                  blue += pixel & 0xFF;
                  count++;
               }
            }

            if (count == 0) {
               count = 1;
            }

            out[dy * dstWidth + dx] = (int) (alpha / count) << 24
               | (int) (red / count) << 16
               | (int) (green / count) << 8
               | (int) (blue / count);
         }
      }

      return out;
   }
}
