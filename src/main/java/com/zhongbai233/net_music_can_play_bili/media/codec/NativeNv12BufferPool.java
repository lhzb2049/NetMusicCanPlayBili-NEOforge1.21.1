package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import org.lwjgl.system.MemoryUtil;

final class NativeNv12BufferPool {
   private final int maxIdle;
   private final ArrayDeque<NativeNv12BufferPool.NativeNv12Buffer> idle = new ArrayDeque<>();
   private boolean retired;

   NativeNv12BufferPool(int maxIdle) {
      this.maxIdle = Math.max(1, maxIdle);
   }

   synchronized NativeNv12BufferPool.NativeNv12Buffer acquire(int byteCount) {
      if (this.retired) {
         return null;
      } else {
         NativeNv12BufferPool.NativeNv12Buffer selected = null;

         while (!this.idle.isEmpty()) {
            NativeNv12BufferPool.NativeNv12Buffer candidate = this.idle.removeFirst();
            if (candidate.buffer().capacity() >= byteCount) {
               selected = candidate;
               break;
            }

            MemoryResourceTracker.freed(MemoryResourceTracker.Category.DECODER_NV12, candidate.buffer().capacity());
            MemoryUtil.memFree(candidate.buffer());
         }

         if (selected == null) {
            ByteBuffer buffer = MemoryUtil.memAlloc(byteCount).order(ByteOrder.nativeOrder());
            MemoryResourceTracker.allocated(MemoryResourceTracker.Category.DECODER_NV12, buffer.capacity());
            selected = new NativeNv12BufferPool.NativeNv12Buffer(buffer);
         }

         return selected;
      }
   }

   synchronized void release(NativeNv12BufferPool.NativeNv12Buffer buffer) {
      if (!this.retired && this.idle.size() < this.maxIdle) {
         this.idle.addLast(buffer);
      } else {
         MemoryResourceTracker.freed(MemoryResourceTracker.Category.DECODER_NV12, buffer.buffer().capacity());
         MemoryUtil.memFree(buffer.buffer());
      }
   }

   synchronized void retire() {
      this.retired = true;

      while (!this.idle.isEmpty()) {
         ByteBuffer buffer = this.idle.removeFirst().buffer();
         MemoryResourceTracker.freed(MemoryResourceTracker.Category.DECODER_NV12, buffer.capacity());
         MemoryUtil.memFree(buffer);
      }
   }

   record NativeNv12Buffer(ByteBuffer buffer) {
   }
}
