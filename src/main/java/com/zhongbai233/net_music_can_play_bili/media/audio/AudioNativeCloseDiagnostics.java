package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class AudioNativeCloseDiagnostics {
   private static final int DEFAULT_ACTIVE_LIMIT = 256;
   private static final int DEFAULT_HISTORY_LIMIT = 128;
   private static final MediaCloseProperties.Timeouts TIMEOUTS = MediaCloseProperties.openAlTimeouts();
   private static final AudioNativeCloseDiagnostics GLOBAL = new AudioNativeCloseDiagnostics(256, 128, TIMEOUTS.softTimeoutNanos(), TIMEOUTS.hardTimeoutNanos());
   private final int activeLimit;
   private final int historyLimit;
   private final long softTimeoutNanos;
   private final long hardTimeoutNanos;
   private final AtomicLong sequence = new AtomicLong();
   private final LinkedHashMap<Long, AudioNativeCloseDiagnostics.Operation> active = new LinkedHashMap<>();
   private final ArrayDeque<AudioNativeCloseDiagnostics.CompletedOperation> history = new ArrayDeque<>();
   private long softTimeouts;
   private long hardTimeouts;
   private long lateConvergences;
   private long droppedOperations;
   private long totalSourcesRequested;
   private long totalBuffersRequested;
   private long failedOperations;
   private long sourceDeleteFailures;
   private long bufferDeleteFailures;

   public AudioNativeCloseDiagnostics(int activeLimit, int historyLimit, long softTimeoutNanos, long hardTimeoutNanos) {
      this.activeLimit = Math.max(1, activeLimit);
      this.historyLimit = Math.max(1, historyLimit);
      this.softTimeoutNanos = Math.max(1L, softTimeoutNanos);
      this.hardTimeoutNanos = Math.max(this.softTimeoutNanos, hardTimeoutNanos);
   }

   public static AudioNativeCloseDiagnostics global() {
      return GLOBAL;
   }

   public static List<String> tickGlobal() {
      return GLOBAL.tick(System.nanoTime());
   }

   public static String describeGlobal(int pendingNativeBatches) {
      AudioNativeCloseDiagnostics.Snapshot snapshot = GLOBAL.snapshot(System.nanoTime());
      return "openal closes active="
         + snapshot.activeOperations()
         + " deferred="
         + snapshot.deferredOperations()
         + " pendingBatches="
         + Math.max(0, pendingNativeBatches)
         + " oldestMs="
         + snapshot.oldestPendingNanos() / 1000000L
         + " softTimeouts="
         + snapshot.softTimeouts()
         + " hardTimeouts="
         + snapshot.hardTimeouts()
         + " late="
         + snapshot.lateConvergences()
         + " dropped="
         + snapshot.droppedOperations()
         + " requestedSources="
         + snapshot.totalSourcesRequested()
         + " requestedBuffers="
         + snapshot.totalBuffersRequested()
         + " failedBatches="
         + snapshot.failedOperations()
         + " sourceDeleteFailures="
         + snapshot.sourceDeleteFailures()
         + " bufferDeleteFailures="
         + snapshot.bufferDeleteFailures();
   }

   public synchronized long begin(int sourceCount, int bufferCount, long nowNanos) {
      if (this.active.size() >= this.activeLimit) {
         Long oldest = this.active.keySet().iterator().next();
         this.active.remove(oldest);
         this.droppedOperations++;
      }

      long id = this.sequence.incrementAndGet();
      int sources = Math.max(0, sourceCount);
      int buffers = Math.max(0, bufferCount);
      this.active.put(id, new AudioNativeCloseDiagnostics.Operation(id, nowNanos, sources, buffers));
      this.totalSourcesRequested += sources;
      this.totalBuffersRequested += buffers;
      return id;
   }

   public synchronized void deferred(long operationId) {
      AudioNativeCloseDiagnostics.Operation operation = this.active.get(operationId);
      if (operation != null) {
         operation.deferred = true;
      }
   }

   public synchronized void complete(long operationId, long nowNanos) {
      this.complete(operationId, nowNanos, 0, 0);
   }

   public synchronized void complete(long operationId, long nowNanos, int failedSourceDeletes, int failedBufferDeletes) {
      AudioNativeCloseDiagnostics.Operation operation = this.active.remove(operationId);
      if (operation != null) {
         int sourceFailures = Math.min(operation.sourceCount, Math.max(0, failedSourceDeletes));
         int bufferFailures = Math.min(operation.bufferCount, Math.max(0, failedBufferDeletes));
         if (sourceFailures > 0 || bufferFailures > 0) {
            this.failedOperations++;
            this.sourceDeleteFailures += sourceFailures;
            this.bufferDeleteFailures += bufferFailures;
         }

         long duration = elapsed(operation.requestedNanos, nowNanos);
         if (operation.hardTimedOut) {
            this.lateConvergences++;
         }

         this.history
            .addLast(new AudioNativeCloseDiagnostics.CompletedOperation(duration, operation.deferred, operation.hardTimedOut, sourceFailures, bufferFailures));

         while (this.history.size() > this.historyLimit) {
            this.history.removeFirst();
         }
      }
   }

   public synchronized List<String> tick(long nowNanos) {
      List<String> warnings = new ArrayList<>();

      for (AudioNativeCloseDiagnostics.Operation operation : this.active.values()) {
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

   public synchronized AudioNativeCloseDiagnostics.Snapshot snapshot(long nowNanos) {
      long oldestAge = 0L;
      int deferred = 0;

      for (AudioNativeCloseDiagnostics.Operation operation : this.active.values()) {
         oldestAge = Math.max(oldestAge, elapsed(operation.requestedNanos, nowNanos));
         if (operation.deferred) {
            deferred++;
         }
      }

      return new AudioNativeCloseDiagnostics.Snapshot(
         this.active.size(),
         deferred,
         this.history.size(),
         this.softTimeouts,
         this.hardTimeouts,
         this.lateConvergences,
         this.droppedOperations,
         oldestAge,
         this.totalSourcesRequested,
         this.totalBuffersRequested,
         this.failedOperations,
         this.sourceDeleteFailures,
         this.bufferDeleteFailures
      );
   }

   private static String describeTimeout(AudioNativeCloseDiagnostics.Operation operation, long ageNanos, boolean hard) {
      return "OpenAL native delete "
         + (hard ? "HARD" : "soft")
         + " timeout: op="
         + operation.id
         + " ageMs="
         + ageNanos / 1000000L
         + " deferred="
         + operation.deferred
         + " sources="
         + operation.sourceCount
         + " buffers="
         + operation.bufferCount;
   }

   private static long elapsed(long start, long now) {
      long value = now - start;
      return value < 0L ? Long.MAX_VALUE : value;
   }

   private record CompletedOperation(long durationNanos, boolean deferred, boolean late, int sourceDeleteFailures, int bufferDeleteFailures) {
   }

   private static final class Operation {
      private final long id;
      private final long requestedNanos;
      private final int sourceCount;
      private final int bufferCount;
      private boolean deferred;
      private boolean softTimedOut;
      private boolean hardTimedOut;

      private Operation(long id, long requestedNanos, int sourceCount, int bufferCount) {
         this.id = id;
         this.requestedNanos = requestedNanos;
         this.sourceCount = sourceCount;
         this.bufferCount = bufferCount;
      }
   }

   public record Snapshot(
      int activeOperations,
      int deferredOperations,
      int retainedCompleted,
      long softTimeouts,
      long hardTimeouts,
      long lateConvergences,
      long droppedOperations,
      long oldestPendingNanos,
      long totalSourcesRequested,
      long totalBuffersRequested,
      long failedOperations,
      long sourceDeleteFailures,
      long bufferDeleteFailures
   ) {
   }
}
