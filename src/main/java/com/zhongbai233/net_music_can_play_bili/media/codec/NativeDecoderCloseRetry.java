package com.zhongbai233.net_music_can_play_bili.media.codec;

final class NativeDecoderCloseRetry {
   private NativeDecoderCloseRetry() {
   }

   static Throwable close(
      NativeDecoderCloseRetry.CloseOperation operation,
      int maxAttempts,
      long initialBackoffMillis,
      NativeDecoderCloseRetry.RetrySleeper sleeper,
      NativeDecoderCloseRetry.CloseRetryListener retryListener
   ) {
      if (maxAttempts < 1) {
         throw new IllegalArgumentException("maxAttempts must be positive");
      } else {
         long delayMillis = Math.max(0L, initialBackoffMillis);
         Throwable previousFailure = null;

         for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
               operation.close();
               return null;
            } catch (Throwable var14) {
               Throwable failure = var14;
               if (previousFailure != null && previousFailure != var14) {
                  var14.addSuppressed(previousFailure);
               }

               previousFailure = var14;
               if (attempt >= maxAttempts) {
                  return var14;
               }

               try {
                  retryListener.onRetry(attempt, delayMillis, failure);
               } catch (Throwable var13) {
                  var14.addSuppressed(var13);
               }

               try {
                  sleeper.sleep(delayMillis);
               } catch (InterruptedException var12) {
                  Thread.currentThread().interrupt();
                  var12.addSuppressed(var14);
                  return var12;
               }

               delayMillis = delayMillis > 4611686018427387903L ? Long.MAX_VALUE : delayMillis * 2L;
            }
         }

         throw new AssertionError("unreachable bounded close state");
      }
   }

   @FunctionalInterface
   interface CloseOperation {
      void close() throws Throwable;
   }

   @FunctionalInterface
   interface CloseRetryListener {
      void onRetry(int var1, long var2, Throwable var4);
   }

   @FunctionalInterface
   interface RetrySleeper {
      void sleep(long var1) throws InterruptedException;
   }
}
