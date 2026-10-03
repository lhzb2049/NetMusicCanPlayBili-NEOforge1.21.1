package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.IOException;
import java.io.InputStream;

public final class BlockingAudioPipe extends InputStream {
   private static final int DEFAULT_MAX_CAPACITY = 33554432;
   private final int initialCapacity;
   private final int maxCapacity;
   private byte[] buffer;
   private int readPos;
   private int writePos;
   private int size;
   private boolean readerClosed;
   private boolean writerClosed;

   public BlockingAudioPipe(int capacity) {
      this(capacity, 33554432);
   }

   public BlockingAudioPipe(int capacity, int maxCapacity) {
      this.initialCapacity = Math.max(4096, capacity);
      this.maxCapacity = Math.max(this.initialCapacity, maxCapacity);
      this.buffer = new byte[this.initialCapacity];
   }

   @Override
   public int read() throws IOException {
      byte[] one = new byte[1];
      int n = this.read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
   }

   @Override
   public synchronized int read(byte[] b, int off, int len) throws IOException {
      if (b == null) {
         throw new NullPointerException("buffer");
      } else if (off < 0 || len < 0 || len > b.length - off) {
         throw new IndexOutOfBoundsException();
      } else if (len == 0) {
         return 0;
      } else {
         while (this.size == 0 && !this.writerClosed && !this.readerClosed) {
            this.waitForPipe();
         }

         if (this.size == 0 && this.writerClosed) {
            return -1;
         } else if (this.readerClosed) {
            return -1;
         } else {
            int n = Math.min(len, this.size);
            int first = Math.min(n, this.buffer.length - this.readPos);
            System.arraycopy(this.buffer, this.readPos, b, off, first);
            int second = n - first;
            if (second > 0) {
               System.arraycopy(this.buffer, 0, b, off + first, second);
            }

            this.readPos = (this.readPos + n) % this.buffer.length;
            this.size -= n;
            if (this.size == 0 && this.buffer.length > this.initialCapacity * 8) {
               this.buffer = new byte[this.initialCapacity * 2];
               this.readPos = 0;
               this.writePos = 0;
            }

            this.notifyAll();
            return n;
         }
      }
   }

   public synchronized void write(byte[] b) throws IOException {
      this.write(b, 0, b.length);
   }

   public synchronized void write(byte[] b, int off, int len) throws IOException {
      if (b == null) {
         throw new NullPointerException("buffer");
      } else if (off >= 0 && len >= 0 && len <= b.length - off) {
         int written = 0;

         while (written < len) {
            while (this.size == this.buffer.length && !this.readerClosed && this.buffer.length >= this.maxCapacity) {
               this.waitForPipe();
            }

            if (this.readerClosed) {
               throw new IOException("audio pipe reader closed");
            }

            if (this.size == this.buffer.length) {
               this.grow();
            }

            int available = this.buffer.length - this.size;
            int n = Math.min(len - written, available);
            int first = Math.min(n, this.buffer.length - this.writePos);
            System.arraycopy(b, off + written, this.buffer, this.writePos, first);
            int second = n - first;
            if (second > 0) {
               System.arraycopy(b, off + written + first, this.buffer, 0, second);
            }

            this.writePos = (this.writePos + n) % this.buffer.length;
            this.size += n;
            written += n;
            this.notifyAll();
         }
      } else {
         throw new IndexOutOfBoundsException();
      }
   }

   public synchronized void closeWriter() {
      this.writerClosed = true;
      this.notifyAll();
   }

   @Override
   public synchronized void close() {
      this.readerClosed = true;
      this.notifyAll();
   }

   private void grow() {
      int newCapacity = Math.min(this.buffer.length * 2, this.maxCapacity);
      if (newCapacity > this.buffer.length) {
         byte[] newBuffer = new byte[newCapacity];
         int first = Math.min(this.size, this.buffer.length - this.readPos);
         System.arraycopy(this.buffer, this.readPos, newBuffer, 0, first);
         if (this.size > first) {
            System.arraycopy(this.buffer, 0, newBuffer, first, this.size - first);
         }

         this.buffer = newBuffer;
         this.readPos = 0;
         this.writePos = this.size;
      }
   }

   private void waitForPipe() throws IOException {
      try {
         this.wait();
      } catch (InterruptedException var2) {
         Thread.currentThread().interrupt();
         throw new IOException("audio pipe interrupted", var2);
      }
   }
}
