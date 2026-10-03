package com.zhongbai233.net_music_can_play_bili.bili;

import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioFormat.Encoding;

final class PcmFadeInAudioInputStream extends AudioInputStream {
   private final int channels;
   private final long fadeFrames;
   private long framesRead;

   private PcmFadeInAudioInputStream(AudioInputStream source, long fadeFrames) {
      super(source, source.getFormat(), -1L);
      this.channels = source.getFormat().getChannels();
      this.fadeFrames = Math.max(1L, fadeFrames);
   }

   static AudioInputStream wrap(AudioInputStream source, int fadeMillis) {
      AudioFormat format = source.getFormat();
      if (fadeMillis > 0
         && format.getEncoding() == Encoding.PCM_SIGNED
         && format.getSampleSizeInBits() == 16
         && !format.isBigEndian()
         && format.getChannels() > 0
         && format.getFrameSize() == format.getChannels() * 2
         && !(format.getSampleRate() <= 0.0F)) {
         long fadeFrames = Math.max(1L, Math.round(format.getSampleRate() * fadeMillis / 1000.0));
         return new PcmFadeInAudioInputStream(source, fadeFrames);
      } else {
         return source;
      }
   }

   @Override
   public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0 && this.framesRead < this.fadeFrames) {
         int frameSize = this.channels * 2;
         int frameCount = read / frameSize;

         for (int frame = 0; frame < frameCount; frame++) {
            long absoluteFrame = this.framesRead + frame;
            if (absoluteFrame >= this.fadeFrames) {
               break;
            }

            double gain = (double)absoluteFrame / this.fadeFrames;
            int frameOffset = offset + frame * frameSize;

            for (int channel = 0; channel < this.channels; channel++) {
               int sampleOffset = frameOffset + channel * 2;
               int sample = (short)(buffer[sampleOffset] & 255 | buffer[sampleOffset + 1] << 8);
               int faded = (int)Math.round(sample * gain);
               buffer[sampleOffset] = (byte)faded;
               buffer[sampleOffset + 1] = (byte)(faded >> 8);
            }
         }

         this.framesRead += frameCount;
         return read;
      } else {
         return read;
      }
   }
}
