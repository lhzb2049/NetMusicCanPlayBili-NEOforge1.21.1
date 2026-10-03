package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.slf4j.Logger;

final class Nv12PboUploader implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int GL_PIXEL_UNPACK_BUFFER = 35052;
   private static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 35055;
   private static final int GL_UNPACK_SKIP_PIXELS = 3316;
   private static final int GL_UNPACK_SKIP_ROWS = 3315;
   private static final int GL_UNPACK_IMAGE_HEIGHT = 32878;
   private static final int GL_UNPACK_SKIP_IMAGES = 32877;
   private static final int RING_SIZE = 3;
   private static final int MAP_FLAGS = 34;
   private final String label;
   private final Nv12PboUploader.PboSlot[] slots = new Nv12PboUploader.PboSlot[3];
   private int nextSlot;
   private boolean loggedFirstUpload;
   private boolean loggedBackpressure;
   private boolean disabled;

   Nv12PboUploader(String label) {
      this.label = label;
   }

   boolean uploadRed8(AbstractTexture texture, byte[] data, int offset, int width, int height) {
      int bytes = width * height;
      return data != null && data.length >= offset + bytes
         ? this.upload(texture, width, height, 6403, 5121, bytes, mapped -> mapped.put(data, offset, bytes))
         : false;
   }

   boolean uploadRed8(AbstractTexture texture, ByteBuffer data, int offset, int width, int height) {
      int bytes = width * height;
      return data != null && data.limit() >= offset + bytes ? this.upload(texture, width, height, 6403, 5121, bytes, mapped -> {
         ByteBuffer src = data.duplicate();
         src.position(offset);
         src.limit(offset + bytes);
         mapped.put(src);
      }) : false;
   }

   boolean uploadNv12UvAsRgba8(AbstractTexture texture, byte[] nv12, int offset, int width, int height) {
      int pixels = width * height;
      int sourceBytes = pixels * 2;
      int uploadBytes = pixels * 4;
      return nv12 != null && nv12.length >= offset + sourceBytes ? this.upload(texture, width, height, 6408, 5121, uploadBytes, mapped -> {
         int src = offset;

         for (int i = 0; i < pixels; i++) {
            mapped.put(nv12[src++]);
            mapped.put(nv12[src++]);
            mapped.put((byte)0);
            mapped.put((byte)-1);
         }
      }) : false;
   }

   boolean uploadNv12UvAsRg8(AbstractTexture texture, byte[] nv12, int offset, int width, int height) {
      int pixels = width * height;
      int bytes = pixels * 2;
      return nv12 != null && nv12.length >= offset + bytes
         ? this.upload(texture, width, height, 33319, 5121, bytes, mapped -> mapped.put(nv12, offset, bytes))
         : false;
   }

   boolean uploadNv12UvAsRg8(AbstractTexture texture, ByteBuffer nv12, int offset, int width, int height) {
      int pixels = width * height;
      int bytes = pixels * 2;
      return nv12 != null && nv12.limit() >= offset + bytes ? this.upload(texture, width, height, 33319, 5121, bytes, mapped -> {
         ByteBuffer src = nv12.duplicate();
         src.position(offset);
         src.limit(offset + bytes);
         mapped.put(src);
      }) : false;
   }

   private boolean upload(AbstractTexture texture, int width, int height, int format, int type, int bytes, Nv12PboUploader.BufferWriter writer) {
      if (texture == null) {
         return false;
      } else if (bytes > 0 && !this.disabled) {
         int previousPbo = GL11C.glGetInteger(35055);
         int previousTexture = GL11C.glGetInteger(32873);
         int previousUnpackAlignment = GL11C.glGetInteger(3317);
         int previousUnpackRowLength = GL11C.glGetInteger(3314);
         int previousUnpackSkipPixels = GL11C.glGetInteger(3316);
         int previousUnpackSkipRows = GL11C.glGetInteger(3315);
         int previousUnpackImageHeight = GL11C.glGetInteger(32878);
         int previousUnpackSkipImages = GL11C.glGetInteger(32877);
         boolean mappedBuffer = false;

         int error;
         try {
            clearGlErrors();
            Nv12PboUploader.PboSlot slot = this.acquireSlot(bytes);
            if (slot != null) {
               GL15C.glBindBuffer(35052, slot.pbo);
               error = GL11C.glGetError();
               if (error != 0) {
                  this.disabled = true;
                  LOGGER.warn("NV12/PBO: {} 绑定 PBO 失败 {}，本纹理后续回退普通上传", this.label, glErrorName(error));
                  return false;
               }

               ByteBuffer mapped = GL30C.glMapBufferRange(35052, 0L, bytes, 34);
               if (mapped == null) {
                  return false;
               }

               mappedBuffer = true;
               writer.write(mapped);
               GL15C.glUnmapBuffer(35052);
               mappedBuffer = false;
               GlStateManager._bindTexture(texture.getId());
               GL11C.glPixelStorei(3317, 1);
               GL11C.glPixelStorei(3314, 0);
               GL11C.glPixelStorei(3316, 0);
               GL11C.glPixelStorei(3315, 0);
               GL11C.glPixelStorei(32878, 0);
               GL11C.glPixelStorei(32877, 0);
               GL11C.glTexSubImage2D(3553, 0, 0, 0, width, height, format, type, 0L);
               error = GL11C.glGetError();
               if (error != 0) {
                  this.disabled = true;
                  LOGGER.warn("NV12/PBO: {} 触发 OpenGL 错误 {}，本纹理后续禁用 PBO 并回退普通上传", this.label, glErrorName(error));
                  return false;
               }

               slot.fence = GL32C.glFenceSync(37143, 0);
               if (!this.loggedFirstUpload) {
                  this.loggedFirstUpload = true;
                  LOGGER.debug(
                     "NV12/PBO: 首次通过有界环形 PBO 上传 {}: {}x{}, format={}, bytes={}, pbo={}",
                     new Object[]{this.label, width, height, glFormatName(format), bytes, slot.pbo}
                  );
               }

               return true;
            }

            if (!this.loggedBackpressure) {
               this.loggedBackpressure = true;
               LOGGER.debug("NV12/PBO: {} 环形缓冲繁忙，本帧回退普通上传以限制驱动内存", this.label);
            }

            error = 0;
         } catch (LinkageError | RuntimeException var34) {
            LOGGER.warn("NV12/PBO: {} 上传失败，将回退 glTexSubImage2D 普通上传: {}", this.label, var34.toString());
            return false;
         } finally {
            if (mappedBuffer) {
               try {
                  GL15C.glUnmapBuffer(35052);
               } catch (RuntimeException var33) {
               }
            }

            GL11C.glPixelStorei(3317, previousUnpackAlignment);
            GL11C.glPixelStorei(3314, previousUnpackRowLength);
            GL11C.glPixelStorei(3316, previousUnpackSkipPixels);
            GL11C.glPixelStorei(3315, previousUnpackSkipRows);
            GL11C.glPixelStorei(32878, previousUnpackImageHeight);
            GL11C.glPixelStorei(32877, previousUnpackSkipImages);
            GlStateManager._bindTexture(previousTexture);
            GL15C.glBindBuffer(35052, previousPbo);
         }

         // 【反编译伪影修复】反编译器写成了 `(boolean)error`（int 局部变量），源码里非法。
         // 字节码实证：这条 return 对应 `iload 20; ireturn`，而 slot 20 在能走到这里的**唯一**路径上
         // 只被 `iconst_0; istore 20`（偏移 348，即 mapped==null 分支）写过 → 值恒为 false。
         return false;
      } else {
         return false;
      }
   }

   private static String glFormatName(int format) {
      if (format == 6403) {
         return "RED";
      } else if (format == 33319) {
         return "RG";
      } else {
         return format == 6408 ? "RGBA" : "0x" + Integer.toHexString(format);
      }
   }

   private static void clearGlErrors() {
      int i = 0;

      while (i < 8 && GL11C.glGetError() != 0) {
         i++;
      }
   }

   private static String glErrorName(int error) {
      return switch (error) {
         case 1280 -> "GL_INVALID_ENUM";
         case 1281 -> "GL_INVALID_VALUE";
         case 1282 -> "GL_INVALID_OPERATION";
         default -> "0x" + Integer.toHexString(error);
         case 1285 -> "GL_OUT_OF_MEMORY";
      };
   }

   private Nv12PboUploader.PboSlot acquireSlot(int bytes) {
      for (int checked = 0; checked < 3; checked++) {
         int index = (this.nextSlot + checked) % 3;
         Nv12PboUploader.PboSlot slot = this.slots[index];
         if (slot == null) {
            slot = new Nv12PboUploader.PboSlot(GL15C.glGenBuffers());
            this.slots[index] = slot;
         }

         if (isAvailable(slot)) {
            GL15C.glBindBuffer(35052, slot.pbo);
            if (slot.capacity < bytes) {
               GL15C.glBufferData(35052, bytes, 35040);
               MemoryResourceTracker.allocated(MemoryResourceTracker.Category.GPU_PBO, bytes - slot.capacity);
               slot.capacity = bytes;
            }

            this.nextSlot = (index + 1) % 3;
            return slot;
         }
      }

      return null;
   }

   private static boolean isAvailable(Nv12PboUploader.PboSlot slot) {
      if (slot.fence == 0L) {
         return true;
      } else {
         int status = GL32C.glClientWaitSync(slot.fence, 0, 0L);
         if (status != 37146 && status != 37148) {
            return false;
         } else {
            GL32C.glDeleteSync(slot.fence);
            slot.fence = 0L;
            return true;
         }
      }
   }

   @Override
   public void close() {
      for (int i = 0; i < this.slots.length; i++) {
         Nv12PboUploader.PboSlot slot = this.slots[i];
         if (slot != null) {
            if (slot.fence != 0L) {
               GL32C.glDeleteSync(slot.fence);
               slot.fence = 0L;
            }

            GL15C.glDeleteBuffers(slot.pbo);
            MemoryResourceTracker.freed(MemoryResourceTracker.Category.GPU_PBO, slot.capacity);
            this.slots[i] = null;
         }
      }

      this.nextSlot = 0;
   }

   @FunctionalInterface
   private interface BufferWriter {
      void write(ByteBuffer var1);
   }

   private static final class PboSlot {
      private final int pbo;
      private int capacity;
      private long fence;

      private PboSlot(int pbo) {
         this.pbo = pbo;
      }
   }
}
