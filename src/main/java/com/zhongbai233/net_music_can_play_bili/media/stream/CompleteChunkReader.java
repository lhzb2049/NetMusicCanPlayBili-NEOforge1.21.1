package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class CompleteChunkReader {
   private CompleteChunkReader() {
   }

   static byte[] read(InputStream body, long expectedLength, int copyBufferSize) throws IOException {
      if (expectedLength > 0L && expectedLength <= 2147483647L) {
         ByteArrayOutputStream chunk = new ByteArrayOutputStream((int)expectedLength);
         byte[] buffer = new byte[Math.min(Math.max(1, copyBufferSize), (int)expectedLength)];
         long remaining = expectedLength;

         while (remaining > 0L) {
            int n = body.read(buffer, 0, (int)Math.min((long)buffer.length, remaining));
            if (n < 0) {
               throw new IOException("CDN range chunk ended early: expected " + expectedLength + ", got " + chunk.size());
            }

            if (n != 0) {
               chunk.write(buffer, 0, n);
               remaining -= n;
            }
         }

         return chunk.toByteArray();
      } else {
         throw new IOException("invalid bounded chunk length " + expectedLength);
      }
   }
}
