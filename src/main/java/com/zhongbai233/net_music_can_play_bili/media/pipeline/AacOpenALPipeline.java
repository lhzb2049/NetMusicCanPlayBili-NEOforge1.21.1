package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmPlanarConverter;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import net.minecraft.core.BlockPos;

public final class AacOpenALPipeline extends AbstractAudioPipeline {
   private final AacFrameDecoder decoder;
   private final AtomicBoolean cleaned = new AtomicBoolean(false);
   private final StereoOpenALHandler stereo;
   private long skipBytesRemaining;

   public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed) {
      this(asc, ownerClosed, null);
   }

   public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos) {
      this(asc, ownerClosed, sourcePos, 0.0F);
   }

   public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds) {
      this(asc, ownerClosed, sourcePos, startOffsetSeconds, startOffsetSeconds);
   }

   public AacOpenALPipeline(byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds) {
      this(asc, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, "");
   }

   public AacOpenALPipeline(
      byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds, String sessionId
   ) {
      this(asc, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, null);
   }

   public AacOpenALPipeline(
      byte[] asc, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds, String sessionId, UUID ownerId
   ) {
      super("fMP4", "aac", null, true);
      this.decoder = new AacFrameDecoder(asc, ownerClosed::get);
      this.stereo = new StereoOpenALHandler();
      this.stereo.setSampleRate((int)this.decoder.format().getSampleRate());
      this.skipBytesRemaining = skipBytes(this.decoder.format(), startOffsetSeconds);
      ClientAudioOutputRegistry.registerStereo(this.stereo, sourcePos, timelineStartOffsetSeconds, sessionId, ownerId);
   }

   @Override
   public AudioFormat format() {
      return this.decoder.format();
   }

   @Override
   public String detail() {
      return this.decoder.format().isBigEndian() ? "big-endian PCM" : "little-endian PCM";
   }

   @Override
   public void onMoof(int[] sampleSizes) {
      this.decoder.onMoof(sampleSizes);
   }

   @Override
   public long onMdat(InputStream input, long length) throws IOException {
      return this.decoder.onMdat(input, length, this::enqueuePcm);
   }

   @Override
   public long decodedFrames() {
      return this.decoder.decodedFrames();
   }

   @Override
   public void finish() {
      this.stereo.finishInput();
   }

   @Override
   public void close() {
      if (this.cleaned.compareAndSet(false, true)) {
         ClientAudioOutputRegistry.unregisterStereo(this.stereo);
         this.stereo.cleanup();
      }
   }

   private void enqueuePcm(byte[] pcm, AudioFormat format) {
      byte[] audible = this.applySkip(pcm);
      if (audible.length > 0) {
         float[][] planar = PcmPlanarConverter.from16Bit(audible, format.getChannels());
         this.stereo.observeFirstPcm(planar);
         this.stereo.enqueuePcm(planar);
      }
   }

   private byte[] applySkip(byte[] pcm) {
      if (this.skipBytesRemaining > 0L && pcm.length != 0) {
         int skipped = (int)Math.min(this.skipBytesRemaining, (long)pcm.length);
         this.skipBytesRemaining -= skipped;
         if (skipped >= pcm.length) {
            return new byte[0];
         } else {
            byte[] tail = new byte[pcm.length - skipped];
            System.arraycopy(pcm, skipped, tail, 0, tail.length);
            return tail;
         }
      } else {
         return pcm;
      }
   }

   private static long skipBytes(AudioFormat format, float seconds) {
      if (seconds <= 0.0F) {
         return 0L;
      } else {
         int frameSize = format.getFrameSize();
         if (frameSize <= 0) {
            int bytesPerSample = Math.max(1, (format.getSampleSizeInBits() + 7) / 8);
            frameSize = bytesPerSample * Math.max(1, format.getChannels());
         }

         return Math.max(0L, (long)Math.round(format.getSampleRate() * seconds) * frameSize);
      }
   }
}
