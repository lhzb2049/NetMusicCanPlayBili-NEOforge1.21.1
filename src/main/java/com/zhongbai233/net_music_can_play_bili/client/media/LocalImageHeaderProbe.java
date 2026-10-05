package com.zhongbai233.net_music_can_play_bili.client.media;

/**
 * 纯逻辑图片头部解析：只读文件头就能拿到宽高，用来在真正解码前挡住超大图（避免一次性分配几百 MB 像素而 OOM）。
 *
 * <p>支持 PNG（IHDR）与 JPEG（SOFn）。解析不出来时返回 {@code null}：这是**保护性检查而非安全边界**，
 * 认不出格式就交给正常解码流程去报错。
 *
 * <p>刻意不依赖任何 Minecraft 类，可离线喂合成文件头验证。
 */
public final class LocalImageHeaderProbe {
   private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

   private LocalImageHeaderProbe() {
   }

   /** @return {@code [width, height]}；识别不出来返回 {@code null} */
   public static int[] dimensions(byte[] head) {
      if (head == null || head.length < 16) {
         return null;
      }

      int[] png = pngDimensions(head);
      return png != null ? png : jpegDimensions(head);
   }

   /** 该尺寸是否超过像素上限（保护性判断，长度可能为 0）。 */
   public static boolean exceeds(int width, int height, long maxPixels) {
      if (width <= 0 || height <= 0) {
         return false;
      }

      return (long) width * height > Math.max(1L, maxPixels);
   }

   static int[] pngDimensions(byte[] head) {
      if (head.length < 24) {
         return null;
      }

      for (int i = 0; i < PNG_SIGNATURE.length; i++) {
         if (head[i] != PNG_SIGNATURE[i]) {
            return null;
         }
      }

      // 紧随签名的是 IHDR：长度(4) + 类型(4) + 宽(4) + 高(4)
      if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') {
         return null;
      }

      int width = readInt(head, 16);
      int height = readInt(head, 20);
      return width > 0 && height > 0 ? new int[]{width, height} : null;
   }

   static int[] jpegDimensions(byte[] head) {
      if (head.length < 4 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) {
         return null;
      }

      int index = 2;
      while (index + 3 < head.length) {
         if ((head[index] & 0xFF) != 0xFF) {
            // 填充字节（0xFF 可重复），继续找下一个标记
            index++;
            continue;
         }

         int marker = head[index + 1] & 0xFF;
         if (marker == 0xFF) {
            index++;
            continue;
         }

         if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD9) || marker == 0x01) {
            index += 2;
            continue;
         }

         if (index + 3 >= head.length) {
            return null;
         }

         int length = (head[index + 2] & 0xFF) << 8 | head[index + 3] & 0xFF;
         if (length < 2) {
            return null;
         }

         boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
         if (sof) {
            if (index + 8 >= head.length) {
               return null;
            }

            int height = (head[index + 5] & 0xFF) << 8 | head[index + 6] & 0xFF;
            int width = (head[index + 7] & 0xFF) << 8 | head[index + 8] & 0xFF;
            return width > 0 && height > 0 ? new int[]{width, height} : null;
         }

         index += 2 + length;
      }

      return null;
   }

   private static int readInt(byte[] data, int offset) {
      return (data[offset] & 0xFF) << 24
         | (data[offset + 1] & 0xFF) << 16
         | (data[offset + 2] & 0xFF) << 8
         | data[offset + 3] & 0xFF;
   }
}
