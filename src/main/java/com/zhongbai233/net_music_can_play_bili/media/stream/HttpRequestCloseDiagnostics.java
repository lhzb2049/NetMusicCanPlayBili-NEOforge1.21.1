package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpRequestCloseDiagnostics {
   private static final int DEFAULT_ACTIVE_LIMIT = 512;
   private static final int DEFAULT_HISTORY_LIMIT = 256;
   private static final HttpRequestCloseDiagnostics GLOBAL = new HttpRequestCloseDiagnostics(512, 256);
   private final int activeLimit;
   private final int historyLimit;
   private final AtomicLong sequence = new AtomicLong();
   private final LinkedHashMap<Long, HttpRequestCloseDiagnostics.Operation> active = new LinkedHashMap<>();
   private final ArrayDeque<HttpRequestCloseDiagnostics.CompletedOperation> history = new ArrayDeque<>();
   private long started;
   private long cancelled;
   private long completed;
   private long failed;
   private long dropped;

   public HttpRequestCloseDiagnostics(int activeLimit, int historyLimit) {
      this.activeLimit = Math.max(1, activeLimit);
      this.historyLimit = Math.max(1, historyLimit);
   }

   public static HttpRequestCloseDiagnostics global() {
      return GLOBAL;
   }

   public synchronized long begin(String kind, String host, long rangeStart, long rangeEnd, long nowNanos) {
      if (this.active.size() >= this.activeLimit) {
         this.active.remove(this.active.keySet().iterator().next());
         this.dropped++;
      }

      long id = this.sequence.incrementAndGet();
      this.active.put(id, new HttpRequestCloseDiagnostics.Operation(id, safe(kind), safe(host), rangeStart, rangeEnd, nowNanos));
      this.started++;
      return id;
   }

   public synchronized void headers(long id, int statusCode) {
      HttpRequestCloseDiagnostics.Operation operation = this.active.get(id);
      if (operation != null) {
         operation.statusCode = statusCode;
      }
   }

   public synchronized void bodyPublished(long id) {
      HttpRequestCloseDiagnostics.Operation operation = this.active.get(id);
      if (operation != null) {
         operation.bodyPublished = true;
      }
   }

   public synchronized void cancelRequested(long id) {
      HttpRequestCloseDiagnostics.Operation operation = this.active.get(id);
      if (operation != null && !operation.cancelRequested) {
         operation.cancelRequested = true;
         this.cancelled++;
      }
   }

   public synchronized void terminal(long id, boolean success, long bytes, long nowNanos) {
      HttpRequestCloseDiagnostics.Operation operation = this.active.remove(id);
      if (operation != null) {
         operation.bytes = Math.max(0L, bytes);
         if (success) {
            this.completed++;
         } else {
            this.failed++;
         }

         this.history
            .addLast(
               new HttpRequestCloseDiagnostics.CompletedOperation(
                  operation.id,
                  operation.kind,
                  operation.host,
                  operation.rangeStart,
                  operation.rangeEnd,
                  operation.statusCode,
                  operation.cancelRequested,
                  operation.bodyPublished,
                  operation.bytes,
                  elapsed(operation.startedNanos, nowNanos)
               )
            );

         while (this.history.size() > this.historyLimit) {
            this.history.removeFirst();
         }
      }
   }

   public synchronized HttpRequestCloseDiagnostics.Snapshot snapshot(long nowNanos) {
      long oldest = 0L;

      for (HttpRequestCloseDiagnostics.Operation operation : this.active.values()) {
         oldest = Math.max(oldest, elapsed(operation.startedNanos, nowNanos));
      }

      HttpRequestCloseDiagnostics.CompletedOperation latest = this.history.peekLast();
      return new HttpRequestCloseDiagnostics.Snapshot(
         this.active.size(),
         this.history.size(),
         this.started,
         this.cancelled,
         this.completed,
         this.failed,
         this.dropped,
         oldest,
         latest != null ? latest.durationNanos : -1L
      );
   }

   private static long elapsed(long start, long now) {
      long elapsed = now - start;
      return elapsed < 0L ? Long.MAX_VALUE : elapsed;
   }

   private static String safe(String value) {
      String normalized = value != null && !value.isBlank() ? value : "<none>";
      return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
   }

   private record CompletedOperation(
      long id,
      String kind,
      String host,
      long rangeStart,
      long rangeEnd,
      int statusCode,
      boolean cancelled,
      boolean bodyPublished,
      long bytes,
      long durationNanos
   ) {
   }

   private static final class Operation {
      private final long id;
      private final String kind;
      private final String host;
      private final long rangeStart;
      private final long rangeEnd;
      private final long startedNanos;
      private int statusCode = -1;
      private boolean cancelRequested;
      private boolean bodyPublished;
      private long bytes;

      private Operation(long id, String kind, String host, long rangeStart, long rangeEnd, long startedNanos) {
         this.id = id;
         this.kind = kind;
         this.host = host;
         this.rangeStart = rangeStart;
         this.rangeEnd = rangeEnd;
         this.startedNanos = startedNanos;
      }
   }

   public record Snapshot(
      int activeRequests,
      int retainedCompleted,
      long startedRequests,
      long cancelRequests,
      long completedRequests,
      long failedRequests,
      long droppedRequests,
      long oldestActiveNanos,
      long latestConvergenceNanos
   ) {
   }
}
