package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

final class VideoPhysicalCloseHandoff {
   private final Object lock = new Object();
   private final Set<CompletableFuture<Void>> closeSignals = Collections.newSetFromMap(new IdentityHashMap<>());
   private final Set<CompletableFuture<Void>> nativeSignals = Collections.newSetFromMap(new IdentityHashMap<>());
   private final Set<CompletableFuture<Void>> decodeSignals = Collections.newSetFromMap(new IdentityHashMap<>());
   private final CompletableFuture<Void> closeReturned = new CompletableFuture<>();
   private final CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
   private final CompletableFuture<Void> decodeExit = new CompletableFuture<>();
   private final CompletableFuture<Void> renderRelease = new CompletableFuture<>();
   private final ProjectionReplacementGate.CloseHandoff stableSnapshot = new ProjectionReplacementGate.CloseHandoff(
      this.closeReturned, this.nativeTermination, this.decodeExit, this.renderRelease
   );
   private boolean sealRequested;
   private boolean registrationsClosed;
   private CompletableFuture<Void> sealedRenderSignal;

   void beginDecode(CompletableFuture<Void> exit) {
      CompletableFuture<Void> signal = Objects.requireNonNull(exit, "exit");
      boolean added;
      synchronized (this.lock) {
         if (this.sealRequested) {
            throw new IllegalStateException("cannot begin a decode generation after seal");
         }

         this.ensureRegistrationOpen();
         added = this.decodeSignals.add(signal);
      }

      if (added) {
         this.watchDecode(signal);
      }
   }

   void attachDecoder(CompletableFuture<Void> termination) {
      CompletableFuture<Void> signal = Objects.requireNonNull(termination, "termination");
      boolean added;
      synchronized (this.lock) {
         this.ensureRegistrationOpen();
         added = this.nativeSignals.add(signal);
      }

      if (added) {
         watchFailure(signal, this.nativeTermination);
      }
   }

   void attachClose(CompletableFuture<Void> close, CompletableFuture<Void> termination, CompletableFuture<Void> exit) {
      CompletableFuture<Void> closeSignal = Objects.requireNonNull(close, "close");
      CompletableFuture<Void> nativeSignal = Objects.requireNonNull(termination, "termination");
      CompletableFuture<Void> exitSignal = Objects.requireNonNull(exit, "exit");
      boolean closeAdded;
      boolean nativeAdded;
      boolean decodeAdded;
      synchronized (this.lock) {
         this.ensureRegistrationOpen();
         if (this.sealRequested && !this.decodeSignals.contains(exitSignal)) {
            throw new IllegalStateException("decode exit was not registered before seal");
         }

         closeAdded = this.closeSignals.add(closeSignal);
         nativeAdded = this.nativeSignals.add(nativeSignal);
         decodeAdded = this.decodeSignals.add(exitSignal);
      }

      if (closeAdded) {
         watchFailure(closeSignal, this.closeReturned);
      }

      if (nativeAdded) {
         watchFailure(nativeSignal, this.nativeTermination);
      }

      if (decodeAdded) {
         this.watchDecode(exitSignal);
      }
   }

   void seal(CompletableFuture<Void> release) {
      CompletableFuture<Void> signal = Objects.requireNonNull(release, "release");
      synchronized (this.lock) {
         if (this.sealRequested) {
            if (this.sealedRenderSignal != signal) {
               throw new IllegalStateException("handoff was already sealed with another render signal");
            }

            return;
         }

         this.sealRequested = true;
         this.sealedRenderSignal = signal;
      }

      bridge(signal, this.renderRelease);
      this.tryCloseRegistration();
   }

   ProjectionReplacementGate.CloseHandoff snapshot() {
      return this.stableSnapshot;
   }

   private void watchDecode(CompletableFuture<Void> signal) {
      signal.whenComplete((ignored, error) -> {
         if (error != null) {
            this.decodeExit.completeExceptionally(error);
         }

         this.tryCloseRegistration();
      });
   }

   private void tryCloseRegistration() {
      List<CompletableFuture<Void>> closes;
      List<CompletableFuture<Void>> natives;
      List<CompletableFuture<Void>> exits;
      synchronized (this.lock) {
         if (!this.sealRequested || this.registrationsClosed || !allDone(this.decodeSignals)) {
            return;
         }

         this.registrationsClosed = true;
         closes = new ArrayList<>(this.closeSignals);
         natives = new ArrayList<>(this.nativeSignals);
         exits = new ArrayList<>(this.decodeSignals);
      }

      bridge(allNormally(closes), this.closeReturned);
      bridge(allNormally(natives), this.nativeTermination);
      bridge(allNormally(exits), this.decodeExit);
   }

   private void ensureRegistrationOpen() {
      if (this.registrationsClosed) {
         throw new IllegalStateException("physical-close registration is closed");
      }
   }

   private static boolean allDone(Set<CompletableFuture<Void>> signals) {
      for (CompletableFuture<Void> signal : signals) {
         if (!signal.isDone()) {
            return false;
         }
      }

      return true;
   }

   private static CompletableFuture<Void> allNormally(List<CompletableFuture<Void>> signals) {
      if (signals.isEmpty()) {
         return CompletableFuture.completedFuture(null);
      } else {
         CompletableFuture<Void> result = new CompletableFuture<>();
         AtomicInteger remaining = new AtomicInteger(signals.size());

         for (CompletableFuture<Void> signal : signals) {
            signal.whenComplete((ignored, error) -> {
               if (error != null) {
                  result.completeExceptionally(error);
               } else if (remaining.decrementAndGet() == 0) {
                  result.complete(null);
               }
            });
         }

         return result;
      }
   }

   private static void watchFailure(CompletableFuture<Void> source, CompletableFuture<Void> target) {
      source.whenComplete((ignored, error) -> {
         if (error != null) {
            target.completeExceptionally(error);
         }
      });
   }

   private static void bridge(CompletableFuture<Void> source, CompletableFuture<Void> target) {
      source.whenComplete((ignored, error) -> {
         if (error == null) {
            target.complete(null);
         } else {
            target.completeExceptionally(error);
         }
      });
   }
}
