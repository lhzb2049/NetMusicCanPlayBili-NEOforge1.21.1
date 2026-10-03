package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.AudioStartupSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

public final class PcmStartupSeekPolicy {
   private static final int MAX_COMPENSATION_PASSES = 4;
   private static final int SKIP_BUFFER_BYTES = 65536;

   private PcmStartupSeekPolicy() {
   }

   public static PcmStartupSeekPolicy.Result seekToCurrentPlayback(
      AudioInputStream stream, AudioFormat format, PlaybackRequest request, float initialSkipSeconds
   ) throws IOException {
      long bytesPerSecond = Math.max(1L, (long)Math.round(format.getSampleRate()) * format.getFrameSize());
      int frameSize = Math.max(1, format.getFrameSize());
      long initialSkipBytes = PcmFrameAlignment.alignDown(bytesForSeconds(format, initialSkipSeconds), frameSize);
      long skippedBytes = 0L;
      int passes = 0;
      byte[] buffer = new byte[65536];

      while (passes < 4) {
         long setupMillis = AudioStartupSync.elapsedSinceCaptureMillis(request.capturedNanos(), System.nanoTime());
         long targetBytes = PcmFrameAlignment.alignDown(saturatedAdd(initialSkipBytes, millisToBytes(bytesPerSecond, setupMillis)), frameSize);
         long remaining = Math.max(0L, targetBytes - skippedBytes);
         if (remaining <= bytesPerSecond / 20L) {
            break;
         }

         long skippedThisPass = skip(stream, remaining, buffer, frameSize);
         skippedBytes = saturatedAdd(skippedBytes, skippedThisPass);
         passes++;
         if (skippedThisPass < remaining) {
            break;
         }
      }

      long compensatedBytes = Math.max(0L, skippedBytes - initialSkipBytes);
      long compensatedMillis = Math.round(compensatedBytes * 1000.0 / bytesPerSecond);
      long timelineOffsetMillis = AudioStartupSync.compensatedOffsetMillis(request.elapsedMillis(), request.totalMillis(), compensatedMillis);
      return new PcmStartupSeekPolicy.Result(timelineOffsetMillis, passes, skippedBytes, frameSize);
   }

   public static long skipFixedOffset(AudioInputStream stream, AudioFormat format, float seconds) throws IOException {
      return skip(stream, bytesForSeconds(format, seconds), new byte[65536], Math.max(1, format.getFrameSize()));
   }

   private static long bytesForSeconds(AudioFormat format, float seconds) {
      return seconds <= 0.0F ? 0L : (long)Math.round(format.getSampleRate() * seconds) * format.getFrameSize();
   }

   private static long skip(AudioInputStream stream, long bytesToSkip, byte[] buffer, int frameSize) throws IOException {
      long alignedTarget = PcmFrameAlignment.alignDown(Math.max(0L, bytesToSkip), frameSize);
      long remaining = alignedTarget;

      while (remaining > 0L) {
         int request = PcmFrameAlignment.alignedRequest(remaining, buffer.length, frameSize);
         if (request <= 0) {
            break;
         }

         int read = stream.read(buffer, 0, request);
         if (read < 0) {
            break;
         }

         if (read > 0) {
            remaining -= read;
         }
      }

      return alignedTarget - remaining;
   }

   private static long millisToBytes(long bytesPerSecond, long millis) {
      if (bytesPerSecond > 0L && millis > 0L) {
         double bytes = bytesPerSecond * (millis / 1000.0);
         return bytes >= 9.223372E18F ? Long.MAX_VALUE : Math.round(bytes);
      } else {
         return 0L;
      }
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }

   public record Result(long timelineOffsetMillis, int passes, long skippedBytes, int frameSize) {
      public float timelineOffsetSeconds() {
         return (float)this.timelineOffsetMillis / 1000.0F;
      }

      public boolean isFrameAligned() {
         return this.frameSize > 0 && this.skippedBytes % this.frameSize == 0L;
      }
   }
}
