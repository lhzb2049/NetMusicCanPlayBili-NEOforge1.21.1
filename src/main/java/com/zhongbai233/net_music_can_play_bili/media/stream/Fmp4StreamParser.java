package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class Fmp4StreamParser {
   private static final int FORMAT_SNIFF_SIZE = 64;
   private static final int MAX_METADATA_BOX_SIZE = 16777216;
   private static final int MAX_BUFFERED_PAYLOAD_SIZE = Fmp4StreamProperties.maxBufferedPayloadBytes();
   private static final int SKIP_BUFFER_SIZE = 8192;

   public Fmp4StreamParser.ContainerKind parse(InputStream source, AtomicBoolean closed, Fmp4StreamParser.Callback callback) throws IOException, UnsupportedAudioFileException {
      PushbackInputStream in = new PushbackInputStream(source, 64);
      byte[] sniff = new byte[12];
      int sniffLen = readSniffBytes(in, sniff, sniff.length);
      if (sniffLen > 0) {
         in.unread(sniff, 0, sniffLen);
      }

      boolean isFmp4 = sniffLen >= 8 && sniff[4] == 102 && sniff[5] == 116 && sniff[6] == 121 && sniff[7] == 112;
      boolean isRawEac3 = sniffLen >= 2 && (sniff[0] & 255) == 11 && (sniff[1] & 255) == 119;
      if (isRawEac3) {
         callback.onRawEac3(in);
         return Fmp4StreamParser.ContainerKind.RAW_EAC3;
      } else if (!isFmp4) {
         return Fmp4StreamParser.ContainerKind.OTHER_AUDIO;
      } else {
         Fmp4StreamParser.BoxHeader box;
         while (!closed.get() && (box = readBoxHeader(in)) != null) {
            if (box.dataSize < 0L) {
               throw new IOException("unknown-length MP4 box is not supported: " + box.type);
            }

            String var10 = box.type;
            switch (var10) {
               case "ftyp":
                  callback.onFtyp(box.dataSize);
                  skipFully(in, box.dataSize);
                  break;
               case "moov":
                  byte[] moovData = readFullyBounded(in, box.dataSize, 16777216, "moov");
                  callback.onMoov(Fmp4ToMp4Converter.parseMoov(moovData), moovData);
                  break;
               case "moof":
                  byte[] moofData = readFullyBounded(in, box.dataSize, 16777216, "moof");
                  callback.onMoof(Fmp4ToMp4Converter.extractSampleSizesFromMoof(moofData), moofData);
                  break;
               case "mdat":
                  Fmp4StreamParser.BoundedInputStream payload = new Fmp4StreamParser.BoundedInputStream(in, box.dataSize);
                  callback.onMdat(payload, box.dataSize);
                  if (closed.get()) {
                     return Fmp4StreamParser.ContainerKind.FMP4;
                  }

                  payload.drain();
                  break;
               default:
                  skipFully(in, box.dataSize);
            }
         }

         return Fmp4StreamParser.ContainerKind.FMP4;
      }
   }

   public static byte[] readFully(InputStream in, long length) throws IOException {
      if (length >= 0L && length <= MAX_BUFFERED_PAYLOAD_SIZE) {
         byte[] data = new byte[(int)length];
         readFullyInto(in, data, 0, data.length);
         return data;
      } else {
         throw new IOException("MP4 payload too large to buffer: " + length + " > " + MAX_BUFFERED_PAYLOAD_SIZE);
      }
   }

   public static void skipFully(InputStream in, long length) throws IOException {
      byte[] buffer = new byte[8192];
      long remaining = length;

      while (remaining > 0L) {
         long skipped = in.skip(remaining);
         if (skipped > 0L) {
            remaining -= skipped;
         } else {
            int toRead = (int)Math.min((long)buffer.length, remaining);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
               throw new EOFException("EOF while skipping MP4 payload");
            }

            remaining -= n;
         }
      }
   }

   private static byte[] readFullyBounded(InputStream in, long length, int maxLength, String boxType) throws IOException {
      if (length > maxLength) {
         throw new IOException("MP4 " + boxType + " box too large: " + length + " > " + maxLength);
      } else {
         return readFully(in, length);
      }
   }

   private static void readFullyInto(InputStream in, byte[] data, int offset, int length) throws IOException {
      int readTotal = 0;

      while (readTotal < length) {
         int n = in.read(data, offset + readTotal, length - readTotal);
         if (n < 0) {
            throw new EOFException("EOF while reading MP4 payload");
         }

         readTotal += n;
      }
   }

   private static int readSniffBytes(InputStream in, byte[] buf, int maxLen) throws IOException {
      int total = 0;

      while (total < maxLen) {
         int r = in.read(buf, total, maxLen - total);
         if (r < 0) {
            break;
         }

         total += r;
      }

      return total;
   }

   private static Fmp4StreamParser.BoxHeader readBoxHeader(InputStream in) throws IOException {
      int first = in.read();
      if (first < 0) {
         return null;
      } else {
         byte[] header = new byte[8];
         header[0] = (byte)first;
         readFullyInto(in, header, 1, 7);
         long size = readUInt32(header, 0);
         String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
         int headerSize = 8;
         if (size == 1L) {
            byte[] ext = readFully(in, 8L);
            size = readUInt64(ext, 0);
            headerSize = 16;
         } else if (size == 0L) {
            return new Fmp4StreamParser.BoxHeader(type, -1L);
         }

         long dataSize = size - headerSize;
         if (dataSize < 0L) {
            throw new IOException("invalid MP4 box size: type=" + type + ", size=" + size);
         } else {
            return new Fmp4StreamParser.BoxHeader(type, dataSize);
         }
      }
   }

   private static long readUInt32(byte[] data, int offset) {
      return (data[offset] & 255L) << 24 | (data[offset + 1] & 255L) << 16 | (data[offset + 2] & 255L) << 8 | data[offset + 3] & 255L;
   }

   private static long readUInt64(byte[] data, int offset) {
      long value = 0L;

      for (int i = 0; i < 8; i++) {
         value = value << 8 | data[offset + i] & 255L;
      }

      return value;
   }

   private static final class BoundedInputStream extends InputStream {
      private final InputStream delegate;
      private long remaining;

      private BoundedInputStream(InputStream delegate, long length) {
         this.delegate = delegate;
         this.remaining = length;
      }

      @Override
      public int read() throws IOException {
         byte[] one = new byte[1];
         int n = this.read(one, 0, 1);
         return n < 0 ? -1 : one[0] & 0xFF;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
         if (this.remaining <= 0L) {
            return -1;
         } else {
            int toRead = (int)Math.min((long)len, this.remaining);
            int n = this.delegate.read(b, off, toRead);
            if (n < 0) {
               throw new EOFException("EOF inside bounded MP4 payload");
            } else {
               this.remaining -= n;
               return n;
            }
         }
      }

      private void drain() throws IOException {
         Fmp4StreamParser.skipFully(this, this.remaining);
      }
   }

   private record BoxHeader(String type, long dataSize) {
   }

   public interface Callback {
      default void onFtyp(long size) throws IOException {
      }

      void onMoov(Fmp4ToMp4Converter.ParseResult var1, byte[] var2) throws IOException, UnsupportedAudioFileException;

      void onMoof(int[] var1, byte[] var2) throws IOException;

      void onMdat(InputStream var1, long var2) throws IOException;

      void onRawEac3(InputStream var1) throws IOException, UnsupportedAudioFileException;
   }

   public static enum ContainerKind {
      FMP4,
      RAW_EAC3,
      OTHER_AUDIO;
   }
}
