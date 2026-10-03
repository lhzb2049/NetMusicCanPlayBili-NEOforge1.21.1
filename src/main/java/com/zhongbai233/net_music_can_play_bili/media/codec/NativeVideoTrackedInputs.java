package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

class NativeVideoTrackedInputs {
   private final IdentityHashMap<InputStream, NativeVideoTrackedInputs.TrackedInput> aliases = new IdentityHashMap<>();
   private final List<NativeVideoTrackedInputs.TrackedInput> inputs = new ArrayList<>();
   private final NativeVideoTrackedInputs.InputCloseScheduler closeScheduler;
   private CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);
   private boolean closing;

   NativeVideoTrackedInputs() {
      this(MediaCloseExecutor::closeAsyncIsolatedStrict);
   }

   NativeVideoTrackedInputs(NativeVideoTrackedInputs.InputCloseScheduler closeScheduler) {
      this.closeScheduler = closeScheduler;
   }

   InputStream track(InputStream stream) {
      if (stream == null) {
         throw new IllegalArgumentException("stream must not be null");
      } else {
         NativeVideoTrackedInputs.TrackedInput tracked;
         boolean closeNow;
         synchronized (this) {
            tracked = this.aliases.get(stream);
            if (tracked == null) {
               tracked = this.addTracked(stream);
            }

            closeNow = this.closing;
         }

         if (closeNow) {
            tracked.closeAsync();
         }

         return tracked.exposedStream();
      }
   }

   CompletableFuture<Void> closeAsync(InputStream stream) {
      if (stream == null) {
         return CompletableFuture.completedFuture(null);
      } else {
         NativeVideoTrackedInputs.TrackedInput tracked;
         synchronized (this) {
            tracked = this.aliases.get(stream);
            if (tracked == null) {
               tracked = this.addTracked(stream);
            }
         }

         tracked.closeAsync();
         return tracked.outcome();
      }
   }

   void beginClose() {
      List<NativeVideoTrackedInputs.TrackedInput> snapshot;
      synchronized (this) {
         this.closing = true;
         snapshot = new ArrayList<>(this.inputs);
      }

      for (NativeVideoTrackedInputs.TrackedInput tracked : snapshot) {
         tracked.closeAsync();
      }
   }

   synchronized CompletableFuture<Void> completionSnapshot() {
      return this.completion;
   }

   synchronized int trackedCount() {
      return this.inputs.size();
   }

   private NativeVideoTrackedInputs.TrackedInput addTracked(InputStream stream) {
      NativeVideoTrackedInputs.TrackedInput tracked = new NativeVideoTrackedInputs.TrackedInput(stream, this.closeScheduler);
      this.inputs.add(tracked);
      this.aliases.put(stream, tracked);
      this.aliases.put(tracked.exposedStream(), tracked);
      this.completion = CompletableFuture.allOf(this.completion, tracked.outcome());
      return tracked;
   }

   @FunctionalInterface
   interface InputCloseScheduler {
      CompletableFuture<Void> closeAsync(AutoCloseable var1, String var2);
   }

   private static final class TrackedInput {
      private final InputStream stream;
      private final InputStream exposedStream;
      private final NativeVideoTrackedInputs.InputCloseScheduler closeScheduler;
      private final AtomicBoolean closeStarted = new AtomicBoolean(false);
      private final CompletableFuture<Void> outcome = new CompletableFuture<>();

      private TrackedInput(InputStream stream, NativeVideoTrackedInputs.InputCloseScheduler closeScheduler) {
         this.stream = stream;
         this.closeScheduler = closeScheduler;
         this.exposedStream = new FilterInputStream(stream) {
            @Override
            public void close() {
               TrackedInput.this.closeAsync();
            }
         };
      }

      private InputStream exposedStream() {
         return this.exposedStream;
      }

      private CompletableFuture<Void> outcome() {
         return this.outcome;
      }

      private void closeAsync() {
         if (this.closeStarted.compareAndSet(false, true)) {
            try {
               this.closeScheduler.closeAsync(this.stream, "native video tracked input").whenComplete((ignored, error) -> {
                  if (error == null) {
                     this.outcome.complete(null);
                  } else {
                     this.outcome.completeExceptionally(error);
                  }
               });
            } catch (Throwable var2) {
               this.outcome.completeExceptionally(var2);
            }
         }
      }
   }
}
