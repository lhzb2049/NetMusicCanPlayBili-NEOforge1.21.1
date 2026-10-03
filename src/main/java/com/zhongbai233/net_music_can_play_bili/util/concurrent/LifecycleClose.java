package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.io.Closeable;
import java.io.IOException;

public final class LifecycleClose {
   private static final long DEFAULT_JOIN_TIMEOUT_MILLIS = 2000L;

   private LifecycleClose() {
   }

   public static void interruptAndJoin(Thread thread) {
      interruptAndJoin(thread, 2000L);
   }

   public static void interruptAndJoin(Thread thread, long timeoutMillis) {
      if (thread != null && thread != Thread.currentThread()) {
         thread.interrupt();
         join(thread, timeoutMillis);
      }
   }

   public static void join(Thread thread, long timeoutMillis) {
      if (thread != null && thread != Thread.currentThread() && thread.isAlive()) {
         try {
            thread.join(Math.max(0L, timeoutMillis));
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public static void closeQuietly(Closeable closeable) {
      if (closeable != null) {
         try {
            closeable.close();
         } catch (IOException var2) {
         }
      }
   }
}
