package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class FlvStreamParser {
   private static final int TAG_TYPE_AUDIO = 8;
   private static final int TAG_TYPE_VIDEO = 9;
   private static final int TAG_HEADER_SIZE = 11;
   private static final int FLV_HEADER_SIZE = 9;
   private static final int FILTERED_TAG_FLAG = 32;
   private static final int SOUND_FORMAT_AAC = 10;
   private static final int AAC_PACKET_TYPE_SEQUENCE_HEADER = 0;
   private static final int AAC_PACKET_TYPE_RAW = 1;
   private static final int VIDEO_CODEC_AVC = 7;
   private static final int VIDEO_FRAME_TYPE_KEY = 1;
   private static final int VIDEO_EX_HEADER_FLAG = 128;
   private static final int AVC_PACKET_TYPE_SEQUENCE_HEADER = 0;
   private static final int AVC_PACKET_TYPE_NALU = 1;
   private static final int MAX_TAG_SIZE = 8388608;

   public long parse(InputStream source, BooleanSupplier stopped, FlvStreamParser.Callback callback) throws IOException, UnsupportedAudioFileException {
      BooleanSupplier stop = stopped != null ? stopped : () -> false;
      readFlvHeader(source);

      long frames;
      for (frames = 0L; !stop.getAsBoolean(); skipFully(source, 4L)) {
         byte[] header = readTagHeader(source);
         if (header != null) {
            int rawType = header[0] & 255;
            if ((rawType & 32) != 0) {
               throw new UnsupportedAudioFileException("FLV 加密 tag 不受支持");
            }

            long dataSize = readUInt24(header, 1);
            if (dataSize > 8388608L) {
               throw new IOException("FLV tag 过大: " + dataSize);
            }

            int tagType = rawType & 31;
            if (tagType == 8) {
               frames += readAudioTag(source, dataSize, readTimestamp(header), callback);
            } else if (tagType == 9 && callback.wantsVideo()) {
               readVideoTag(source, dataSize, readTimestamp(header), callback);
            } else {
               skipFully(source, dataSize);
            }
            continue;
         }
         break;
      }

      return frames;
   }

   private static void readFlvHeader(InputStream source) throws IOException, UnsupportedAudioFileException {
      byte[] header = readFully(source, 9L);
      if (header[0] == 70 && header[1] == 76 && header[2] == 86) {
         long dataOffset = readUInt32(header, 5);
         if (dataOffset < 9L) {
            throw new IOException("FLV 头部长度非法: " + dataOffset);
         } else {
            skipFully(source, dataOffset - 9L);
            skipFully(source, 4L);
         }
      } else {
         throw new UnsupportedAudioFileException("不是 FLV 流");
      }
   }

   private static byte[] readTagHeader(InputStream source) throws IOException {
      byte[] header = new byte[11];
      int read = 0;

      while (read < 11) {
         int n = source.read(header, read, 11 - read);
         if (n < 0) {
            if (read == 0) {
               return null;
            }

            throw new EOFException("FLV tag 头在第 " + read + " 字节被截断");
         }

         read += n;
      }

      return header;
   }

   private static long readAudioTag(InputStream source, long dataSize, long timestampMillis, FlvStreamParser.Callback callback) throws IOException, UnsupportedAudioFileException {
      if (dataSize < 2L) {
         skipFully(source, dataSize);
         return 0L;
      } else {
         byte[] prefix = readFully(source, 2L);
         int soundFormat = (prefix[0] & 240) >> 4;
         if (soundFormat != 10) {
            throw new UnsupportedAudioFileException("FLV 音频编码不受支持: " + describeSoundFormat(soundFormat));
         } else {
            int packetType = prefix[1] & 255;
            byte[] payload = readFully(source, dataSize - 2L);
            switch (packetType) {
               case 0:
                  if (payload.length == 0) {
                     throw new UnsupportedAudioFileException("FLV AAC 序列头为空");
                  }

                  callback.onAacSequenceHeader(payload);
                  return 0L;
               case 1:
                  if (payload.length == 0) {
                     return 0L;
                  }

                  callback.onAacFrame(payload, timestampMillis);
                  return 1L;
               default:
                  return 0L;
            }
         }
      }
   }

   private static void readVideoTag(InputStream source, long dataSize, long dtsMillis, FlvStreamParser.Callback callback) throws IOException {
      if (dataSize < 5L) {
         skipFully(source, dataSize);
      } else {
         byte[] prefix = readFully(source, 5L);
         int flags = prefix[0] & 255;
         if ((flags & 128) == 0 && (flags & 15) == 7) {
            boolean keyframe = (flags & 112) >> 4 == 1;
            int packetType = prefix[1] & 255;
            int compositionTimeMillis = readSInt24(prefix, 2);
            long payloadSize = dataSize - 5L;
            switch (packetType) {
               case 0:
                  byte[] config = readFully(source, payloadSize);
                  if (config.length > 0) {
                     callback.onAvcSequenceHeader(config);
                  }
                  break;
               case 1:
                  byte[] sample = readFully(source, payloadSize);
                  if (sample.length > 0) {
                     callback.onAvcSample(sample, dtsMillis, compositionTimeMillis, keyframe);
                  }
                  break;
               default:
                  skipFully(source, payloadSize);
            }
         } else {
            skipFully(source, dataSize - 5L);
         }
      }
   }

   private static long readTimestamp(byte[] header) {
      return readUInt24(header, 4) | (long)(header[7] & 255) << 24;
   }

   private static int readSInt24(byte[] data, int offset) {
      int value = (data[offset] & 255) << 16 | (data[offset + 1] & 255) << 8 | data[offset + 2] & 255;
      return (value & 8388608) != 0 ? value - 16777216 : value;
   }

   public static String describeSoundFormat(int soundFormat) {
      return switch (soundFormat) {
         case 0 -> "Linear PCM(0)";
         case 1 -> "ADPCM(1)";
         case 2 -> "MP3(2)";
         case 3 -> "Linear PCM little-endian(3)";
         case 4, 5, 6 -> "Nellymoser(" + soundFormat + ")";
         case 7 -> "G.711 A-law(7)";
         case 8 -> "G.711 mu-law(8)";
         case 9 -> "Enhanced FLV 扩展音频头(9)";
         default -> "SoundFormat(" + soundFormat + ")";
         case 11 -> "Speex(11)";
         case 14 -> "MP3 8kHz(14)";
      };
   }

   private static byte[] readFully(InputStream source, long length) throws IOException {
      if (length >= 0L && length <= 8388608L) {
         byte[] data = new byte[(int)length];
         int read = 0;

         while (read < data.length) {
            int n = source.read(data, read, data.length - read);
            if (n < 0) {
               throw new EOFException("FLV 负载在第 " + read + "/" + data.length + " 字节被截断");
            }

            read += n;
         }

         return data;
      } else {
         throw new IOException("FLV 负载长度非法: " + length);
      }
   }

   private static void skipFully(InputStream source, long length) throws IOException {
      long remaining = length;
      byte[] buffer = null;

      while (remaining > 0L) {
         long skipped = source.skip(remaining);
         if (skipped > 0L) {
            remaining -= skipped;
         } else {
            if (buffer == null) {
               buffer = new byte[8192];
            }

            int n = source.read(buffer, 0, (int)Math.min((long)buffer.length, remaining));
            if (n < 0) {
               throw new EOFException("FLV 流在跳过 " + length + " 字节时结束");
            }

            remaining -= n;
         }
      }
   }

   private static long readUInt24(byte[] data, int offset) {
      return (long)(data[offset] & 255) << 16 | (long)(data[offset + 1] & 255) << 8 | data[offset + 2] & 255;
   }

   private static long readUInt32(byte[] data, int offset) {
      return (long)(data[offset] & 255) << 24 | (long)(data[offset + 1] & 255) << 16 | (long)(data[offset + 2] & 255) << 8 | data[offset + 3] & 255;
   }

   public interface Callback {
      void onAacSequenceHeader(byte[] var1) throws IOException, UnsupportedAudioFileException;

      void onAacFrame(byte[] var1, long var2) throws IOException;

      default boolean wantsVideo() {
         return false;
      }

      default void onAvcSequenceHeader(byte[] avcConfig) throws IOException {
      }

      default void onAvcSample(byte[] sample, long dtsMillis, int compositionTimeMillis, boolean keyframe) throws IOException {
      }
   }
}
