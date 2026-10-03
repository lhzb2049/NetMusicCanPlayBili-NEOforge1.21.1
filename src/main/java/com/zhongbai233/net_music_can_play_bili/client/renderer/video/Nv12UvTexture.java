package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

final class Nv12UvTexture extends AbstractTexture {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean RG8_ENABLED = VideoPipelineProperties.upload().nv12UvRg8Enabled();
   private final String label;
   private int width;
   private int height;
   private RawRg8GlTexture rg8Texture;
   private DynamicTexture rgbaFallback;
   private ByteBuffer uploadBuffer;
   private Nv12PboUploader pboUploader;
   private boolean rg8Path;

   Nv12UvTexture(String label, int width, int height) {
      this.label = label;
      this.recreate(width, height);
   }

   private AbstractTexture currentTexture() {
      return (AbstractTexture)(this.rg8Path ? this.rg8Texture : this.rgbaFallback);
   }

   public int getId() {
      AbstractTexture current = this.currentTexture();
      return current != null ? current.getId() : super.getId();
   }

   public void bind() {
      AbstractTexture current = this.currentTexture();
      if (current != null) {
         current.bind();
      } else {
         super.bind();
      }
   }

   boolean matches(int width, int height) {
      return this.width == width && this.height == height && this.currentTexture() != null;
   }

   void recreate(int width, int height) {
      this.close();
      this.width = Math.max(1, width);
      this.height = Math.max(1, height);
      this.rg8Path = false;
      if (RG8_ENABLED) {
         try {
            RawRg8GlTexture created = RawRg8GlTexture.create(this.label, this.width, this.height);
            this.rg8Texture = created;
            this.rg8Path = true;
         } catch (LinkageError | RuntimeException var5) {
            LOGGER.warn("NV12: 创建 GL_RG8 UV 纹理失败，回退 RGBA8: {}", var5.toString());
            if (this.rg8Texture != null) {
               this.rg8Texture.close();
            }

            this.rg8Texture = null;
         }
      }

      try {
         if (this.rg8Texture == null) {
            this.rgbaFallback = new DynamicTexture(this.width, this.height, false);
         }

         this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height * (this.rg8Path ? 2 : 4));
         MemoryResourceTracker.allocated(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
      } catch (LinkageError | RuntimeException var4) {
         this.close();
         throw var4;
      }
   }

   void upload(byte[] nv12, int offset) {
      if (this.currentTexture() == null) {
         this.recreate(this.width, this.height);
      }

      int pixelCount = this.width * this.height;
      int byteCount = pixelCount * (this.rg8Path ? 2 : 4);
      if (this.uploadBuffer == null || this.uploadBuffer.capacity() < byteCount) {
         if (this.uploadBuffer != null) {
            MemoryResourceTracker.freed(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
            MemoryUtil.memFree(this.uploadBuffer);
         }

         this.uploadBuffer = MemoryUtil.memAlloc(byteCount);
         MemoryResourceTracker.allocated(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
      }

      this.uploadBuffer.clear();
      if (this.rg8Path) {
         this.uploadBuffer.put(nv12, offset, byteCount);
      } else {
         int src = offset;

         for (int i = 0; i < pixelCount; i++) {
            this.uploadBuffer.put(nv12[src++]);
            this.uploadBuffer.put(nv12[src++]);
            this.uploadBuffer.put((byte)0);
            this.uploadBuffer.put((byte)-1);
         }
      }

      this.uploadBuffer.flip();
      if (this.pboUploader == null || !this.rg8Path || !this.pboUploader.uploadNv12UvAsRg8(this.rg8Texture, nv12, offset, this.width, this.height)) {
         if (this.pboUploader == null || this.rg8Path || !this.pboUploader.uploadNv12UvAsRgba8(this.rgbaFallback, nv12, offset, this.width, this.height)) {
            int targetId = this.currentTexture().getId();
            GlStateManager._bindTexture(targetId);
            GL11C.glPixelStorei(3317, 1);
            GL11C.glTexSubImage2D(3553, 0, 0, 0, this.width, this.height, this.rg8Path ? '舧' : 6408, 5121, this.uploadBuffer);
         }
      }
   }

   boolean uploadPbo(byte[] nv12, int offset) {
      if (this.currentTexture() == null) {
         this.recreate(this.width, this.height);
      }

      if (this.pboUploader == null) {
         this.pboUploader = new Nv12PboUploader(this.label + "_pbo");
      }

      return this.rg8Path
         ? this.pboUploader.uploadNv12UvAsRg8(this.rg8Texture, nv12, offset, this.width, this.height)
         : this.pboUploader.uploadNv12UvAsRgba8(this.rgbaFallback, nv12, offset, this.width, this.height);
   }

   boolean uploadPbo(ByteBuffer nv12, int offset) {
      if (this.currentTexture() == null) {
         this.recreate(this.width, this.height);
      }

      if (!this.rg8Path) {
         return false;
      } else {
         if (this.pboUploader == null) {
            this.pboUploader = new Nv12PboUploader(this.label + "_pbo");
         }

         return this.pboUploader.uploadNv12UvAsRg8(this.rg8Texture, nv12, offset, this.width, this.height);
      }
   }

   public void load(ResourceManager resourceManager) {
   }

   public void close() {
      if (this.pboUploader != null) {
         this.pboUploader.close();
         this.pboUploader = null;
      }

      if (this.uploadBuffer != null) {
         MemoryResourceTracker.freed(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
         MemoryUtil.memFree(this.uploadBuffer);
         this.uploadBuffer = null;
      }

      if (this.rg8Texture != null) {
         this.rg8Texture.close();
         this.rg8Texture = null;
      }

      if (this.rgbaFallback != null) {
         this.rgbaFallback.close();
         this.rgbaFallback = null;
      }

      this.rg8Path = false;
      super.close();
   }
}
