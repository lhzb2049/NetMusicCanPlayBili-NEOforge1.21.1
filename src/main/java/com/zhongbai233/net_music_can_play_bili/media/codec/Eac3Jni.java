package com.zhongbai233.net_music_can_play_bili.media.codec;

final class Eac3Jni {
   private Eac3Jni() {
   }

   static native long decoderOpen();

   static native float[][] decode(long var0, byte[] var2, int var3, int var4);

   static native void flush(long var0);

   static native void close(long var0);

   static native String version();
}
