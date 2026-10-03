package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class HandheldDecoderAdmissionPolicy {
   private HandheldDecoderAdmissionPolicy() {
   }

   static HandheldDecoderAdmissionPolicy.Decision decide(CompletableFuture<Void> decodeExit, CompletableFuture<Void> nativeTermination) {
      if (decodeExit != null && nativeTermination != null) {
         CompletableFuture<Void> safeDecodeExit = nonNull(decodeExit);
         CompletableFuture<Void> safeNativeTermination = nonNull(nativeTermination);
         if (failed(safeDecodeExit) || failed(safeNativeTermination)) {
            return HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED;
         } else {
            return safeDecodeExit.isDone() && safeNativeTermination.isDone()
               ? HandheldDecoderAdmissionPolicy.Decision.OPEN
               : HandheldDecoderAdmissionPolicy.Decision.WAIT;
         }
      } else {
         return HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED;
      }
   }

   static CompletableFuture<Void> convergence(CompletableFuture<Void> decodeExit, CompletableFuture<Void> nativeTermination) {
      return decodeExit != null && nativeTermination != null
         ? CompletableFuture.allOf(nonNull(decodeExit), nonNull(nativeTermination))
         : CompletableFuture.failedFuture(new IllegalStateException("handheld decoder close signal is missing"));
   }

   static boolean completedNormally(CompletableFuture<Void> future) {
      if (future != null && future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) {
         try {
            future.join();
            return true;
         } catch (CancellationException | CompletionException var2) {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean failed(CompletableFuture<Void> future) {
      return future.isCancelled() || future.isCompletedExceptionally();
   }

   private static CompletableFuture<Void> nonNull(CompletableFuture<Void> future) {
      return future != null ? future : CompletableFuture.completedFuture(null);
   }

   static enum Decision {
      OPEN,
      WAIT,
      FAIL_CLOSED;
   }
}
