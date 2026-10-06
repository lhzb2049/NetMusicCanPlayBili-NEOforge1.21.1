package com.zhongbai233.net_music_can_play_bili.media.local;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 {@code byte[]} 上遍历 MP4 盒（box）的最小读取器。
 *
 * <p>为什么不用现成的解析器：模组里已有的 {@code Fmp4ToMp4Converter} 只关心 **fMP4**（moof/moov 里的
 * esds/avcC），而阶段 2 要读的是**普通 MP4** 的样本表（stts/stsz/stsc/stco/stss/ctts），两者关注的盒子
 * 不重合。这里只做「按大小切开、按类型找子盒」这件小事，纯逻辑、可离线验证。
 */
public final class Mp4BoxReader {
   /** 单个 ftyp/moov 之类元数据盒的读取上限，防止畸形文件要求一次分配几百 MB。 */
   public static final int MAX_BOX_BYTES = 64 * 1024 * 1024;

   private Mp4BoxReader() {
   }

   /** 一个盒的定位信息：{@code size} 含头部自身。 */
   public record Box(int start, long size, int headerSize, String type) {
      public int payloadStart() {
         return this.start + this.headerSize;
      }

      public int end() {
         return (int)(this.start + this.size);
      }

      public int payloadSize() {
         return (int)(this.size - this.headerSize);
      }
   }

   /** 依次读出一段区域里的同级盒；遇到畸形尺寸即停止（不抛异常，交给调用方判断缺了什么）。 */
   public static List<Box> children(byte[] data, int offset, int end) {
      List<Box> boxes = new ArrayList<>();
      int pos = Math.max(0, offset);
      int limit = Math.min(Math.max(0, end), data.length);
      while (pos + 8 <= limit) {
         long size = u32(data, pos);
         String type = fourCc(data, pos + 4);
         int headerSize = 8;
         if (size == 1L) {
            if (pos + 16 > limit) {
               break;
            }

            size = u64(data, pos + 8);
            headerSize = 16;
         } else if (size == 0L) {
            size = limit - pos;
         }

         if (size < headerSize || pos + size > limit) {
            break;
         }

         boxes.add(new Box(pos, size, headerSize, type));
         pos += (int)size;
      }

      return boxes;
   }

   /** 在同级盒里找第一个指定类型；找不到返回 null。 */
   public static Box find(byte[] data, int offset, int end, String type) {
      for (Box box : children(data, offset, end)) {
         if (box.type().equals(type)) {
            return box;
         }
      }

      return null;
   }

   public static Box findIn(byte[] data, Box parent, String type) {
      return parent == null ? null : find(data, parent.payloadStart(), parent.end(), type);
   }

   /** 沿路径逐级查找（例如 {@code path(data, moov, "trak", "mdia", "stbl")}）；任一级缺失返回 null。 */
   public static Box path(byte[] data, Box parent, String... types) {
      Box current = parent;
      for (String type : types) {
         current = findIn(data, current, type);
         if (current == null) {
            return null;
         }
      }

      return current;
   }

   /** 盒的完整字节（含头部），用于把 stsd 里的样本描述盒原样搬进新文件。 */
   public static byte[] rawBytes(byte[] data, Box box) {
      byte[] out = new byte[(int)box.size()];
      System.arraycopy(data, box.start(), out, 0, out.length);
      return out;
   }

   public static byte[] payloadBytes(byte[] data, Box box) {
      byte[] out = new byte[box.payloadSize()];
      System.arraycopy(data, box.payloadStart(), out, 0, out.length);
      return out;
   }

   public static String fourCc(byte[] data, int offset) {
      if (offset < 0 || offset + 4 > data.length) {
         return "????";
      }

      return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
   }

   public static int u16(byte[] data, int offset) {
      return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
   }

   public static int i32(byte[] data, int offset) {
      return (data[offset] & 0xFF) << 24
         | (data[offset + 1] & 0xFF) << 16
         | (data[offset + 2] & 0xFF) << 8
         | data[offset + 3] & 0xFF;
   }

   public static long u32(byte[] data, int offset) {
      return i32(data, offset) & 0xFFFFFFFFL;
   }

   public static long u64(byte[] data, int offset) {
      return u32(data, offset) << 32 | u32(data, offset + 4);
   }
}
