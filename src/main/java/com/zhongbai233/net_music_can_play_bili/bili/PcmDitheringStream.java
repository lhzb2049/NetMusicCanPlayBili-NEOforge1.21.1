package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import org.slf4j.Logger;

final class PcmDitheringStream extends InputStream {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int BLOCK_FRAMES = 4096;
   private static final int SRC_SAMPLE_BYTES = 3;
   private static final int DST_SAMPLE_BYTES = 2;
   private static final int QUANTIZATION_STEP = 256;
   private static final Encoding PCM_SIGNED = Encoding.PCM_SIGNED;
   private final InputStream source;
   private final int srcFrameSize;
   private final int dstFrameSize;
   private final int channels;
   private final boolean srcBigEndian;
   private final boolean dstBigEndian;
   private final byte[] srcBlock;
   private final byte[] dstBlock;
   private int dstLen;
   private int dstPos;
   private final byte[] singleByte = new byte[1];
   private final RandomGenerator rng;
   private boolean closed;
   private boolean firstBlockLogged;

   PcmDitheringStream(InputStream source, AudioFormat srcFormat, AudioFormat dstFormat) {
      this.source = Objects.requireNonNull(source, "source");
      Objects.requireNonNull(srcFormat, "srcFormat");
      Objects.requireNonNull(dstFormat, "dstFormat");
      if (!PCM_SIGNED.equals(srcFormat.getEncoding())) {
         throw new IllegalArgumentException("仅支持 signed PCM 源格式，got: " + srcFormat);
      } else if (!PCM_SIGNED.equals(dstFormat.getEncoding())) {
         throw new IllegalArgumentException("仅支持 signed PCM 目标格式，got: " + dstFormat);
      } else if (srcFormat.getSampleSizeInBits() != 24) {
         throw new IllegalArgumentException("仅支持 24-bit 源格式，got: " + srcFormat);
      } else if (dstFormat.getSampleSizeInBits() != 16) {
         throw new IllegalArgumentException("仅支持 16-bit 目标格式，got: " + dstFormat);
      } else if (srcFormat.getChannels() != dstFormat.getChannels()) {
         throw new IllegalArgumentException("源和目标声道数不一致");
      } else if (Float.compare(srcFormat.getSampleRate(), dstFormat.getSampleRate()) != 0) {
         throw new IllegalArgumentException("源和目标采样率不一致");
      } else {
         this.channels = srcFormat.getChannels();
         this.srcFrameSize = 3 * this.channels;
         this.dstFrameSize = 2 * this.channels;
         this.srcBigEndian = srcFormat.isBigEndian();
         this.dstBigEndian = dstFormat.isBigEndian();
         this.srcBlock = new byte[4096 * this.srcFrameSize];
         this.dstBlock = new byte[4096 * this.dstFrameSize];
         this.rng = RandomGeneratorFactory.getDefault().create();
         this.dstLen = 0;
         this.dstPos = 0;
      }
   }

   @Override
   public int read() throws IOException {
      int n = this.read(this.singleByte, 0, 1);
      return n < 0 ? -1 : this.singleByte[0] & 0xFF;
   }

   @Override
   public int read(byte[] b, int off, int len) throws IOException {
      Objects.checkFromIndexSize(off, len, b.length);
      if (this.closed) {
         return -1;
      } else if (len == 0) {
         return 0;
      } else {
         int totalRead = 0;

         while (totalRead < len && (this.dstPos < this.dstLen || this.convertNextBlock())) {
            int n = Math.min(len - totalRead, this.dstLen - this.dstPos);
            System.arraycopy(this.dstBlock, this.dstPos, b, off + totalRead, n);
            this.dstPos += n;
            totalRead += n;
         }

         return totalRead == 0 ? -1 : totalRead;
      }
   }

   private boolean convertNextBlock() throws IOException {
      int srcLen = readFully(this.source, this.srcBlock);
      if (!this.firstBlockLogged && srcLen > 0) {
         this.firstBlockLogged = true;
         LOGGER.debug("B站 TPDF 抖动转换已启动: 输入 {}bit/{}ch → 输出 16bit/{}ch, 首块 {} bytes", new Object[]{24, this.channels, this.channels, srcLen});
      }

      if (srcLen <= 0) {
         return false;
      } else {
         int fullFrames = srcLen / this.srcFrameSize;
         if (fullFrames == 0) {
            return false;
         } else {
            this.dstPos = 0;
            this.dstLen = fullFrames * this.dstFrameSize;
            int srcOff = 0;
            int dstOff = 0;

            for (int f = 0; f < fullFrames; f++) {
               for (int ch = 0; ch < this.channels; ch++) {
                  int s24;
                  if (this.srcBigEndian) {
                     s24 = (this.srcBlock[srcOff] & 255) << 16 | (this.srcBlock[srcOff + 1] & 255) << 8 | this.srcBlock[srcOff + 2] & 255;
                  } else {
                     s24 = this.srcBlock[srcOff] & 255 | (this.srcBlock[srcOff + 1] & 255) << 8 | (this.srcBlock[srcOff + 2] & 255) << 16;
                  }

                  if ((s24 & 8388608) != 0) {
                     s24 |= -16777216;
                  }

                  double tpdf = (this.rng.nextDouble() - this.rng.nextDouble()) * 256.0;
                  double dithered = s24 + tpdf;
                  int s16 = (int)Math.round(dithered / 256.0);
                  if (s16 > 32767) {
                     s16 = 32767;
                  }

                  if (s16 < -32768) {
                     s16 = -32768;
                  }

                  if (this.dstBigEndian) {
                     this.dstBlock[dstOff] = (byte)(s16 >> 8 & 0xFF);
                     this.dstBlock[dstOff + 1] = (byte)(s16 & 0xFF);
                  } else {
                     this.dstBlock[dstOff] = (byte)(s16 & 0xFF);
                     this.dstBlock[dstOff + 1] = (byte)(s16 >> 8 & 0xFF);
                  }

                  srcOff += 3;
                  dstOff += 2;
               }
            }

            return this.dstLen > 0;
         }
      }
   }

   private static int readFully(InputStream in, byte[] buf) throws IOException {
      int total = 0;

      while (total < buf.length) {
         int n = in.read(buf, total, buf.length - total);
         if (n < 0) {
            break;
         }

         if (n == 0) {
            int one = in.read();
            if (one < 0) {
               break;
            }

            buf[total++] = (byte)one;
         } else {
            total += n;
         }
      }

      return total;
   }

   @Override
   public void close() throws IOException {
      this.closed = true;
      this.source.close();
   }
}
