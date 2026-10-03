package com.zhongbai233.net_music_can_play_bili.media.audio;

import java.util.Objects;
import javax.sound.sampled.AudioFormat;

public final class PcmPlanarConverter {
   private PcmPlanarConverter() {
   }

   public static float[][] from16Bit(byte[] pcmBytes, int channels) {
      return from16Bit(pcmBytes, 0, pcmBytes.length, channels);
   }

   public static float[][] from16Bit(byte[] pcmBytes, int offset, int length, int channels) {
      Objects.requireNonNull(pcmBytes, "pcmBytes");
      Objects.checkFromIndexSize(offset, length, pcmBytes.length);
      int sourceChannels = Math.max(1, channels);
      int outChannels = Math.min(2, sourceChannels);
      int samples = length / (2 * sourceChannels);
      float[][] planar = new float[outChannels][samples];

      for (int sample = 0; sample < samples; sample++) {
         for (int channel = 0; channel < outChannels; channel++) {
            int index = offset + (sample * sourceChannels + channel) * 2;
            short value = (short)(pcmBytes[index + 1] << 8 | pcmBytes[index] & 255);
            planar[channel][sample] = value / 32768.0F;
         }
      }

      return planar;
   }

   public static float[][] convert(byte[] pcmBytes, AudioFormat format) {
      return convert(pcmBytes, 0, pcmBytes.length, format);
   }

   public static float[][] convert(byte[] pcmBytes, int offset, int length, AudioFormat format) {
      Objects.requireNonNull(pcmBytes, "pcmBytes");
      Objects.requireNonNull(format, "format");
      Objects.checkFromIndexSize(offset, length, pcmBytes.length);
      int bits = format.getSampleSizeInBits();
      int sourceChannels = Math.max(1, format.getChannels());
      if (bits <= 16) {
         return from16Bit(pcmBytes, offset, length, sourceChannels);
      } else {
         int outChannels = Math.min(2, sourceChannels);
         boolean bigEndian = format.isBigEndian();
         int bytesPerSample = Math.max(2, bits / 8);
         int samples = length / (bytesPerSample * sourceChannels);
         float[][] planar = new float[outChannels][samples];

         for (int sample = 0; sample < samples; sample++) {
            for (int channel = 0; channel < outChannels; channel++) {
               int index = offset + (sample * sourceChannels + channel) * bytesPerSample;
               if (bytesPerSample == 3) {
                  int b0 = pcmBytes[index] & 255;
                  int b1 = pcmBytes[index + 1] & 255;
                  int b2 = pcmBytes[index + 2] & 255;
                  int value = bigEndian ? b0 << 16 | b1 << 8 | b2 : b2 << 16 | b1 << 8 | b0;
                  int sign = bigEndian ? b0 : b2;
                  if ((sign & 128) != 0) {
                     value |= -16777216;
                  }

                  planar[channel][sample] = value / 8388608.0F;
               } else {
                  int b0 = pcmBytes[index] & 255;
                  int b1 = pcmBytes[index + 1] & 255;
                  int b2 = pcmBytes[index + 2] & 255;
                  int b3 = pcmBytes[index + 3] & 255;
                  int value = bigEndian ? b0 << 24 | b1 << 16 | b2 << 8 | b3 : b3 << 24 | b2 << 16 | b1 << 8 | b0;
                  planar[channel][sample] = value / 2.1474836E9F;
               }
            }
         }

         return planar;
      }
   }
}
