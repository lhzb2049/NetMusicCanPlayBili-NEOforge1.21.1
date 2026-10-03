package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public final class Nv12TextureSet implements VideoYuvTextureSet {
   private static final boolean PBO_UPLOAD = VideoPipelineProperties.upload().nv12PboEnabled();
   private final ResourceLocation yId;
   private final ResourceLocation uvId;
   private final ResourceLocation placeholderId;
   private final String labelPrefix;
   private Yuv420pPlaneTexture yTexture;
   private Nv12UvTexture uvTexture;
   private int width;
   private int height;

   public Nv12TextureSet(ResourceLocation yId, ResourceLocation uvId, ResourceLocation placeholderId, String labelPrefix) {
      this.yId = yId;
      this.uvId = uvId;
      this.placeholderId = placeholderId;
      this.labelPrefix = labelPrefix;
   }

   @Override
   public ResourceLocation yId() {
      return this.yId;
   }

   @Override
   public ResourceLocation uId() {
      return this.uvId;
   }

   @Override
   public ResourceLocation vId() {
      return this.placeholderId;
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
      return Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
   }

   @Override
   public boolean upload(byte[] nv12, int width, int height) {
      int ySize = width * height;
      int uvWidth = Math.max(1, width / 2);
      int uvHeight = Math.max(1, height / 2);
      int uvSize = uvWidth * uvHeight * 2;
      if (nv12 != null && nv12.length >= ySize + uvSize) {
         this.ensureTextures(width, height, uvWidth, uvHeight);
         if (PBO_UPLOAD) {
            boolean yOk = this.yTexture.uploadPbo(nv12, 0);
            boolean uvOk = this.uvTexture.uploadPbo(nv12, ySize);
            if (yOk && uvOk) {
               return true;
            }
         }

         this.yTexture.upload(nv12, 0);
         this.uvTexture.upload(nv12, ySize);
         return true;
      } else {
         return false;
      }
   }

   @Override
   public boolean upload(ByteBuffer nv12, int byteLength, int width, int height) {
      int ySize = width * height;
      int uvWidth = Math.max(1, width / 2);
      int uvHeight = Math.max(1, height / 2);
      int uvSize = uvWidth * uvHeight * 2;
      if (nv12 != null && byteLength >= ySize + uvSize && nv12.limit() >= ySize + uvSize) {
         this.ensureTextures(width, height, uvWidth, uvHeight);
         if (PBO_UPLOAD) {
            boolean yOk = this.yTexture.uploadPbo(nv12, 0);
            boolean uvOk = this.uvTexture.uploadPbo(nv12, ySize);
            if (yOk && uvOk) {
               return true;
            }
         }

         return this.upload(frameBytes(nv12, ySize + uvSize), width, height);
      } else {
         return false;
      }
   }

   private static byte[] frameBytes(ByteBuffer buffer, int byteCount) {
      ByteBuffer src = buffer.duplicate();
      src.position(0);
      src.limit(Math.min(src.limit(), byteCount));
      byte[] out = new byte[src.remaining()];
      src.get(out);
      return out;
   }

   private void ensureTextures(int width, int height, int uvWidth, int uvHeight) {
      if (this.yTexture != null && this.yTexture.matches(width, height) && this.uvTexture != null && this.uvTexture.matches(uvWidth, uvHeight)) {
         this.width = width;
         this.height = height;
      } else {
         this.close();
         this.width = width;
         this.height = height;

         try {
            this.yTexture = new Yuv420pPlaneTexture(this.labelPrefix + "_y", width, height);
            this.uvTexture = new Nv12UvTexture(this.labelPrefix + "_uv", uvWidth, uvHeight);
            Minecraft.getInstance().getTextureManager().register(this.yId, this.yTexture);
            Minecraft.getInstance().getTextureManager().register(this.uvId, this.uvTexture);
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

      if (this.uvTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.uvId);
         this.uvTexture.close();
         this.uvTexture = null;
      }
   }
}
