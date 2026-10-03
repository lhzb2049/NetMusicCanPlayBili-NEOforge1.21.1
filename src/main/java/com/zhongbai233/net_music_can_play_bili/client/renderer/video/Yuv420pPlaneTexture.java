package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

final class Yuv420pPlaneTexture extends AbstractTexture {
   private static final int GL_TEXTURE_MAX_LEVEL = 33085;
   private static final int GL_TEXTURE_BASE_LEVEL = 33084;
   private final String label;
   private int width;
   private int height;
   private ByteBuffer uploadBuffer;
   private Nv12PboUploader pboUploader;
   private boolean created;

   Yuv420pPlaneTexture(String label, int width, int height) {
      this.label = label;
      this.recreate(width, height);
   }

   boolean matches(int width, int height) {
      return this.width == width && this.height == height;
   }

   void recreate(int width, int height) {
      this.close();
      this.width = Math.max(1, width);
      this.height = Math.max(1, height);
      int previousTexture = GL11C.glGetInteger(32873);
      int previousUnpackAlignment = GL11C.glGetInteger(3317);

      try {
         int id = this.getId();
         GlStateManager._bindTexture(id);
         GL11C.glTexParameteri(3553, 33085, 0);
         GL11C.glTexParameteri(3553, 33084, 0);
         GL11C.glTexParameteri(3553, 10241, 9729);
         GL11C.glTexParameteri(3553, 10240, 9729);
         GL11C.glTexParameteri(3553, 10242, 10497);
         GL11C.glTexParameteri(3553, 10243, 10497);
         GL11C.glPixelStorei(3317, 1);
         GL11C.glTexImage2D(3553, 0, 33321, this.width, this.height, 0, 6403, 5121, 0L);
         int error = GL11C.glGetError();
         if (error != 0) {
            this.releaseId();
            throw new IllegalStateException("创建 GL_R8 YUV 平面纹理失败，glError=" + error);
         }

         this.created = true;

         try {
            this.uploadBuffer = MemoryUtil.memAlloc(this.width * this.height);
            MemoryResourceTracker.allocated(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
         } catch (LinkageError | RuntimeException var11) {
            this.close();
            throw var11;
         }
      } finally {
         GL11C.glPixelStorei(3317, previousUnpackAlignment);
         GlStateManager._bindTexture(previousTexture);
      }
   }

   void upload(byte[] yuv420p, int offset) {
      if (!this.created || this.width == 0) {
         this.recreate(this.width, this.height);
      }

      int byteCount = this.width * this.height;
      if (this.uploadBuffer == null || this.uploadBuffer.capacity() < byteCount) {
         if (this.uploadBuffer != null) {
            MemoryResourceTracker.freed(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
            MemoryUtil.memFree(this.uploadBuffer);
         }

         this.uploadBuffer = MemoryUtil.memAlloc(byteCount);
         MemoryResourceTracker.allocated(MemoryResourceTracker.Category.TEXTURE_STAGING, this.uploadBuffer.capacity());
      }

      this.uploadBuffer.clear();
      this.uploadBuffer.put(yuv420p, offset, byteCount);
      this.uploadBuffer.flip();
      GlStateManager._bindTexture(this.getId());
      GL11C.glPixelStorei(3317, 1);
      GL11C.glTexSubImage2D(3553, 0, 0, 0, this.width, this.height, 6403, 5121, this.uploadBuffer);
   }

   boolean uploadPbo(byte[] data, int offset) {
      if (!this.created) {
         this.recreate(this.width, this.height);
      }

      if (this.pboUploader == null) {
         this.pboUploader = new Nv12PboUploader(this.label + "_pbo");
      }

      return this.pboUploader.uploadRed8(this, data, offset, this.width, this.height);
   }

   boolean uploadPbo(ByteBuffer data, int offset) {
      if (!this.created) {
         this.recreate(this.width, this.height);
      }

      if (this.pboUploader == null) {
         this.pboUploader = new Nv12PboUploader(this.label + "_pbo");
      }

      return this.pboUploader.uploadRed8(this, data, offset, this.width, this.height);
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

      this.created = false;
      this.releaseId();
   }
}
