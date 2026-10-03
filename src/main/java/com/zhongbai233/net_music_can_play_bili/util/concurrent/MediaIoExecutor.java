package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class MediaIoExecutor {
   private static final int CORE_THREADS = 2;
   private static final int MAX_THREADS = Math.clamp((long)(Runtime.getRuntime().availableProcessors() / 2), 2, 4);
   private static final int QUEUE_CAPACITY = 64;
   private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
      2, MAX_THREADS, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), NetMusicThreadFactory.daemon("media-io")
   );

   private MediaIoExecutor() {
   }

   public static <T> CompletableFuture<T> supply(Supplier<T> task) {
      Objects.requireNonNull(task, "task");

      try {
         return CompletableFuture.supplyAsync(task, EXECUTOR);
      } catch (RejectedExecutionException var2) {
         return CompletableFuture.failedFuture(var2);
      }
   }

   static {
      EXECUTOR.allowCoreThreadTimeOut(true);
   }
}
