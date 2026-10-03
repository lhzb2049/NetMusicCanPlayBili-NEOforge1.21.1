package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.nio.ByteBuffer;

final class VideoJni {
   private VideoJni() {
   }

   static native long decoderOpen(int var0, int var1);

   static native long decoderOpenForCodec(int var0, int var1, int var2);

   static native long decoderOpenForCodecWithHwaccel(int var0, int var1, int var2, String var3);

   static native int sendPacket(long var0, byte[] var2, int var3, int var4);

   static native int sendPacketWithPts(long var0, byte[] var2, int var3, int var4, long var5);

   static native byte[] getVideoFrame(long var0);

   static native int getVideoFrameInto(long var0, byte[] var2);

   static native byte[] getVideoFrameYuv420(long var0);

   static native byte[] getVideoFrameNv12(long var0);

   static native int getVideoFrameNv12IntoDirect(long var0, ByteBuffer var2);

   static native int receiveFrameNoCopy(long var0);

   static native long getLastFramePtsNanos(long var0);

   static native int sendEndOfStream(long var0);

   static native void flush(long var0);

   static native String getHwaccelName(long var0);

   static native void close(long var0);

   static native long getDimensions(long var0);

   static native long[] getNativeMemoryStats();
}
