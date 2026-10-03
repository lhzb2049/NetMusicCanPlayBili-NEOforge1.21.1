package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

public final class MediaCloseExecutor {
   private MediaCloseExecutor() {
   }

   public static CompletableFuture<Void> closeAsync(AutoCloseable resource, String description) {
      return AsyncCloseExecutor.closeAsync(resource, description, message -> logger().warn(message));
   }

   public static CompletableFuture<Void> closeAsyncStrict(AutoCloseable resource, String description) {
      return AsyncCloseExecutor.closeAsyncStrict(resource, description, message -> logger().warn(message));
   }

   public static CompletableFuture<Void> closeAsyncIsolatedStrict(AutoCloseable resource, String description) {
      return AsyncCloseExecutor.closeAsyncIsolatedStrict(resource, description, message -> logger().warn(message));
   }

   private static Logger logger() {
      return MediaCloseExecutor.LoggerHolder.INSTANCE;
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }
}
