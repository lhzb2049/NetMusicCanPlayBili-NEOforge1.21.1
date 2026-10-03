package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import java.io.IOException;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

public final class FlacPcmPipeline extends AbstractAudioPipeline {
   private final AudioFormat format;
   private final BlockingAudioPipe output;
   private long compressedBytes;

   public FlacPcmPipeline(byte[] dfLa, BlockingAudioPipe output) throws IOException {
      super("fMP4", "flac", null, false);
      this.format = FlacStreamSupport.audioFormat(dfLa);
      this.output = output;
      FlacStreamSupport.writeNativeHeader(output, dfLa);
   }

   @Override
   public AudioFormat format() {
      return this.format;
   }

   @Override
   public String detail() {
      return this.format.getSampleSizeInBits() > 16 ? "Hi-Res FLAC via NetMusic dither" : "FLAC via NetMusic";
   }

   @Override
   public long onMdat(InputStream input, long length) throws IOException {
      long copied = FlacStreamSupport.copyMdat(input, length, this.output, null);
      this.compressedBytes += copied;
      return copied;
   }

   @Override
   public String statsSummary() {
      return "flacCompressedBytes=" + this.compressedBytes;
   }

   @Override
   public void finish() {
      this.output.closeWriter();
   }

   @Override
   public void close() {
      this.output.closeWriter();
   }

   public AudioInputStream openDecodedStream() {
      return new Flac2PcmAudioInputStream(this.output, this.format, -1L);
   }
}
