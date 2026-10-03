package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

final class VideoCandidateClosePolicy {
   private VideoCandidateClosePolicy() {
   }

   static VideoCandidateClosePolicy.Decision decide(boolean closeReturned, boolean nativeTerminated, long elapsedNanos, long timeoutMillis) {
      if (closeReturned && nativeTerminated) {
         return VideoCandidateClosePolicy.Decision.OPEN_NEXT;
      } else {
         long safeTimeoutMillis = Math.max(1L, timeoutMillis);
         return Math.max(0L, elapsedNanos) >= TimeUnit.MILLISECONDS.toNanos(safeTimeoutMillis)
            ? VideoCandidateClosePolicy.Decision.FAIL_CLOSED
            : VideoCandidateClosePolicy.Decision.WAIT;
      }
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

   static enum Decision {
      WAIT,
      OPEN_NEXT,
      FAIL_CLOSED;
   }
}
