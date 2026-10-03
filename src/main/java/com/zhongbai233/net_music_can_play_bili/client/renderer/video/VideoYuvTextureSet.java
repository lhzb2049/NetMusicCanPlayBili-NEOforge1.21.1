package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.nio.ByteBuffer;
import net.minecraft.resources.ResourceLocation;

public interface VideoYuvTextureSet extends AutoCloseable {
   ResourceLocation yId();

   ResourceLocation uId();

   ResourceLocation vId();

   int width();

   int height();

   Fmp4NativeVideoDecoder.DecodedFrame.Format format();

   boolean upload(byte[] var1, int var2, int var3);

   boolean upload(ByteBuffer var1, int var2, int var3, int var4);

   @Override
   void close();
}
