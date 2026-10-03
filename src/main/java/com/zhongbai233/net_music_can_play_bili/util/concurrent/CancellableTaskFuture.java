package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class CancellableTaskFuture<T> extends CompletableFuture<T> {
   private final AtomicReference<Future<?>> worker = new AtomicReference<>();
   private final AtomicBoolean cancellationSignalled = new AtomicBoolean();
   private final Runnable cancellationAction;

   private CancellableTaskFuture(Runnable cancellationAction) {
      this.cancellationAction = Objects.requireNonNull(cancellationAction, "cancellationAction");
   }

   public static <T> CancellableTaskFuture<T> submit(ExecutorService executor, Supplier<T> supplier) {
      return submit(executor, supplier, () -> {});
   }

   public static <T> CancellableTaskFuture<T> submit(ExecutorService executor, Supplier<T> supplier, Runnable cancellationAction) {
      Objects.requireNonNull(executor, "executor");
      Objects.requireNonNull(supplier, "supplier");
      CancellableTaskFuture<T> completion = new CancellableTaskFuture<>(cancellationAction);

      try {
         Future<?> submitted = executor.submit(() -> {
            if (!completion.isCancelled()) {
               try {
                  completion.complete(supplier.get());
               } catch (Throwable var3x) {
                  completion.completeExceptionally(var3x);
               }
            }
         });
         completion.bindWorker(submitted);
      } catch (Throwable var5) {
         completion.completeExceptionally(var5);
      }

      return completion;
   }

   @Override
   public boolean cancel(boolean mayInterruptIfRunning) {
      boolean cancelled = super.cancel(mayInterruptIfRunning);
      if (cancelled || this.isCancelled()) {
         this.signalCancellation();
      }

      this.interruptWorker();
      return cancelled;
   }

   public void cancelWorker() {
      this.signalCancellation();
      this.interruptWorker();
   }

   private void signalCancellation() {
      if (this.cancellationSignalled.compareAndSet(false, true)) {
         try {
            this.cancellationAction.run();
         } catch (Throwable var2) {
         }
      }
   }

   private void interruptWorker() {
      Future<?> submitted = this.worker.get();
      if (submitted != null) {
         submitted.cancel(true);
      }
   }

   private void bindWorker(Future<?> submitted) {
      if (!this.worker.compareAndSet(null, submitted)) {
         submitted.cancel(true);
      } else {
         if (this.isCancelled()) {
            submitted.cancel(true);
         }
      }
   }
}
