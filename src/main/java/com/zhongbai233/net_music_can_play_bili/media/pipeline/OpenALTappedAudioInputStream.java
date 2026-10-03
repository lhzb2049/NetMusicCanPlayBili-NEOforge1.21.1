package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.media.audio.PcmPlanarConverter;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import org.slf4j.Logger;

public final class OpenALTappedAudioInputStream extends AudioInputStream {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SKIP_BUFFER_SIZE = 65536;
   private static final AtomicLong INSTANCES_CREATED = new AtomicLong();
   private static final AtomicLong CLOSES_COMPLETED = new AtomicLong();
   private final AudioInputStream source;
   private final StereoOpenALHandler stereo;
   private final Runnable onClose;
   private final int frameBytes;
   private final byte[] carry;
   private final byte[] skipBuffer = new byte[65536];
   private byte[] tapBuffer = new byte[0];
   private final long initialSkipBytes;
   private long skipBytesRemaining;
   private int carryLength;
   private volatile boolean closed;
   private boolean firstDiagnostics;
   private boolean inputFinished;
   private boolean skipLogged;

   public OpenALTappedAudioInputStream(AudioInputStream source, StereoOpenALHandler stereo, Runnable onClose) {
      this(source, stereo, onClose, 0.0F);
   }

   public OpenALTappedAudioInputStream(AudioInputStream source, StereoOpenALHandler stereo, Runnable onClose, float startOffsetSeconds) {
      super(source, source.getFormat(), -1L);
      this.source = source;
      this.stereo = stereo;
      this.onClose = onClose;
      AudioFormat format = source.getFormat();
      int bytesPerSample = Math.max(1, (format.getSampleSizeInBits() + 7) / 8);
      this.frameBytes = Math.max(1, bytesPerSample * Math.max(1, format.getChannels()));
      this.skipBytesRemaining = Math.max(0L, (long)Math.round(format.getSampleRate() * Math.max(0.0F, startOffsetSeconds)) * this.frameBytes);
      this.initialSkipBytes = this.skipBytesRemaining;
      this.carry = new byte[this.frameBytes];
      INSTANCES_CREATED.incrementAndGet();
   }

   @Override
   public int read() throws IOException {
      byte[] one = new byte[1];
      int n = this.read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
   }

   @Override
   public int read(byte[] b, int off, int len) throws IOException {
      if (this.closed) {
         return -1;
      } else if (!this.drainStartOffset()) {
         return -1;
      } else {
         int n = this.source.read(b, off, len);
         if (n <= 0) {
            if (n < 0) {
               this.finishInput();
            }

            return n;
         } else {
            this.tap(b, off, n);
            Arrays.fill(b, off, off + n, (byte)0);
            return n;
         }
      }
   }

   private boolean drainStartOffset() throws IOException {
      while (this.skipBytesRemaining > 0L && !this.closed) {
         int request = (int)Math.min((long)this.skipBuffer.length, this.skipBytesRemaining);
         int n = this.source.read(this.skipBuffer, 0, request);
         if (n < 0) {
            this.skipBytesRemaining = 0L;
            this.finishInput();
            return false;
         }

         if (n != 0) {
            this.skipBytesRemaining -= n;
         }
      }

      if (!this.skipLogged && this.initialSkipBytes > 0L) {
         this.skipLogged = true;
         LOGGER.debug("OpenAL tapped stream skipped {} PCM bytes for start offset", this.initialSkipBytes);
      }

      return !this.closed;
   }

   @Override
   public synchronized void close() throws IOException {
      if (!this.closed) {
         this.closed = true;
         IOException error = null;

         try {
            try {
               this.source.close();
            } catch (IOException var7) {
               error = var7;
            }

            try {
               this.onClose.run();
            } catch (RuntimeException var8) {
               if (error == null) {
                  error = new IOException("OpenAL tapped stream cleanup failed", var8);
               } else {
                  error.addSuppressed(var8);
               }
            }
         } finally {
            CLOSES_COMPLETED.incrementAndGet();
         }

         if (error != null) {
            throw error;
         }
      }
   }

   private void tap(byte[] pcm, int offset, int length) {
      if (this.stereo != null && length > 0) {
         int combinedLength = this.carryLength + length;
         this.ensureTapCapacity(combinedLength);
         if (this.carryLength > 0) {
            System.arraycopy(this.carry, 0, this.tapBuffer, 0, this.carryLength);
         }

         System.arraycopy(pcm, offset, this.tapBuffer, this.carryLength, length);
         int aligned = combinedLength - combinedLength % this.frameBytes;
         if (aligned > 0) {
            float[][] planar = PcmPlanarConverter.convert(this.tapBuffer, 0, aligned, this.getFormat());
            StereoOpenALHandler.PcmQuality quality = this.stereo.observeFirstPcm(planar);
            if (!this.firstDiagnostics && quality.samples() >= 1024L) {
               this.firstDiagnostics = true;
               this.logFirstPcmDiagnostics(aligned, quality);
            }

            this.stereo.enqueuePcm(planar);
         }

         int remain = combinedLength - aligned;
         if (remain > 0) {
            System.arraycopy(this.tapBuffer, aligned, this.carry, 0, remain);
         }

         this.carryLength = remain;
      }
   }

   private void ensureTapCapacity(int required) {
      if (this.tapBuffer.length < required) {
         int capacity = Math.max(4096, this.tapBuffer.length);

         while (capacity < required && capacity <= 1073741823) {
            capacity *= 2;
         }

         if (capacity < required) {
            capacity = required;
         }

         this.tapBuffer = new byte[capacity];
      }
   }

   private void finishInput() {
      if (!this.inputFinished && this.stereo != null) {
         this.inputFinished = true;
         this.stereo.finishInput();
      }
   }

   public boolean isClosed() {
      return this.closed;
   }

   public static OpenALTappedAudioInputStream.LifecycleSnapshot lifecycleSnapshot() {
      long created = INSTANCES_CREATED.get();
      long closed = CLOSES_COMPLETED.get();
      return new OpenALTappedAudioInputStream.LifecycleSnapshot(created, closed, Math.max(0L, created - closed));
   }

   private void logFirstPcmDiagnostics(int bytes, StereoOpenALHandler.PcmQuality quality) {
      AudioFormat format = this.getFormat();
      LOGGER.debug(
         "PCM tap first chunk: bytes={} frameBytes={} aligned={} sampleSize={} endian={} samples={} peak={} rms={} clippedRatio={}",
         new Object[]{
            bytes,
            this.frameBytes,
            bytes % this.frameBytes == 0,
            format.getSampleSizeInBits(),
            format.isBigEndian() ? "big" : "little",
            quality.samples(),
            quality.peak(),
            quality.rms(),
            quality.clippedRatio()
         }
      );
   }

   public record LifecycleSnapshot(long instancesCreated, long closesCompleted, long activeInstances) {
   }
}
