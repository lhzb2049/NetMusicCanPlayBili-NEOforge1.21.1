package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;

public final class AacPcmPipeline extends AbstractAudioPipeline {
   private final AacFrameDecoder decoder;
   private final BlockingAudioPipe output;

   public AacPcmPipeline(byte[] asc, BlockingAudioPipe output) {
      super("fMP4", "aac", "NetMusic-compatible PCM", false);
      this.decoder = new AacFrameDecoder(asc, null);
      this.output = output;
   }

   @Override
   public AudioFormat format() {
      return this.decoder.format();
   }

   @Override
   public void onMoof(int[] sampleSizes) {
      this.decoder.onMoof(sampleSizes);
   }

   @Override
   public long onMdat(InputStream input, long length) throws IOException {
      return this.decoder.onMdat(input, length, (pcm, format) -> this.output.write(pcm));
   }

   @Override
   public long decodedFrames() {
      return this.decoder.decodedFrames();
   }

   @Override
   public String statsSummary() {
      return "aacFrames=" + this.decoder.decodedFrames();
   }

   @Override
   public void close() {
      this.output.closeWriter();
   }
}
