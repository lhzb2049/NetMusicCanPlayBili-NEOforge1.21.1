package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;

final class FlacStreamSupport {
   private static final int STREAM_BUFFER_SIZE = 262144;

   private FlacStreamSupport() {
   }

   static AudioFormat audioFormat(byte[] dfLa) throws IOException {
      AudioFormat format = Fmp4ToMp4Converter.flacDfLaToAudioFormat(dfLa);
      if (format == null) {
         throw new IOException("unable to parse fMP4 FLAC dfLa metadata");
      } else {
         return format;
      }
   }

   static byte[] nativeFlacHeader(byte[] dfLa) {
      byte[] nativeMeta = Fmp4ToMp4Converter.dfLaToNativeFlacMetadata(dfLa);
      byte[] header = new byte[4 + nativeMeta.length];
      header[0] = 102;
      header[1] = 76;
      header[2] = 97;
      header[3] = 67;
      System.arraycopy(nativeMeta, 0, header, 4, nativeMeta.length);
      return header;
   }

   static long writeNativeHeader(BlockingAudioPipe output, byte[] dfLa) throws IOException {
      byte[] header = nativeFlacHeader(dfLa);
      output.write(header);
      return header.length;
   }

   static long copyMdat(InputStream input, long length, BlockingAudioPipe output, BooleanSupplier shouldStop) throws IOException {
      byte[] buffer = new byte[262144];
      long remaining = length;
      long copied = 0L;

      while ((shouldStop == null || !shouldStop.getAsBoolean()) && remaining > 0L) {
         int toRead = (int)Math.min((long)buffer.length, remaining);
         int n = input.read(buffer, 0, toRead);
         if (n < 0) {
            throw new EOFException("EOF while reading FLAC mdat");
         }

         output.write(buffer, 0, n);
         copied += n;
         remaining -= n;
      }

      return copied;
   }
}
