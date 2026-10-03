package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;

public interface AudioDecodePipeline extends AutoCloseable {
   AudioFormat format();

   String container();

   String codec();

   String detail();

   boolean usesOpenAlOutput();

   default void onMoof(int[] sampleSizes) throws IOException {
   }

   long onMdat(InputStream var1, long var2) throws IOException;

   default long onRawStream(InputStream input) throws IOException {
      return this.onMdat(input, -1L);
   }

   default long onAudioFrame(byte[] frame) throws IOException {
      this.onMoof(new int[]{frame.length});
      return this.onMdat(new ByteArrayInputStream(frame), frame.length);
   }

   default long decodedFrames() {
      return 0L;
   }

   default String statsSummary() {
      return "";
   }

   default void finish() throws IOException {
   }

   @Override
   void close();
}
