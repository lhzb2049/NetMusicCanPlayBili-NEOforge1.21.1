package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class ProjectionReplacementGate<K> {
   private final Map<K, ProjectionReplacementGate.DomainSlot> ownerSlots = new HashMap<>();
   private final Map<String, ProjectionReplacementGate.DomainSlot> sessionSlots = new HashMap<>();
   private final ProjectionReplacementGate.TimeoutScheduler timeoutScheduler;
   private final Object gateIdentity = new Object();

   ProjectionReplacementGate() {
      this((task, delay, unit) -> CompletableFuture.delayedExecutor(delay, unit).execute(task));
   }

   ProjectionReplacementGate(ProjectionReplacementGate.TimeoutScheduler timeoutScheduler) {
      this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
   }

   ProjectionReplacementGate.Intent<K> beginIntent(K ownerKey, String desiredSessionId, ProjectionReplacementGate.CloseHandoff proposedHandoff) {
      Objects.requireNonNull(ownerKey, "ownerKey");
      Objects.requireNonNull(proposedHandoff, "proposedHandoff");
      String sessionId = normalizeSession(desiredSessionId);
      List<ProjectionReplacementGate.Completion> superseded = new ArrayList<>(2);
      ProjectionReplacementGate.Intent<K> intent;
      synchronized (this) {
         ProjectionReplacementGate.DomainSlot ownerSlot = this.ownerSlots.computeIfAbsent(ownerKey, ignored -> new ProjectionReplacementGate.DomainSlot());
         ProjectionReplacementGate.DomainSlot sessionSlot = this.sessionSlot(sessionId, true);
         long nextOwnerEpoch = nextEpoch(ownerSlot.epoch);
         long nextSessionEpoch = sessionSlot != null ? nextEpoch(sessionSlot.epoch) : 0L;
         this.invalidateDistinct(superseded, ownerSlot.current, sessionSlot != null ? sessionSlot.current : null);
         ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, proposedHandoff);
         if (sessionSlot != null) {
            sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, proposedHandoff);
         }

         ProjectionReplacementGate.CloseHandoff barrier = ownerSlot.retainedHandoff;
         if (sessionSlot != null) {
            barrier = stableHandoff(barrier, sessionSlot.retainedHandoff);
         }

         ownerSlot.epoch = nextOwnerEpoch;
         if (sessionSlot != null) {
            sessionSlot.epoch = nextSessionEpoch;
         }

         ProjectionReplacementGate.IntentState state = new ProjectionReplacementGate.IntentState(
            ownerKey, sessionId, ownerSlot.epoch, nextSessionEpoch, barrier
         );
         ownerSlot.current = state;
         if (sessionSlot != null) {
            sessionSlot.current = state;
         }

         intent = new ProjectionReplacementGate.Intent<>(ownerKey, sessionId, state.ownerEpoch, state.sessionEpoch, barrier, state, this.gateIdentity);
      }

      completeAll(superseded);
      return intent;
   }

   void retainCloseHandoff(K ownerKey, ProjectionReplacementGate.CloseHandoff closeHandoff) {
      this.retainCloseHandoff(ownerKey, "", closeHandoff);
   }

   void retainCloseHandoff(K ownerKey, String sessionId, ProjectionReplacementGate.CloseHandoff closeHandoff) {
      Objects.requireNonNull(ownerKey, "ownerKey");
      Objects.requireNonNull(closeHandoff, "closeHandoff");
      String normalizedSession = normalizeSession(sessionId);
      List<ProjectionReplacementGate.Completion> superseded = new ArrayList<>(2);
      synchronized (this) {
         ProjectionReplacementGate.DomainSlot ownerSlot = this.ownerSlots.computeIfAbsent(ownerKey, ignored -> new ProjectionReplacementGate.DomainSlot());
         ProjectionReplacementGate.DomainSlot sessionSlot = this.sessionSlot(normalizedSession, true);
         long nextOwnerEpoch = nextEpoch(ownerSlot.epoch);
         long nextSessionEpoch = sessionSlot != null ? nextEpoch(sessionSlot.epoch) : 0L;
         this.invalidateDistinct(superseded, ownerSlot.current, sessionSlot != null ? sessionSlot.current : null);
         ownerSlot.epoch = nextOwnerEpoch;
         ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, closeHandoff);
         ownerSlot.current = null;
         if (sessionSlot != null) {
            sessionSlot.epoch = nextSessionEpoch;
            sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, closeHandoff);
            sessionSlot.current = null;
         }
      }

      completeAll(superseded);
   }

   synchronized void retainCommitted(ProjectionReplacementGate.Intent<K> intent, ProjectionReplacementGate.CloseHandoff closeHandoff) {
      Objects.requireNonNull(closeHandoff, "closeHandoff");
      ProjectionReplacementGate.IntentState state = this.matchingState(intent);
      if (state != null && state.committed) {
         ProjectionReplacementGate.DomainSlot ownerSlot = this.ownerSlots.get(intent.ownerKey());
         ownerSlot.retainedHandoff = stableHandoff(ownerSlot.retainedHandoff, closeHandoff);
         ownerSlot.current = null;
         ProjectionReplacementGate.DomainSlot sessionSlot = this.sessionSlot(intent.desiredSessionId(), false);
         if (sessionSlot != null) {
            sessionSlot.retainedHandoff = stableHandoff(sessionSlot.retainedHandoff, closeHandoff);
            sessionSlot.current = null;
         }
      } else {
         throw new IllegalStateException("projection replacement intent is not the active commit");
      }
   }

   void cancelIntent(ProjectionReplacementGate.Intent<K> intent) {
      ProjectionReplacementGate.Completion completion;
      synchronized (this) {
         ProjectionReplacementGate.IntentState state = this.matchingState(intent);
         if (state == null) {
            return;
         }

         state.terminalDecision = ProjectionReplacementGate.Decision.FAIL_CLOSED;
         this.detachState(state);
         completion = claimWaiter(state, ProjectionReplacementGate.Decision.FAIL_CLOSED);
      }

      complete(completion);
   }

   synchronized ProjectionReplacementGate.Decision evaluate(ProjectionReplacementGate.Intent<K> intent) {
      ProjectionReplacementGate.IntentState state = this.matchingState(intent);
      return state != null ? this.evaluate(state) : ProjectionReplacementGate.Decision.FAIL_CLOSED;
   }

   synchronized boolean isCurrent(ProjectionReplacementGate.Intent<K> intent) {
      ProjectionReplacementGate.IntentState state = this.matchingState(intent);
      return state != null && !state.committed;
   }

   CompletableFuture<ProjectionReplacementGate.Decision> waitFor(ProjectionReplacementGate.Intent<K> intent, long timeout, TimeUnit unit) {
      Objects.requireNonNull(intent, "intent");
      Objects.requireNonNull(unit, "unit");
      if (timeout < 0L) {
         throw new IllegalArgumentException("timeout must be >= 0");
      } else {
         ProjectionReplacementGate.IntentState state;
         ProjectionReplacementGate.Waiter waiter;
         synchronized (this) {
            state = this.matchingState(intent);
            if (state == null) {
               return CompletableFuture.completedFuture(ProjectionReplacementGate.Decision.FAIL_CLOSED);
            }

            ProjectionReplacementGate.Decision decision = this.evaluate(state);
            if (decision != ProjectionReplacementGate.Decision.WAIT) {
               return CompletableFuture.completedFuture(decision);
            }

            if (state.waiter != null) {
               return state.waiter.outcome;
            }

            waiter = new ProjectionReplacementGate.Waiter();
            state.waiter = waiter;
         }

         try {
            this.timeoutScheduler.schedule(() -> this.resolveTimeout(intent, state, waiter), timeout, unit);
         } catch (RuntimeException var10) {
            this.resolveTimeout(intent, state, waiter);
         }

         for (CompletableFuture<Void> signal : state.closeHandoff.signals()) {
            signal.whenComplete((ignored, error) -> this.resolveSignals(intent, state, waiter));
         }

         this.resolveSignals(intent, state, waiter);
         return waiter.outcome;
      }
   }

   synchronized boolean commitIfOpen(ProjectionReplacementGate.Intent<K> intent, Runnable action) {
      Objects.requireNonNull(action, "action");
      ProjectionReplacementGate.IntentState state = this.matchingState(intent);
      if (state != null && !state.committed && this.evaluate(state) == ProjectionReplacementGate.Decision.OPEN) {
         state.committed = true;

         try {
            action.run();
            return true;
         } catch (Error | RuntimeException var5) {
            state.terminalDecision = ProjectionReplacementGate.Decision.FAIL_CLOSED;
            this.detachState(state);
            throw var5;
         }
      } else {
         return false;
      }
   }

   private void resolveSignals(
      ProjectionReplacementGate.Intent<K> intent, ProjectionReplacementGate.IntentState expectedState, ProjectionReplacementGate.Waiter expectedWaiter
   ) {
      ProjectionReplacementGate.Completion completion = null;
      synchronized (this) {
         ProjectionReplacementGate.IntentState state = this.matchingState(intent);
         if (state != expectedState || state.waiter != expectedWaiter || expectedWaiter.claimed) {
            return;
         }

         ProjectionReplacementGate.Decision decision = physicalDecision(state.closeHandoff);
         if (decision != ProjectionReplacementGate.Decision.WAIT) {
            state.terminalDecision = decision;
            completion = claimWaiter(state, decision);
         }
      }

      complete(completion);
   }

   private void resolveTimeout(
      ProjectionReplacementGate.Intent<K> intent, ProjectionReplacementGate.IntentState expectedState, ProjectionReplacementGate.Waiter expectedWaiter
   ) {
      ProjectionReplacementGate.Completion completion = null;
      synchronized (this) {
         ProjectionReplacementGate.IntentState state = this.matchingState(intent);
         if (state != expectedState || state.waiter != expectedWaiter || expectedWaiter.claimed) {
            return;
         }

         state.terminalDecision = ProjectionReplacementGate.Decision.FAIL_CLOSED;
         completion = claimWaiter(state, ProjectionReplacementGate.Decision.FAIL_CLOSED);
      }

      complete(completion);
   }

   private ProjectionReplacementGate.IntentState matchingState(ProjectionReplacementGate.Intent<K> intent) {
      if (intent != null && intent.gateIdentity() == this.gateIdentity) {
         ProjectionReplacementGate.DomainSlot ownerSlot = this.ownerSlots.get(intent.ownerKey());
         if (ownerSlot != null && ownerSlot.current != null && ownerSlot.current == intent.stateIdentity()) {
            ProjectionReplacementGate.IntentState state = ownerSlot.current;
            if (ownerSlot.epoch == intent.epoch()
               && state.ownerEpoch == intent.epoch()
               && state.closeHandoff == intent.closeHandoff()
               && state.sessionId.equals(intent.desiredSessionId())) {
               ProjectionReplacementGate.DomainSlot sessionSlot = this.sessionSlot(intent.desiredSessionId(), false);
               return sessionSlot == null
                     || sessionSlot.current == state && sessionSlot.epoch == intent.sessionEpoch() && state.sessionEpoch == intent.sessionEpoch()
                  ? state
                  : null;
            } else {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private void invalidateDistinct(
      List<ProjectionReplacementGate.Completion> completions, ProjectionReplacementGate.IntentState first, ProjectionReplacementGate.IntentState second
   ) {
      IdentityHashMap<ProjectionReplacementGate.IntentState, Boolean> seen = new IdentityHashMap<>();
      if (first != null) {
         seen.put(first, Boolean.TRUE);
         this.invalidate(first, completions);
      }

      if (second != null && !seen.containsKey(second)) {
         this.invalidate(second, completions);
      }
   }

   private void invalidate(ProjectionReplacementGate.IntentState state, List<ProjectionReplacementGate.Completion> completions) {
      state.terminalDecision = ProjectionReplacementGate.Decision.FAIL_CLOSED;
      this.detachState(state);
      ProjectionReplacementGate.Completion completion = claimWaiter(state, ProjectionReplacementGate.Decision.FAIL_CLOSED);
      if (completion != null) {
         completions.add(completion);
      }
   }

   private void detachState(ProjectionReplacementGate.IntentState state) {
      K ownerKey = (K)state.ownerKey;
      ProjectionReplacementGate.DomainSlot ownerSlot = this.ownerSlots.get(ownerKey);
      if (ownerSlot != null && ownerSlot.current == state) {
         ownerSlot.current = null;
      }

      ProjectionReplacementGate.DomainSlot sessionSlot = this.sessionSlot(state.sessionId, false);
      if (sessionSlot != null && sessionSlot.current == state) {
         sessionSlot.current = null;
      }
   }

   private ProjectionReplacementGate.DomainSlot sessionSlot(String sessionId, boolean create) {
      if (sessionId != null && !sessionId.isBlank()) {
         return create ? this.sessionSlots.computeIfAbsent(sessionId, ignored -> new ProjectionReplacementGate.DomainSlot()) : this.sessionSlots.get(sessionId);
      } else {
         return null;
      }
   }

   private ProjectionReplacementGate.Decision evaluate(ProjectionReplacementGate.IntentState state) {
      if (state.terminalDecision != null) {
         return state.terminalDecision;
      } else {
         ProjectionReplacementGate.Decision decision = physicalDecision(state.closeHandoff);
         if (decision != ProjectionReplacementGate.Decision.WAIT) {
            state.terminalDecision = decision;
         }

         return decision;
      }
   }

   private static ProjectionReplacementGate.Decision physicalDecision(ProjectionReplacementGate.CloseHandoff handoff) {
      boolean pending = false;

      for (CompletableFuture<Void> signal : handoff.signals()) {
         if (signal.isDone()) {
            if (signal.isCancelled() || signal.isCompletedExceptionally()) {
               return ProjectionReplacementGate.Decision.FAIL_CLOSED;
            }

            try {
               signal.join();
            } catch (RuntimeException var5) {
               return ProjectionReplacementGate.Decision.FAIL_CLOSED;
            }
         } else {
            pending = true;
         }
      }

      return pending ? ProjectionReplacementGate.Decision.WAIT : ProjectionReplacementGate.Decision.OPEN;
   }

   private static ProjectionReplacementGate.CloseHandoff stableHandoff(
      ProjectionReplacementGate.CloseHandoff retained, ProjectionReplacementGate.CloseHandoff proposed
   ) {
      if (retained == null || physicalDecision(retained) == ProjectionReplacementGate.Decision.OPEN) {
         return proposed;
      } else {
         return retained != proposed && physicalDecision(proposed) != ProjectionReplacementGate.Decision.OPEN
            ? new ProjectionReplacementGate.CloseHandoff(
               requireBoth(retained.closeReturned(), proposed.closeReturned()),
               requireBoth(retained.nativeTermination(), proposed.nativeTermination()),
               requireBoth(retained.decodeExit(), proposed.decodeExit()),
               requireBoth(retained.renderRelease(), proposed.renderRelease())
            )
            : retained;
      }
   }

   private static CompletableFuture<Void> requireBoth(CompletableFuture<Void> first, CompletableFuture<Void> second) {
      if (first == second) {
         return first;
      } else {
         CompletableFuture<Void> result = new CompletableFuture<>();
         AtomicInteger remaining = new AtomicInteger(2);
         BiConsumer<Void, Throwable> completion = (ignored, error) -> {
            if (error != null) {
               result.completeExceptionally(error);
            } else if (remaining.decrementAndGet() == 0) {
               result.complete(null);
            }
         };
         first.whenComplete(completion);
         second.whenComplete(completion);
         return result;
      }
   }

   private static ProjectionReplacementGate.Completion claimWaiter(ProjectionReplacementGate.IntentState state, ProjectionReplacementGate.Decision decision) {
      ProjectionReplacementGate.Waiter waiter = state.waiter;
      if (waiter != null && !waiter.claimed) {
         waiter.claimed = true;
         state.waiter = null;
         return new ProjectionReplacementGate.Completion(waiter.outcome, decision);
      } else {
         return null;
      }
   }

   private static void completeAll(List<ProjectionReplacementGate.Completion> completions) {
      for (ProjectionReplacementGate.Completion completion : completions) {
         complete(completion);
      }
   }

   private static void complete(ProjectionReplacementGate.Completion completion) {
      if (completion != null) {
         completion.outcome.complete(completion.decision);
      }
   }

   private static long nextEpoch(long current) {
      if (current == Long.MAX_VALUE) {
         throw new IllegalStateException("projection replacement intent epoch exhausted");
      } else {
         return current + 1L;
      }
   }

   private static String normalizeSession(String desiredSessionId) {
      return desiredSessionId != null ? desiredSessionId : "";
   }

   record CloseHandoff(
      CompletableFuture<Void> closeReturned,
      CompletableFuture<Void> nativeTermination,
      CompletableFuture<Void> decodeExit,
      CompletableFuture<Void> renderRelease
   ) {
      CloseHandoff(
         CompletableFuture<Void> closeReturned,
         CompletableFuture<Void> nativeTermination,
         CompletableFuture<Void> decodeExit,
         CompletableFuture<Void> renderRelease
      ) {
         Objects.requireNonNull(closeReturned, "closeReturned");
         Objects.requireNonNull(nativeTermination, "nativeTermination");
         Objects.requireNonNull(decodeExit, "decodeExit");
         Objects.requireNonNull(renderRelease, "renderRelease");
         this.closeReturned = closeReturned;
         this.nativeTermination = nativeTermination;
         this.decodeExit = decodeExit;
         this.renderRelease = renderRelease;
      }

      static ProjectionReplacementGate.CloseHandoff completed() {
         CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);
         return new ProjectionReplacementGate.CloseHandoff(completed, completed, completed, completed);
      }

      private List<CompletableFuture<Void>> signals() {
         return List.of(this.closeReturned, this.nativeTermination, this.decodeExit, this.renderRelease);
      }
   }

   private record Completion(CompletableFuture<ProjectionReplacementGate.Decision> outcome, ProjectionReplacementGate.Decision decision) {
   }

   static enum Decision {
      OPEN,
      WAIT,
      FAIL_CLOSED;
   }

   private static final class DomainSlot {
      private long epoch;
      private ProjectionReplacementGate.CloseHandoff retainedHandoff;
      private ProjectionReplacementGate.IntentState current;
   }

   record Intent<K>(
      K ownerKey,
      String desiredSessionId,
      long epoch,
      long sessionEpoch,
      ProjectionReplacementGate.CloseHandoff closeHandoff,
      Object stateIdentity,
      Object gateIdentity
   ) {
      Intent(
         K ownerKey,
         String desiredSessionId,
         long epoch,
         long sessionEpoch,
         ProjectionReplacementGate.CloseHandoff closeHandoff,
         Object stateIdentity,
         Object gateIdentity
      ) {
         Objects.requireNonNull(ownerKey, "ownerKey");
         desiredSessionId = ProjectionReplacementGate.normalizeSession(desiredSessionId);
         Objects.requireNonNull(closeHandoff, "closeHandoff");
         Objects.requireNonNull(stateIdentity, "stateIdentity");
         Objects.requireNonNull(gateIdentity, "gateIdentity");
         this.ownerKey = ownerKey;
         this.desiredSessionId = desiredSessionId;
         this.epoch = epoch;
         this.sessionEpoch = sessionEpoch;
         this.closeHandoff = closeHandoff;
         this.stateIdentity = stateIdentity;
         this.gateIdentity = gateIdentity;
      }
   }

   private static final class IntentState {
      private final Object ownerKey;
      private final String sessionId;
      private final long ownerEpoch;
      private final long sessionEpoch;
      private final ProjectionReplacementGate.CloseHandoff closeHandoff;
      private ProjectionReplacementGate.Decision terminalDecision;
      private ProjectionReplacementGate.Waiter waiter;
      private boolean committed;

      private IntentState(Object ownerKey, String sessionId, long ownerEpoch, long sessionEpoch, ProjectionReplacementGate.CloseHandoff closeHandoff) {
         this.ownerKey = ownerKey;
         this.sessionId = sessionId;
         this.ownerEpoch = ownerEpoch;
         this.sessionEpoch = sessionEpoch;
         this.closeHandoff = closeHandoff;
      }
   }

   @FunctionalInterface
   interface TimeoutScheduler {
      void schedule(Runnable var1, long var2, TimeUnit var4);
   }

   private static final class Waiter {
      private final CompletableFuture<ProjectionReplacementGate.Decision> outcome = new CompletableFuture<>();
      private boolean claimed;
   }
}
