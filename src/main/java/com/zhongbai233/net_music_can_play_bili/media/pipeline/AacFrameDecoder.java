package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.SampleBuffer;
import com.github.tartaricacid.netmusic.soundlibs.net.sourceforge.jaad.aac.Decoder;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;
import org.slf4j.Logger;

final class AacFrameDecoder {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int AAC_FRAME_SAMPLES = 1024;
   private final Decoder decoder;
   private final SampleBuffer sampleBuffer;
   private final AudioFormat format;
   private final BooleanSupplier shouldStop;
   private int[] pendingSampleSizes;
   private long decodedFrames;

   AacFrameDecoder(byte[] asc, BooleanSupplier shouldStop) {
      this.decoder = Decoder.create(asc);
      this.format = this.decoder.getAudioFormat();
      this.sampleBuffer = new SampleBuffer(this.format);
      this.sampleBuffer.setBigEndian(this.format.isBigEndian());
      this.shouldStop = shouldStop != null ? shouldStop : () -> false;
   }

   AudioFormat format() {
      return this.format;
   }

   void onMoof(int[] sampleSizes) {
      this.pendingSampleSizes = sampleSizes;
      if (sampleSizes == null || sampleSizes.length == 0) {
         LOGGER.trace("fMP4 AAC moof had no sample sizes");
      }
   }

   long onMdat(InputStream input, long length, AacFrameDecoder.PcmSink sink) throws IOException {
      if (this.pendingSampleSizes != null && this.pendingSampleSizes.length != 0) {
         long remaining = length;
         long decodedThisBox = 0L;

         for (int sampleSize : this.pendingSampleSizes) {
            if (this.shouldStop.getAsBoolean() || remaining <= 0L) {
               break;
            }

            if (sampleSize > 0) {
               if (sampleSize > remaining) {
                  decodedThisBox += this.decodePartialTail(input, remaining, sink);
                  remaining = 0L;
                  break;
               }

               byte[] sample = Fmp4StreamParser.readFully(input, sampleSize);
               remaining -= sampleSize;
               if (sampleSize > 0) {
                  if (this.decodeOne(sample, sink)) {
                     decodedThisBox++;
                  } else {
                     this.emitSilenceFrame(sink);
                     decodedThisBox++;
                  }
               }
            }
         }

         if (remaining > 0L) {
            Fmp4StreamParser.skipFully(input, remaining);
         }

         this.decodedFrames += decodedThisBox;
         this.pendingSampleSizes = null;
         return decodedThisBox;
      } else {
         Fmp4StreamParser.skipFully(input, length);
         return 0L;
      }
   }

   long decodedFrames() {
      return this.decodedFrames;
   }

   private long decodePartialTail(InputStream input, long remaining, AacFrameDecoder.PcmSink sink) throws IOException {
      if (remaining <= 0L) {
         Fmp4StreamParser.skipFully(input, remaining);
         return 0L;
      } else {
         byte[] partial = Fmp4StreamParser.readFully(input, remaining);

         try {
            return this.decodeOne(partial, sink) ? 1L : 0L;
         } catch (Exception var7) {
            LOGGER.trace("Skipping truncated AAC tail frame size={}: {}", remaining, var7.getMessage());
            return 0L;
         }
      }
   }

   private boolean decodeOne(byte[] sample, AacFrameDecoder.PcmSink sink) throws IOException {
      try {
         this.decoder.decodeFrame(sample, this.sampleBuffer);
         byte[] pcm = this.sampleBuffer.getData();
         if (pcm != null && pcm.length != 0) {
            sink.accept(pcm, this.format);
            return true;
         } else {
            return false;
         }
      } catch (IOException var4) {
         throw var4;
      } catch (Exception var5) {
         LOGGER.trace("Skipping undecodable AAC frame(size={}): {}", sample.length, var5.getMessage());
         return false;
      }
   }

   private void emitSilenceFrame(AacFrameDecoder.PcmSink sink) throws IOException {
      int frameSize = this.format.getFrameSize();
      if (frameSize <= 0) {
         int bytesPerSample = Math.max(1, (this.format.getSampleSizeInBits() + 7) / 8);
         frameSize = bytesPerSample * Math.max(1, this.format.getChannels());
      }

      sink.accept(new byte[Math.max(1, 1024 * frameSize)], this.format);
   }

   @FunctionalInterface
   interface PcmSink {
      void accept(byte[] var1, AudioFormat var2) throws IOException;
   }
}
