package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11C;

final class RawRg8GlTexture extends AbstractTexture {
   private static final int GL_TEXTURE_MAX_LEVEL = 33085;
   private static final int GL_TEXTURE_BASE_LEVEL = 33084;
   private final String label;
   private final int width;
   private final int height;
   private boolean created;

   private RawRg8GlTexture(String label, int width, int height) {
      this.label = label;
      this.width = Math.max(1, width);
      this.height = Math.max(1, height);
   }

   static RawRg8GlTexture create(String label, int width, int height) {
      RawRg8GlTexture texture = new RawRg8GlTexture(label, width, height);
      texture.ensureCreated();
      return texture;
   }

   String label() {
      return this.label;
   }

   int width() {
      return this.width;
   }

   int height() {
      return this.height;
   }

   boolean isCreated() {
      return this.created;
   }

   void ensureCreated() {
      if (!this.created) {
         int previousTexture = GL11C.glGetInteger(32873);
         int previousUnpackAlignment = GL11C.glGetInteger(3317);

         try {
            GlStateManager._bindTexture(this.getId());
            GL11C.glTexParameteri(3553, 33085, 0);
            GL11C.glTexParameteri(3553, 33084, 0);
            GL11C.glTexParameteri(3553, 10241, 9729);
            GL11C.glTexParameteri(3553, 10240, 9729);
            GL11C.glTexParameteri(3553, 10242, 10497);
            GL11C.glTexParameteri(3553, 10243, 10497);
            GL11C.glPixelStorei(3317, 1);
            GL11C.glTexImage2D(3553, 0, 33323, this.width, this.height, 0, 33319, 5121, 0L);
            int error = GL11C.glGetError();
            if (error != 0) {
               this.releaseId();
               throw new IllegalStateException("创建 GL_RG8 NV12 UV 纹理失败，glError=" + error);
            }

            this.created = true;
         } finally {
            GL11C.glPixelStorei(3317, previousUnpackAlignment);
            GlStateManager._bindTexture(previousTexture);
         }
      }
   }

   void upload(ByteBuffer pixels) {
      this.ensureCreated();
      GlStateManager._bindTexture(this.getId());
      GL11C.glPixelStorei(3317, 1);
      GL11C.glTexSubImage2D(3553, 0, 0, 0, this.width, this.height, 33319, 5121, pixels);
   }

   public void load(ResourceManager resourceManager) {
   }

   public void close() {
      this.created = false;
      this.releaseId();
   }
}
