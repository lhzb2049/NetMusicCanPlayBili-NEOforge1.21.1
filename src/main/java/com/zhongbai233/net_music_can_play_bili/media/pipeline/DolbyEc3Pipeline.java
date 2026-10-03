package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import net.minecraft.core.BlockPos;

public final class DolbyEc3Pipeline extends AbstractAudioPipeline {
   private static final int STREAM_BUFFER_SIZE = 262144;
   private static final int EC3_MAX_FRAME_SIZE = 4096;
   private static final int EC3_SCAN_TAIL_SIZE = 8;
   private final AudioFormat format = new AudioFormat(48000.0F, 16, 2, true, false);
   private final AtomicBoolean ownerClosed;
   private final AtomicBoolean cleaned = new AtomicBoolean(false);
   private final DolbyAudioHandler dolby;
   private long skipFramesRemaining;
   private long mdatBoxes;
   private long mdatBytes;
   private long ec3Frames;

   public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed) {
      this(container, ownerClosed, null);
   }

   public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed, BlockPos sourcePos) {
      this(container, ownerClosed, sourcePos, 0.0F);
   }

   public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds) {
      this(container, ownerClosed, sourcePos, startOffsetSeconds, startOffsetSeconds);
   }

   public DolbyEc3Pipeline(String container, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds) {
      this(container, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, "");
   }

   public DolbyEc3Pipeline(
      String container, AtomicBoolean ownerClosed, BlockPos sourcePos, float startOffsetSeconds, float timelineStartOffsetSeconds, String sessionId
   ) {
      this(container, ownerClosed, sourcePos, startOffsetSeconds, timelineStartOffsetSeconds, sessionId, null);
   }

   public DolbyEc3Pipeline(
      String container,
      AtomicBoolean ownerClosed,
      BlockPos sourcePos,
      float startOffsetSeconds,
      float timelineStartOffsetSeconds,
      String sessionId,
      UUID ownerId
   ) {
      super(container, "ec-3", "Dolby Atmos", true);
      this.ownerClosed = ownerClosed;
      this.dolby = new DolbyAudioHandler();
      this.skipFramesRemaining = skipFrames(startOffsetSeconds);
      ClientAudioOutputRegistry.register(this.dolby, sourcePos, timelineStartOffsetSeconds, sessionId, ownerId);
   }

   @Override
   public AudioFormat format() {
      return this.format;
   }

   @Override
   public long onMdat(InputStream input, long length) throws IOException {
      DolbyEc3Pipeline.Ec3ScanStats stats = this.scanEc3FramesFromStream(input, length);
      if (stats.framesFound() > 0L || length < 0L) {
         this.mdatBoxes++;
         this.mdatBytes = this.mdatBytes + stats.bytesRead();
         this.ec3Frames = this.ec3Frames + stats.framesFound();
      }

      return stats.framesFound();
   }

   @Override
   public long decodedFrames() {
      return this.ec3Frames;
   }

   @Override
   public String statsSummary() {
      return "DolbyMdat=" + this.mdatBoxes + ", DolbyBytes=" + this.mdatBytes + ", DolbyFrames=" + this.ec3Frames + ", DolbyQueue=" + this.dolby.queuedFrames();
   }

   @Override
   public void close() {
      if (this.cleaned.compareAndSet(false, true)) {
         ClientAudioOutputRegistry.unregister(this.dolby);
         this.dolby.cleanup();
      }
   }

   private DolbyEc3Pipeline.Ec3ScanStats scanEc3FramesFromStream(InputStream in, long length) throws IOException {
      byte[] readBuffer = new byte[262144];
      byte[] scanBuffer = new byte[266248];
      int carry = 0;
      long remaining = length;
      long bytesRead = 0L;
      long framesFound = 0L;

      while (!this.ownerClosed.get() && (length < 0L || remaining > 0L)) {
         int toRead = length < 0L ? readBuffer.length : (int)Math.min((long)readBuffer.length, remaining);
         int n = in.read(readBuffer, 0, toRead);
         if (n < 0) {
            if (length >= 0L && remaining > 0L) {
               throw new EOFException("EOF while reading EC-3 payload");
            }
            break;
         }

         bytesRead += n;
         if (length >= 0L) {
            remaining -= n;
         }

         System.arraycopy(readBuffer, 0, scanBuffer, carry, n);
         int scanLen = carry + n;
         DolbyEc3Pipeline.Ec3ChunkScanResult result = this.scanEc3FramesInto(scanBuffer, scanLen);
         framesFound += result.framesFound();
         carry = Math.min(result.carryLength(), 4104);
         if (carry > 0) {
            System.arraycopy(scanBuffer, scanLen - carry, scanBuffer, 0, carry);
         }
      }

      return new DolbyEc3Pipeline.Ec3ScanStats(bytesRead, framesFound);
   }

   private DolbyEc3Pipeline.Ec3ChunkScanResult scanEc3FramesInto(byte[] data, int len) {
      int pos = 0;
      int carryStart = Math.max(0, len - 8);
      long framesFound = 0L;

      while (pos < len - 1) {
         if ((data[pos] & 255) == 11 && (data[pos + 1] & 255) == 119) {
            if (pos + 4 > len) {
               carryStart = pos;
               break;
            }

            int fszRaw = (data[pos + 2] & 7) << 8 | data[pos + 3] & 255;
            int fsz = (fszRaw + 1) * 2;
            if (fsz >= 16 && fsz <= 4096) {
               if (pos + fsz > len) {
                  carryStart = pos;
                  break;
               }

               byte[] ec3Frame = new byte[fsz];
               System.arraycopy(data, pos, ec3Frame, 0, fsz);
               if (this.skipFramesRemaining > 0L) {
                  this.skipFramesRemaining--;
               } else if (this.dolby.enqueueFrame(ec3Frame)) {
                  framesFound++;
               }

               pos += fsz;
               carryStart = Math.max(pos, len - 8);
               continue;
            }
         }

         pos++;
      }

      int carryLength = Math.max(0, len - Math.min(carryStart, len));
      return new DolbyEc3Pipeline.Ec3ChunkScanResult(framesFound, carryLength);
   }

   private static long skipFrames(float seconds) {
      return seconds <= 0.0F ? 0L : Math.max(0L, Math.round(seconds * 48000.0 / 1536.0));
   }

   private record Ec3ChunkScanResult(long framesFound, int carryLength) {
   }

   private record Ec3ScanStats(long bytesRead, long framesFound) {
   }
}
