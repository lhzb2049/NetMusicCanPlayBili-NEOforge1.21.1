package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class VideoCloseDiagnostics {
   private static final int DEFAULT_ACTIVE_LIMIT = 256;
   private static final int DEFAULT_HISTORY_LIMIT = 128;
   private static final MediaCloseProperties.Timeouts TIMEOUTS = MediaCloseProperties.videoTimeouts();
   private static final VideoCloseDiagnostics GLOBAL = new VideoCloseDiagnostics(256, 128, TIMEOUTS.softTimeoutNanos(), TIMEOUTS.hardTimeoutNanos());
   private final int activeLimit;
   private final int historyLimit;
   private final long softTimeoutNanos;
   private final long hardTimeoutNanos;
   private final AtomicLong sequence = new AtomicLong();
   private final LinkedHashMap<Long, VideoCloseDiagnostics.Operation> active = new LinkedHashMap<>();
   private final ArrayDeque<VideoCloseDiagnostics.CompletedOperation> history = new ArrayDeque<>();
   private long softTimeouts;
   private long hardTimeouts;
   private long lateConvergences;
   private long failedConvergences;
   private long droppedOperations;

   public VideoCloseDiagnostics(int activeLimit, int historyLimit, long softTimeoutNanos, long hardTimeoutNanos) {
      this.activeLimit = Math.max(1, activeLimit);
      this.historyLimit = Math.max(1, historyLimit);
      this.softTimeoutNanos = Math.max(1L, softTimeoutNanos);
      this.hardTimeoutNanos = Math.max(this.softTimeoutNanos, hardTimeoutNanos);
   }

   public static VideoCloseDiagnostics global() {
      return GLOBAL;
   }

   public static List<String> tickGlobal() {
      return GLOBAL.tick(System.nanoTime());
   }

   public static String describeGlobal() {
      VideoCloseDiagnostics.Snapshot snapshot = GLOBAL.snapshot(System.nanoTime());
      return "video closes active="
         + snapshot.activeOperations()
         + " retained="
         + snapshot.retainedCompleted()
         + " oldestMs="
         + snapshot.oldestPendingNanos() / 1000000L
         + " softTimeouts="
         + snapshot.softTimeouts()
         + " hardTimeouts="
         + snapshot.hardTimeouts()
         + " late="
         + snapshot.lateConvergences()
         + " failed="
         + snapshot.failedConvergences()
         + " dropped="
         + snapshot.droppedOperations()
         + " latestMs="
         + (snapshot.latestConvergenceNanos() >= 0L ? snapshot.latestConvergenceNanos() / 1000000L : -1L);
   }

   public synchronized long begin(String sessionId, Set<VideoCloseDiagnostics.Phase> required, long nowNanos) {
      return this.begin(PlaybackSessionId.parse(sessionId), required, nowNanos);
   }

   synchronized long begin(PlaybackSessionId sessionId, Set<VideoCloseDiagnostics.Phase> required, long nowNanos) {
      return this.begin(Optional.of(sessionId), required, nowNanos);
   }

   private long begin(Optional<PlaybackSessionId> sessionId, Set<VideoCloseDiagnostics.Phase> required, long nowNanos) {
      if (this.active.size() >= this.activeLimit) {
         Long oldest = this.active.keySet().iterator().next();
         this.active.remove(oldest);
         this.droppedOperations++;
      }

      long id = this.sequence.incrementAndGet();
      EnumSet<VideoCloseDiagnostics.Phase> phases = required != null && !required.isEmpty()
         ? EnumSet.copyOf(required)
         : EnumSet.noneOf(VideoCloseDiagnostics.Phase.class);
      VideoCloseDiagnostics.Operation operation = new VideoCloseDiagnostics.Operation(id, sessionId, nowNanos, phases);
      this.active.put(id, operation);
      this.convergeIfComplete(operation, nowNanos);
      return id;
   }

   public synchronized void complete(long operationId, VideoCloseDiagnostics.Phase phase, long nowNanos) {
      this.complete(operationId, phase, null, nowNanos);
   }

   public synchronized void complete(long operationId, VideoCloseDiagnostics.Phase phase, Throwable failure, long nowNanos) {
      VideoCloseDiagnostics.Operation operation = this.active.get(operationId);
      if (operation != null && phase != null && operation.required.contains(phase)) {
         operation.completed.add(phase);
         if (failure != null) {
            operation.failures.putIfAbsent(phase, describeFailure(failure));
         }

         this.convergeIfComplete(operation, nowNanos);
      }
   }

   void observe(long operationId, VideoCloseDiagnostics.Phase phase, CompletableFuture<Void> signal) {
      if (signal != null && phase != null) {
         signal.whenComplete((ignored, failure) -> this.complete(operationId, phase, failure, System.nanoTime()));
      }
   }

   public synchronized List<String> tick(long nowNanos) {
      List<String> warnings = new ArrayList<>();

      for (VideoCloseDiagnostics.Operation operation : this.active.values()) {
         long age = elapsed(operation.requestedNanos, nowNanos);
         if (!operation.softTimedOut && age >= this.softTimeoutNanos) {
            operation.softTimedOut = true;
            this.softTimeouts++;
            warnings.add(describeTimeout(operation, age, false));
         }

         if (!operation.hardTimedOut && age >= this.hardTimeoutNanos) {
            operation.hardTimedOut = true;
            this.hardTimeouts++;
            warnings.add(describeTimeout(operation, age, true));
         }
      }

      return List.copyOf(warnings);
   }

   public synchronized VideoCloseDiagnostics.Snapshot snapshot(long nowNanos) {
      long oldestAge = 0L;

      for (VideoCloseDiagnostics.Operation operation : this.active.values()) {
         oldestAge = Math.max(oldestAge, elapsed(operation.requestedNanos, nowNanos));
      }

      VideoCloseDiagnostics.CompletedOperation latest = this.history.peekLast();
      return new VideoCloseDiagnostics.Snapshot(
         this.active.size(),
         this.history.size(),
         this.softTimeouts,
         this.hardTimeouts,
         this.lateConvergences,
         this.failedConvergences,
         this.droppedOperations,
         oldestAge,
         latest != null ? latest.durationNanos() : -1L,
         latest != null ? describeSession(latest.playbackSessionId()) : "",
         latest != null ? latest.failure() : ""
      );
   }

   public synchronized List<String> activeDescriptions(long nowNanos) {
      List<String> descriptions = new ArrayList<>(this.active.size());

      for (VideoCloseDiagnostics.Operation operation : this.active.values()) {
         EnumSet<VideoCloseDiagnostics.Phase> pending = EnumSet.copyOf(operation.required);
         pending.removeAll(operation.completed);
         descriptions.add(
            "op="
               + operation.id
               + " session="
               + describeSession(operation.playbackSessionId)
               + " ageMs="
               + elapsed(operation.requestedNanos, nowNanos) / 1000000L
               + " pending="
               + pending
               + " failures="
               + operation.failures
         );
      }

      return List.copyOf(descriptions);
   }

   private void convergeIfComplete(VideoCloseDiagnostics.Operation operation, long nowNanos) {
      if (operation.completed.containsAll(operation.required)) {
         this.active.remove(operation.id);
         long duration = elapsed(operation.requestedNanos, nowNanos);
         if (operation.hardTimedOut) {
            this.lateConvergences++;
         }

         String failure = operation.failures.isEmpty() ? "" : operation.failures.toString();
         if (!failure.isEmpty()) {
            this.failedConvergences++;
         }

         this.history.addLast(new VideoCloseDiagnostics.CompletedOperation(operation.playbackSessionId, duration, operation.hardTimedOut, failure));

         while (this.history.size() > this.historyLimit) {
            this.history.removeFirst();
         }
      }
   }

   private static String describeTimeout(VideoCloseDiagnostics.Operation operation, long ageNanos, boolean hard) {
      EnumSet<VideoCloseDiagnostics.Phase> pending = EnumSet.copyOf(operation.required);
      pending.removeAll(operation.completed);
      return "video close "
         + (hard ? "HARD" : "soft")
         + " timeout: op="
         + operation.id
         + " session="
         + describeSession(operation.playbackSessionId)
         + " ageMs="
         + ageNanos / 1000000L
         + " pending="
         + pending;
   }

   private static long elapsed(long start, long now) {
      long value = now - start;
      return value < 0L ? Long.MAX_VALUE : value;
   }

   private static String describeFailure(Throwable failure) {
      Throwable root = failure;

      while (root.getCause() != null && root.getCause() != root) {
         root = root.getCause();
      }

      String message = root.getMessage();
      String value = root.getClass().getSimpleName() + (message != null && !message.isBlank() ? ": " + message : "");
      return value.length() <= 240 ? value : value.substring(0, 240);
   }

   private static String describeSession(Optional<PlaybackSessionId> sessionId) {
      String value = sessionId.<String>map(session -> session.value()).orElse("<none>");
      return value.length() <= 48 ? value : value.substring(0, 48);
   }

   private record CompletedOperation(Optional<PlaybackSessionId> playbackSessionId, long durationNanos, boolean late, String failure) {
   }

   private static final class Operation {
      private final long id;
      private final Optional<PlaybackSessionId> playbackSessionId;
      private final long requestedNanos;
      private final EnumSet<VideoCloseDiagnostics.Phase> required;
      private final EnumSet<VideoCloseDiagnostics.Phase> completed = EnumSet.noneOf(VideoCloseDiagnostics.Phase.class);
      private final EnumMap<VideoCloseDiagnostics.Phase, String> failures = new EnumMap<>(VideoCloseDiagnostics.Phase.class);
      private boolean softTimedOut;
      private boolean hardTimedOut;

      private Operation(long id, Optional<PlaybackSessionId> playbackSessionId, long requestedNanos, EnumSet<VideoCloseDiagnostics.Phase> required) {
         this.id = id;
         this.playbackSessionId = playbackSessionId;
         this.requestedNanos = requestedNanos;
         this.required = required;
      }
   }

   public static enum Phase {
      FRAME_QUEUE_CLEARED,
      DECODER_CLOSE_RETURNED,
      DECODE_THREAD_EXITED,
      NATIVE_TERMINATED,
      RENDER_RELEASE_RETURNED;
   }

   public record Snapshot(
      int activeOperations,
      int retainedCompleted,
      long softTimeouts,
      long hardTimeouts,
      long lateConvergences,
      long failedConvergences,
      long droppedOperations,
      long oldestPendingNanos,
      long latestConvergenceNanos,
      String latestSessionId,
      String latestFailure
   ) {
   }
}
