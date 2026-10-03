package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TempFileByteSpool implements Closeable {
   private static final long READ_WAIT_TIMEOUT_MILLIS = NcpbSystemProperties.longValue(
      "ncpb.media.spool.read_wait_timeout_ms", "bili.media.spool.read_wait_timeout_ms", 30000L
   );
   private final Path path;
   private final RandomAccessFile readFile;
   private final RandomAccessFile writeFile;
   private long cachedLength;
   private boolean complete;
   private boolean closed;
   private IOException failure;

   public TempFileByteSpool(String prefix) throws IOException {
      this.path = Files.createTempFile(prefix, ".spool");
      this.readFile = new RandomAccessFile(this.path.toFile(), "r");
      this.writeFile = new RandomAccessFile(this.path.toFile(), "rw");
   }

   public void write(byte[] buffer, int offset, int length) throws IOException {
      if (length > 0) {
         long pos;
         synchronized (this) {
            this.ensureOpen();
            pos = this.cachedLength;
         }

         this.writeFile.seek(pos);
         this.writeFile.write(buffer, offset, length);
         synchronized (this) {
            this.cachedLength = pos + length;
            this.notifyAll();
         }
      }
   }

   public int read(long position, byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
         return 0;
      } else {
         int available;
         synchronized (this) {
            while (!this.closed && this.failure == null && position >= this.cachedLength && !this.complete) {
               this.waitForData();
            }

            if (this.failure != null) {
               throw this.failure;
            }

            if (this.closed) {
               return -1;
            }

            if (position >= this.cachedLength) {
               return -1;
            }

            available = (int)Math.min((long)length, this.cachedLength - position);
         }

         this.readFile.seek(position);
         int total = 0;

         while (total < available) {
            int n = this.readFile.read(buffer, offset + total, available - total);
            if (n < 0) {
               break;
            }

            total += n;
         }

         if (total == 0 && !this.complete) {
            throw new EOFException("temp spool ended before cached length");
         } else {
            return total > 0 ? total : -1;
         }
      }
   }

   public synchronized long cachedLength() {
      return this.cachedLength;
   }

   public synchronized long waitUntilCached(long targetLength, long timeoutMillis) throws IOException {
      long target = Math.max(0L, targetLength);
      long deadline = timeoutMillis > 0L ? System.currentTimeMillis() + timeoutMillis : 0L;

      while (!this.closed && this.failure == null && !this.complete && this.cachedLength < target) {
         try {
            if (timeoutMillis <= 0L) {
               this.wait();
            } else {
               long remaining = deadline - System.currentTimeMillis();
               if (remaining <= 0L) {
                  break;
               }

               this.wait(remaining);
            }
         } catch (InterruptedException var11) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for prebuffer", var11);
         }
      }

      if (this.failure != null) {
         throw this.failure;
      } else {
         return this.cachedLength;
      }
   }

   public synchronized boolean isComplete() {
      return this.complete;
   }

   public synchronized void complete() {
      this.complete = true;
      this.notifyAll();
   }

   public synchronized void fail(IOException exception) {
      if (this.failure == null) {
         this.failure = exception;
      }

      this.complete = true;
      this.notifyAll();
   }

   public Path path() {
      return this.path;
   }

   @Override
   public synchronized void close() throws IOException {
      if (!this.closed) {
         this.closed = true;
         this.complete = true;
         this.notifyAll();
         IOException closeError = null;

         try {
            this.readFile.close();
         } catch (IOException var3) {
            closeError = var3;
         }

         try {
            this.writeFile.close();
         } catch (IOException var5) {
            if (closeError != null) {
               closeError.addSuppressed(var5);
            } else {
               closeError = var5;
            }
         }

         try {
            Files.deleteIfExists(this.path);
         } catch (IOException var4) {
            if (closeError != null) {
               closeError.addSuppressed(var4);
            } else {
               closeError = var4;
            }
         }

         if (closeError != null) {
            throw closeError;
         }
      }
   }

   public static void cleanupOrphanedSpoolFiles() {
      try {
         Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
         if (!Files.isDirectory(tmpDir)) {
            return;
         }

         try (DirectoryStream<Path> files = Files.newDirectoryStream(tmpDir, "http-prefetch-*.spool")) {
            for (Path file : files) {
               try {
                  Files.deleteIfExists(file);
               } catch (IOException var6) {
               }
            }
         }
      } catch (IOException var8) {
      }
   }

   private void ensureOpen() throws IOException {
      if (this.closed) {
         throw new IOException("temp spool is closed");
      } else if (this.failure != null) {
         throw this.failure;
      }
   }

   private void waitForData() throws IOException {
      try {
         if (READ_WAIT_TIMEOUT_MILLIS <= 0L) {
            this.wait();
         } else {
            long before = this.cachedLength;
            this.wait(READ_WAIT_TIMEOUT_MILLIS);
            if (!this.closed && this.failure == null && !this.complete && this.cachedLength == before) {
               throw new IOException("timed out waiting for temp spool data after " + READ_WAIT_TIMEOUT_MILLIS + "ms");
            }
         }
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
         throw new IOException("interrupted while waiting for temp spool", var3);
      }
   }
}
