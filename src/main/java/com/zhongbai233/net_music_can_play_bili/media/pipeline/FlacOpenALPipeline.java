package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.github.tartaricacid.netmusic.soundlibs.org.jflac.sound.spi.Flac2PcmAudioInputStream;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import net.minecraft.core.BlockPos;

public final class FlacOpenALPipeline extends AbstractAudioPipeline {
   private static final int PIPE_BUFFER_SIZE = 4194304;
   private final AudioFormat format;
   private final BlockingAudioPipe compressedPipe = new BlockingAudioPipe(4194304);
   private final AtomicBoolean ownerClosed;
   private final AtomicBoolean cleaned = new AtomicBoolean(false);
   private final StereoOpenALHandler stereo;
   private final float startOffsetSeconds;
   private long compressedBytes;

   public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed) throws IOException {
      this(dfLa, ownerClosed, null);
   }

   public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos) throws IOException {
      this(dfLa, ownerClosed, sourcePos, 0.0F);
   }

   public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds) throws IOException {
      this(dfLa, ownerClosed, sourcePos, startOffsetSeconds, startOffsetSeconds);
   }

   public FlacOpenALPipeline(byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds) throws IOException {
      this(dfLa, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, "");
   }

   public FlacOpenALPipeline(
      byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds, String sessionId
   ) throws IOException {
      this(dfLa, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, null);
   }

   public FlacOpenALPipeline(
      byte[] dfLa, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds, String sessionId, UUID ownerId
   ) throws IOException {
      super("fMP4", "flac", null, true);
      this.format = FlacStreamSupport.audioFormat(dfLa);
      this.ownerClosed = ownerClosed;
      this.stereo = new StereoOpenALHandler();
      this.startOffsetSeconds = Math.max(0.0F, startOffsetSeconds);
      this.stereo.setSampleRate((int)this.format.getSampleRate());
      ClientAudioOutputRegistry.registerStereo(this.stereo, sourcePos, timelineStartOffsetSeconds, sessionId, ownerId);
      FlacStreamSupport.writeNativeHeader(this.compressedPipe, dfLa);
   }

   @Override
   public AudioFormat format() {
      return this.format;
   }

   @Override
   public String detail() {
      return this.format.getSampleSizeInBits() > 16 ? "Hi-Res FLAC" : "FLAC";
   }

   @Override
   public long onMdat(InputStream input, long length) throws IOException {
      long copied = FlacStreamSupport.copyMdat(input, length, this.compressedPipe, this.ownerClosed::get);
      this.compressedBytes += copied;
      return copied;
   }

   @Override
   public String statsSummary() {
      return "flacCompressedBytes=" + this.compressedBytes;
   }

   @Override
   public void finish() {
      this.compressedPipe.closeWriter();
   }

   @Override
   public void close() {
      if (this.cleaned.compareAndSet(false, true)) {
         this.compressedPipe.closeWriter();
         this.compressedPipe.close();
         ClientAudioOutputRegistry.unregisterStereo(this.stereo);
         this.stereo.cleanup();
      }
   }

   public AudioInputStream openTappedStream() {
      AudioInputStream decoded = new Flac2PcmAudioInputStream(this.compressedPipe, this.format, -1L);
      return new OpenALTappedAudioInputStream(decoded, this.stereo, this::close, this.startOffsetSeconds);
   }
}
