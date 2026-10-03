package com.zhongbai233.net_music_can_play_bili.bili;

public final class Mp3FrameSync {
   private static final int[] MP3_MPEG1_LAYER1_BITRATES = new int[]{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0};
   private static final int[] MP3_MPEG1_LAYER2_BITRATES = new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0};
   private static final int[] MP3_MPEG1_LAYER3_BITRATES = new int[]{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
   private static final int[] MP3_MPEG2_LAYER1_BITRATES = new int[]{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0};
   private static final int[] MP3_MPEG2_LAYER23_BITRATES = new int[]{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
   private static final int[] MP3_MPEG1_SAMPLE_RATES = new int[]{44100, 48000, 32000, 0};
   private static final int[] MP3_MPEG2_SAMPLE_RATES = new int[]{22050, 24000, 16000, 0};
   private static final int[] MP3_MPEG25_SAMPLE_RATES = new int[]{11025, 12000, 8000, 0};

   private Mp3FrameSync() {
   }

   public static int findFrameSync(byte[] bytes, int length) {
      int safeLength = Math.max(0, Math.min(length, bytes != null ? bytes.length : 0));
      if (bytes != null && safeLength >= 4) {
         for (int i = 0; i + 3 < safeLength; i++) {
            Mp3FrameSync.Frame first = parseFrame(bytes, i, safeLength);
            if (first != null) {
               int pos = i;
               int validFrames = 0;

               while (validFrames < 4) {
                  Mp3FrameSync.Frame frame = parseFrame(bytes, pos, safeLength);
                  if (frame == null || !frame.isCompatibleWith(first)) {
                     break;
                  }

                  validFrames++;
                  pos += frame.frameLength();
               }

               if (validFrames >= 3) {
                  return i;
               }
            }
         }

         return -1;
      } else {
         return -1;
      }
   }

   public static Mp3FrameSync.Frame parseFrame(byte[] bytes, int offset, int length) {
      int safeLength = Math.max(0, Math.min(length, bytes != null ? bytes.length : 0));
      if (bytes != null && offset >= 0 && offset + 4 <= safeLength) {
         int b0 = bytes[offset] & 255;
         int b1 = bytes[offset + 1] & 255;
         int b2 = bytes[offset + 2] & 255;
         if (b0 == 255 && (b1 & 224) == 224) {
            int version = b1 >> 3 & 3;
            int layer = b1 >> 1 & 3;
            int bitrateIndex = b2 >> 4 & 15;
            int sampleRateIndex = b2 >> 2 & 3;
            int padding = b2 >> 1 & 1;
            if (version != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 15 && sampleRateIndex != 3) {
               int bitrateKbps = mp3BitrateKbps(version, layer, bitrateIndex);
               int sampleRate = mp3SampleRate(version, sampleRateIndex);
               if (bitrateKbps > 0 && sampleRate > 0) {
                  int frameLength;
                  if (layer == 3) {
                     frameLength = (12 * bitrateKbps * 1000 / sampleRate + padding) * 4;
                  } else if (layer == 2) {
                     frameLength = 144 * bitrateKbps * 1000 / sampleRate + padding;
                  } else {
                     int coefficient = version == 3 ? 144 : 72;
                     frameLength = coefficient * bitrateKbps * 1000 / sampleRate + padding;
                  }

                  return frameLength >= 4 && offset + frameLength <= safeLength ? new Mp3FrameSync.Frame(version, layer, sampleRate, frameLength) : null;
               } else {
                  return null;
               }
            } else {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static int mp3BitrateKbps(int version, int layer, int index) {
      if (version == 3) {
         if (layer == 3) {
            return MP3_MPEG1_LAYER1_BITRATES[index];
         } else {
            return layer == 2 ? MP3_MPEG1_LAYER2_BITRATES[index] : MP3_MPEG1_LAYER3_BITRATES[index];
         }
      } else {
         return layer == 3 ? MP3_MPEG2_LAYER1_BITRATES[index] : MP3_MPEG2_LAYER23_BITRATES[index];
      }
   }

   private static int mp3SampleRate(int version, int index) {
      if (version == 3) {
         return MP3_MPEG1_SAMPLE_RATES[index];
      } else {
         return version == 2 ? MP3_MPEG2_SAMPLE_RATES[index] : MP3_MPEG25_SAMPLE_RATES[index];
      }
   }

   public record Frame(int version, int layer, int sampleRate, int frameLength) {
      boolean isCompatibleWith(Mp3FrameSync.Frame other) {
         return this.version == other.version && this.layer == other.layer && this.sampleRate == other.sampleRate;
      }
   }
}
