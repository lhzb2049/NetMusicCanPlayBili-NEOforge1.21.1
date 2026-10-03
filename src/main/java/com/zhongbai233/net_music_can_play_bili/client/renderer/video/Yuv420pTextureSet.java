package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

final class Yuv420pTextureSet implements VideoYuvTextureSet {
   private final ResourceLocation yId;
   private final ResourceLocation uId;
   private final ResourceLocation vId;
   private final String labelPrefix;
   private Yuv420pPlaneTexture yTexture;
   private Yuv420pPlaneTexture uTexture;
   private Yuv420pPlaneTexture vTexture;
   private int width;
   private int height;

   Yuv420pTextureSet(ResourceLocation yId, ResourceLocation uId, ResourceLocation vId, String labelPrefix) {
      this.yId = yId;
      this.uId = uId;
      this.vId = vId;
      this.labelPrefix = labelPrefix;
   }

   @Override
   public ResourceLocation yId() {
      return this.yId;
   }

   @Override
   public ResourceLocation uId() {
      return this.uId;
   }

   @Override
   public ResourceLocation vId() {
      return this.vId;
   }

   @Override
   public int width() {
      return this.width;
   }

   @Override
   public int height() {
      return this.height;
   }

   @Override
   public Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
      return Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P;
   }

   @Override
   public boolean upload(byte[] yuv420p, int width, int height) {
      int ySize = width * height;
      int chromaWidth = Math.max(1, width / 2);
      int chromaHeight = Math.max(1, height / 2);
      int uvSize = chromaWidth * chromaHeight;
      if (yuv420p != null && yuv420p.length >= ySize + uvSize * 2) {
         this.ensureTextures(width, height, chromaWidth, chromaHeight);
         this.yTexture.upload(yuv420p, 0);
         this.uTexture.upload(yuv420p, ySize);
         this.vTexture.upload(yuv420p, ySize + uvSize);
         return true;
      } else {
         return false;
      }
   }

   @Override
   public boolean upload(ByteBuffer yuv420p, int byteLength, int width, int height) {
      int ySize = width * height;
      int chromaWidth = Math.max(1, width / 2);
      int chromaHeight = Math.max(1, height / 2);
      int uvSize = chromaWidth * chromaHeight;
      int requiredBytes = ySize + uvSize * 2;
      return yuv420p != null && byteLength >= requiredBytes && yuv420p.limit() >= requiredBytes
         ? this.upload(frameBytes(yuv420p, requiredBytes), width, height)
         : false;
   }

   private static byte[] frameBytes(ByteBuffer buffer, int byteCount) {
      ByteBuffer src = buffer.duplicate();
      src.position(0);
      src.limit(Math.min(src.limit(), byteCount));
      byte[] out = new byte[src.remaining()];
      src.get(out);
      return out;
   }

   private void ensureTextures(int width, int height, int chromaWidth, int chromaHeight) {
      if (this.yTexture != null
         && this.yTexture.matches(width, height)
         && this.uTexture != null
         && this.uTexture.matches(chromaWidth, chromaHeight)
         && this.vTexture != null
         && this.vTexture.matches(chromaWidth, chromaHeight)) {
         this.width = width;
         this.height = height;
      } else {
         this.close();
         this.width = width;
         this.height = height;

         try {
            this.yTexture = new Yuv420pPlaneTexture(this.labelPrefix + "_y", width, height);
            this.uTexture = new Yuv420pPlaneTexture(this.labelPrefix + "_u", chromaWidth, chromaHeight);
            this.vTexture = new Yuv420pPlaneTexture(this.labelPrefix + "_v", chromaWidth, chromaHeight);
            Minecraft.getInstance().getTextureManager().register(this.yId, this.yTexture);
            Minecraft.getInstance().getTextureManager().register(this.uId, this.uTexture);
            Minecraft.getInstance().getTextureManager().register(this.vId, this.vTexture);
         } catch (LinkageError | RuntimeException var6) {
            this.close();
            throw var6;
         }
      }
   }

   @Override
   public void close() {
      if (this.yTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.yId);
         this.yTexture.close();
         this.yTexture = null;
      }

      if (this.uTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.uId);
         this.uTexture.close();
         this.uTexture = null;
      }

      if (this.vTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.vId);
         this.vTexture.close();
         this.vTexture = null;
      }
   }
}
