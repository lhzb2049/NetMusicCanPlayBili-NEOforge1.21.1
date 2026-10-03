package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.nio.ByteBuffer;

public record HandheldVideoFrame(
   byte[] data,
   ByteBuffer buffer,
   int byteLength,
   Fmp4NativeVideoDecoder.DecodedFrame.Format format,
   int width,
   int height,
   long ptsNanos,
   AutoCloseable delegate
) implements AutoCloseable {
   public static HandheldVideoFrame retain(Fmp4NativeVideoDecoder.DecodedFrame decoded, int byteLength, int width, int height, long ptsNanos) {
      ByteBuffer buffer = decoded.buffer();
      byte[] data = buffer == null ? decoded.data() : null;
      return new HandheldVideoFrame(data, buffer, byteLength, decoded.format(), width, height, ptsNanos, decoded);
   }

   public HandheldVideoFrame retain() {
      if (this.delegate instanceof Fmp4NativeVideoDecoder.DecodedFrame decoded) {
         Fmp4NativeVideoDecoder.DecodedFrame retained = decoded.retain();
         ByteBuffer retainedBuffer = retained.buffer();
         return new HandheldVideoFrame(
            retainedBuffer == null ? retained.data() : null, retainedBuffer, this.byteLength, this.format, this.width, this.height, this.ptsNanos, retained
         );
      } else {
         return new HandheldVideoFrame(this.data, this.buffer, this.byteLength, this.format, this.width, this.height, this.ptsNanos, null);
      }
   }

   @Override
   public void close() {
      if (this.delegate != null) {
         try {
            this.delegate.close();
         } catch (Exception var2) {
         }
      }
   }
}
