package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import org.slf4j.Logger;

public class VideoNativeDecoder implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String DEFAULT_HWACCEL = "auto";
   private long handle;
   private final int codecId;
   private final int targetWidth;
   private final int targetHeight;
   private boolean open;
   private String requestedHwaccel = "auto";
   private String actualHwaccel = "not-opened";
   private int originalWidth;
   private int originalHeight;
   private long totalFrames;

   public VideoNativeDecoder(int targetWidth, int targetHeight) {
      this(7, targetWidth, targetHeight);
   }

   public VideoNativeDecoder(int codecId, int targetWidth, int targetHeight) {
      if (codecId != 7 && codecId != 13) {
         throw new IllegalArgumentException("不支持的视频 codecId=" + codecId + "（仅支持 7=H.264, 13=AV1）");
      } else {
         this.codecId = codecId;
         this.targetWidth = targetWidth;
         this.targetHeight = targetHeight;
      }
   }

   public static void ensureLoaded() {
      Eac3NativeDecoder.preload();
   }

   public static boolean isNativeAvailable() {
      return Eac3NativeDecoder.isNativeAvailable();
   }

   public static VideoNativeDecoder.NativeMemoryStats nativeMemoryStats() {
      if (!isNativeAvailable()) {
         return VideoNativeDecoder.NativeMemoryStats.unavailable();
      } else {
         try {
            long[] values = VideoJni.getNativeMemoryStats();
            return values != null && values.length >= 11
               ? new VideoNativeDecoder.NativeMemoryStats(
                  true, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10]
               )
               : VideoNativeDecoder.NativeMemoryStats.unavailable();
         } catch (UnsatisfiedLinkError var1) {
            return VideoNativeDecoder.NativeMemoryStats.unavailable();
         } catch (Throwable var2) {
            LOGGER.debug("VideoNativeDecoder: 查询 FFmpeg 内存统计失败", var2);
            return VideoNativeDecoder.NativeMemoryStats.unavailable();
         }
      }
   }

   public void setRequestedHwaccel(String requestedHwaccel) {
      if (!this.open) {
         this.requestedHwaccel = requestedHwaccel != null && !requestedHwaccel.isBlank() ? requestedHwaccel.trim() : "auto";
      }
   }

   private boolean ensureOpen() {
      if (this.open && this.handle != 0L) {
         return true;
      } else if (!isNativeAvailable()) {
         return false;
      } else {
         this.handle = this.isHwaccelRequested()
            ? VideoJni.decoderOpenForCodecWithHwaccel(this.codecId, this.targetWidth, this.targetHeight, this.requestedHwaccel)
            : (
               this.codecId == 7
                  ? VideoJni.decoderOpen(this.targetWidth, this.targetHeight)
                  : VideoJni.decoderOpenForCodec(this.codecId, this.targetWidth, this.targetHeight)
            );
         if (this.handle == 0L) {
            LOGGER.error("VideoNativeDecoder: decoderOpen 失败 codecId={}, hwaccel={}", this.codecId, this.isHwaccelRequested() ? this.requestedHwaccel : "none");
            return false;
         } else {
            this.actualHwaccel = queryActualHwaccel(this.handle);
            LOGGER.debug(
               "VideoNativeDecoder 已打开: codecId={}, requestedHwaccel={}, actualHwaccel={}, target={}x{}",
               new Object[]{this.codecId, this.requestedHwaccel, this.actualHwaccel, this.targetWidth, this.targetHeight}
            );
            this.open = true;
            return true;
         }
      }
   }

   public synchronized boolean open() {
      return this.ensureOpen();
   }

   public synchronized String actualHwaccel() {
      return this.actualHwaccel;
   }

   public synchronized boolean isHardwareAccelerated() {
      return this.open && VideoHardwareBackendPolicy.isHardwareBackend(this.actualHwaccel);
   }

   private boolean isHwaccelRequested() {
      return !this.requestedHwaccel.isBlank() && !"none".equalsIgnoreCase(this.requestedHwaccel) && !"off".equalsIgnoreCase(this.requestedHwaccel);
   }

   private static String queryActualHwaccel(long handle) {
      try {
         String value = VideoJni.getHwaccelName(handle);
         return value != null && !value.isBlank() ? value : "unknown";
      } catch (UnsatisfiedLinkError var3) {
         return "unknown-old-native";
      } catch (Throwable var4) {
         LOGGER.warn("VideoNativeDecoder: 查询实际硬解后端失败", var4);
         return "unknown-error";
      }
   }

   public synchronized boolean sendPacket(byte[] data, int offset, int length) {
      if (!this.ensureOpen()) {
         return false;
      } else {
         return this.open && this.handle != 0L ? VideoJni.sendPacket(this.handle, data, offset, length) == 0 : false;
      }
   }

   public boolean sendPacket(byte[] data) {
      return this.sendPacket(data, 0, data.length);
   }

   public synchronized boolean sendPacket(byte[] data, long ptsNanos) {
      if (!this.ensureOpen()) {
         return false;
      } else if (!this.open || this.handle == 0L) {
         return false;
      } else if (ptsNanos < 0L) {
         return VideoJni.sendPacket(this.handle, data, 0, data.length) == 0;
      } else {
         try {
            int result = VideoJni.sendPacketWithPts(this.handle, data, 0, data.length, ptsNanos);
            return result == 0 ? true : VideoJni.sendPacket(this.handle, data, 0, data.length) == 0;
         } catch (UnsatisfiedLinkError var5) {
            return VideoJni.sendPacket(this.handle, data, 0, data.length) == 0;
         }
      }
   }

   public synchronized long lastFramePtsNanos() {
      if (this.open && this.handle != 0L) {
         try {
            return VideoJni.getLastFramePtsNanos(this.handle);
         } catch (UnsatisfiedLinkError var2) {
            return -1L;
         }
      } else {
         return -1L;
      }
   }

   public synchronized boolean sendEndOfStream() {
      if (this.open && this.handle != 0L) {
         try {
            int status = VideoJni.sendEndOfStream(this.handle);
            if (status == 0) {
               return true;
            } else if (status == 1) {
               throw new IllegalStateException("native EOF marker requires another receive drain");
            } else {
               throw new IllegalStateException("native EOF marker failed: codecId=" + this.codecId + ", hwaccel=" + this.actualHwaccel);
            }
         } catch (UnsatisfiedLinkError var2) {
            return false;
         }
      } else {
         return false;
      }
   }

   public synchronized byte[] getVideoFrame() {
      if (this.open && this.handle != 0L) {
         byte[] rgba = VideoJni.getVideoFrame(this.handle);
         if (rgba != null) {
            this.totalFrames++;
            if (this.totalFrames == 1L) {
               long dims = VideoJni.getDimensions(this.handle);
               this.originalWidth = (int)(dims >> 32);
               this.originalHeight = (int)dims;
            }
         }

         return rgba;
      } else {
         return null;
      }
   }

   public synchronized boolean getVideoFrameInto(byte[] output) {
      if (this.open && this.handle != 0L) {
         try {
            int status = VideoJni.getVideoFrameInto(this.handle, output);
            if (status < 0) {
               throw new IllegalStateException(
                  "native RGBA output failed: codecId="
                     + this.codecId
                     + ", hwaccel="
                     + this.actualHwaccel
                     + ", target="
                     + this.targetWidth
                     + "x"
                     + this.targetHeight
               );
            } else if (status == 0) {
               return false;
            } else {
               this.onFrameDecoded(output.length, false);
               return true;
            }
         } catch (UnsatisfiedLinkError var4) {
            byte[] frame = this.getVideoFrame();
            if (frame == null) {
               return false;
            } else if (output.length < frame.length) {
               throw new IllegalArgumentException("RGBA output buffer too small: " + output.length + " < " + frame.length);
            } else {
               System.arraycopy(frame, 0, output, 0, frame.length);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   private void onFrameDecoded(int byteLength, boolean yuv) {
      this.totalFrames++;
      if (this.totalFrames == 1L) {
         long dims = VideoJni.getDimensions(this.handle);
         this.originalWidth = (int)(dims >> 32);
         this.originalHeight = (int)dims;
      }
   }

   public synchronized byte[] getVideoFrameYuv420() {
      if (this.open && this.handle != 0L) {
         byte[] yuv;
         try {
            yuv = VideoJni.getVideoFrameYuv420(this.handle);
         } catch (UnsatisfiedLinkError var4) {
            LOGGER.warn("VideoNativeDecoder: 当前 native 缺少 getVideoFrameYuv420，回退 RGBA", var4);
            return this.getVideoFrame();
         }

         if (yuv != null) {
            this.totalFrames++;
            if (this.totalFrames == 1L) {
               long dims = VideoJni.getDimensions(this.handle);
               this.originalWidth = (int)(dims >> 32);
               this.originalHeight = (int)dims;
            }
         }

         return yuv;
      } else {
         return null;
      }
   }

   public synchronized byte[] getVideoFrameNv12() {
      if (this.open && this.handle != 0L) {
         byte[] nv12;
         try {
            nv12 = VideoJni.getVideoFrameNv12(this.handle);
         } catch (UnsatisfiedLinkError var4) {
            LOGGER.warn("VideoNativeDecoder: 当前 native 缺少 getVideoFrameNv12，回退 YUV420P", var4);
            return this.getVideoFrameYuv420();
         }

         if (nv12 != null) {
            this.totalFrames++;
            if (this.totalFrames == 1L) {
               long dims = VideoJni.getDimensions(this.handle);
               this.originalWidth = (int)(dims >> 32);
               this.originalHeight = (int)dims;
            }
         }

         return nv12;
      } else {
         return null;
      }
   }

   public synchronized boolean getVideoFrameNv12Into(ByteBuffer output) {
      if (!this.open || this.handle == 0L || output == null) {
         return false;
      } else if (!output.isDirect()) {
         throw new IllegalArgumentException("NV12 output buffer must be a direct ByteBuffer");
      } else {
         output.clear();

         try {
            int status = VideoJni.getVideoFrameNv12IntoDirect(this.handle, output);
            if (status < 0) {
               throw new IllegalStateException(
                  "native NV12 direct output failed: codecId="
                     + this.codecId
                     + ", hwaccel="
                     + this.actualHwaccel
                     + ", target="
                     + this.targetWidth
                     + "x"
                     + this.targetHeight
               );
            } else if (status == 0) {
               return false;
            } else {
               int byteLength = Math.max(1, this.getOutputWidth()) * Math.max(1, this.getOutputHeight()) * 3 / 2;
               output.position(0);
               output.limit(Math.min(output.capacity(), byteLength));
               this.totalFrames++;
               if (this.totalFrames == 1L) {
                  long dims = VideoJni.getDimensions(this.handle);
                  this.originalWidth = (int)(dims >> 32);
                  this.originalHeight = (int)dims;
               }

               return true;
            }
         } catch (UnsatisfiedLinkError var6) {
            byte[] nv12 = this.getVideoFrameNv12();
            if (nv12 == null) {
               return false;
            } else if (output.capacity() < nv12.length) {
               throw new IllegalArgumentException("NV12 direct output buffer too small: " + output.capacity() + " < " + nv12.length);
            } else {
               output.clear();
               output.put(nv12);
               output.flip();
               return true;
            }
         }
      }
   }

   public synchronized boolean receiveFrameNoCopy() {
      if (this.open && this.handle != 0L) {
         try {
            int status = VideoJni.receiveFrameNoCopy(this.handle);
            if (status < 0) {
               throw new IllegalStateException("native receiveFrameNoCopy failed: codecId=" + this.codecId + ", hwaccel=" + this.actualHwaccel);
            } else if (status == 0) {
               return false;
            } else {
               this.totalFrames++;
               if (this.totalFrames == 1L) {
                  long dims = VideoJni.getDimensions(this.handle);
                  this.originalWidth = (int)(dims >> 32);
                  this.originalHeight = (int)dims;
               }

               return true;
            }
         } catch (UnsatisfiedLinkError var4) {
            return this.getVideoFrame() != null;
         }
      } else {
         return false;
      }
   }

   public synchronized void flush() {
      if (this.open && this.handle != 0L) {
         VideoJni.flush(this.handle);
      }
   }

   @Override
   public synchronized void close() {
      this.open = false;
      if (this.handle != 0L) {
         long closingHandle = this.handle;

         try {
            VideoJni.close(closingHandle);
            this.handle = 0L;
         } catch (Error | RuntimeException var4) {
            this.handle = closingHandle;
            throw var4;
         }
      }
   }

   public int getOriginalWidth() {
      return this.originalWidth;
   }

   public int getOriginalHeight() {
      return this.originalHeight;
   }

   public int getOutputWidth() {
      return this.targetWidth > 0 ? this.targetWidth : this.originalWidth;
   }

   public int getOutputHeight() {
      return this.targetHeight > 0 ? this.targetHeight : this.originalHeight;
   }

   public long totalFrames() {
      return this.totalFrames;
   }

   public record NativeMemoryStats(
      boolean available,
      long ffmpegCurrentBytes,
      long ffmpegPeakBytes,
      long allocations,
      long reallocations,
      long frees,
      long d3d11TextureCurrent,
      long d3d11TexturePeak,
      long d3d11SurfaceCurrent,
      long d3d11SurfacePeak,
      long d3d11LogicalBytesCurrent,
      long d3d11LogicalBytesPeak
   ) {
      public static VideoNativeDecoder.NativeMemoryStats unavailable() {
         return new VideoNativeDecoder.NativeMemoryStats(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
      }
   }
}
