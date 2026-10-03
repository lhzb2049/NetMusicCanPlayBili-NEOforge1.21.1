package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

final class AiSubtitleSessionRegistry<C, S, R> {
   private final Map<C, AiSubtitleSessionRegistry.SessionKey<S>> consumers = new HashMap<>();
   private final Map<AiSubtitleSessionRegistry.SessionKey<S>, AiSubtitleSessionRegistry.Entry<C, R>> sessions = new HashMap<>();
   private final AiSubtitleSessionRegistry.Loader<R> loader;

   AiSubtitleSessionRegistry(AiSubtitleSessionRegistry.Loader<R> loader) {
      this.loader = Objects.requireNonNull(loader, "loader");
   }

   synchronized void acquire(C consumer, S source, PlaybackSessionId sessionId, String rawUrl, String title) {
      Objects.requireNonNull(consumer, "consumer");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(sessionId, "sessionId");
      String normalizedUrl = rawUrl != null ? rawUrl.trim() : "";
      String normalizedTitle = title != null ? title : "";
      AiSubtitleSessionRegistry.SessionKey<S> desired = new AiSubtitleSessionRegistry.SessionKey<>(source, sessionId);
      AiSubtitleSessionRegistry.SessionKey<S> current = this.consumers.get(consumer);
      if (desired.equals(current)) {
         AiSubtitleSessionRegistry.Entry<C, R> existing = this.sessions.get(desired);
         if (existing != null && existing.rawUrl.equals(normalizedUrl)) {
            return;
         }
      }

      this.releaseLocked(consumer);
      AiSubtitleSessionRegistry.Entry<C, R> entry = this.sessions.get(desired);
      if (entry == null || !entry.rawUrl.equals(normalizedUrl)) {
         if (entry != null) {
            entry.task.cancel();

            for (C displaced : entry.consumers.keySet()) {
               this.consumers.remove(displaced, desired);
            }
         }

         AiSubtitleSessionRegistry.Task<R> task = Objects.requireNonNull(this.loader.load(normalizedUrl, normalizedTitle), "loader task");
         entry = new AiSubtitleSessionRegistry.Entry<>(normalizedUrl, task);
         this.sessions.put(desired, entry);
         this.watch(desired, entry);
      }

      entry.consumers.put(consumer, Boolean.TRUE);
      this.consumers.put(consumer, desired);
   }

   synchronized AiSubtitleSessionRegistry.Snapshot<R> snapshot(S source, PlaybackSessionId sessionId) {
      if (source != null && sessionId != null) {
         AiSubtitleSessionRegistry.Entry<C, R> entry = this.sessions.get(new AiSubtitleSessionRegistry.SessionKey(source, sessionId));
         return entry != null ? entry.snapshot : AiSubtitleSessionRegistry.Snapshot.unavailable();
      } else {
         return AiSubtitleSessionRegistry.Snapshot.unavailable();
      }
   }

   synchronized void release(C consumer) {
      if (consumer != null) {
         this.releaseLocked(consumer);
      }
   }

   synchronized void clear() {
      for (AiSubtitleSessionRegistry.Entry<C, R> entry : this.sessions.values()) {
         entry.task.cancel();
      }

      this.sessions.clear();
      this.consumers.clear();
   }

   synchronized void releaseMatching(Predicate<? super C> predicate) {
      Objects.requireNonNull(predicate, "predicate");

      for (C consumer : new ArrayList<>(this.consumers.keySet())) {
         if (predicate.test(consumer)) {
            this.releaseLocked(consumer);
         }
      }
   }

   synchronized int activeSessions() {
      return this.sessions.size();
   }

   synchronized int activeConsumers() {
      return this.consumers.size();
   }

   synchronized AiSubtitleSessionRegistry.Diagnostics diagnostics() {
      int loading = 0;
      int ready = 0;
      int unavailable = 0;
      int failed = 0;

      for (AiSubtitleSessionRegistry.Entry<C, R> entry : this.sessions.values()) {
         switch (entry.snapshot.status()) {
            case LOADING:
               loading++;
               break;
            case READY:
               ready++;
               break;
            case UNAVAILABLE:
               unavailable++;
               break;
            case FAILED:
               failed++;
         }
      }

      return new AiSubtitleSessionRegistry.Diagnostics(this.sessions.size(), this.consumers.size(), loading, ready, unavailable, failed);
   }

   private void watch(AiSubtitleSessionRegistry.SessionKey<S> key, AiSubtitleSessionRegistry.Entry<C, R> expected) {
      expected.task
         .future()
         .whenComplete(
            (result, error) -> {
               synchronized (this) {
                  if (this.sessions.get(key) == expected) {
                     if (error == null) {
                        expected.snapshot = result != null
                           ? new AiSubtitleSessionRegistry.Snapshot<>(AiSubtitleSessionRegistry.Status.READY, (R)result, "")
                           : AiSubtitleSessionRegistry.Snapshot.unavailable();
                     } else {
                        Throwable cause = unwrap(error);
                        if (cause instanceof CancellationException) {
                           expected.snapshot = new AiSubtitleSessionRegistry.Snapshot<>(AiSubtitleSessionRegistry.Status.FAILED, null, "cancelled");
                        } else {
                           expected.snapshot = new AiSubtitleSessionRegistry.Snapshot<>(
                              AiSubtitleSessionRegistry.Status.FAILED, null, cause.getClass().getSimpleName() + ": " + safeMessage(cause)
                           );
                        }
                     }
                  }
               }
            }
         );
   }

   private void releaseLocked(C consumer) {
      AiSubtitleSessionRegistry.SessionKey<S> key = this.consumers.remove(consumer);
      if (key != null) {
         AiSubtitleSessionRegistry.Entry<C, R> entry = this.sessions.get(key);
         if (entry != null) {
            entry.consumers.remove(consumer);
            if (entry.consumers.isEmpty() && this.sessions.remove(key, entry)) {
               entry.task.cancel();
            }
         }
      }
   }

   private static Throwable unwrap(Throwable error) {
      Throwable current = error;

      while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
         current = current.getCause();
      }

      return current;
   }

   private static String safeMessage(Throwable error) {
      String message = error.getMessage();
      return message != null ? message : "";
   }

   record Diagnostics(int sessions, int consumers, int loading, int ready, int unavailable, int failed) {
   }

   private static final class Entry<C, R> {
      private final String rawUrl;
      private final AiSubtitleSessionRegistry.Task<R> task;
      private final Map<C, Boolean> consumers = new HashMap<>();
      private AiSubtitleSessionRegistry.Snapshot<R> snapshot = AiSubtitleSessionRegistry.Snapshot.loading();

      private Entry(String rawUrl, AiSubtitleSessionRegistry.Task<R> task) {
         this.rawUrl = rawUrl;
         this.task = task;
      }
   }

   @FunctionalInterface
   interface Loader<R> {
      AiSubtitleSessionRegistry.Task<R> load(String var1, String var2);
   }

   private record SessionKey<S>(S source, PlaybackSessionId sessionId) {
      private SessionKey(S source, PlaybackSessionId sessionId) {
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(sessionId, "sessionId");
         this.source = source;
         this.sessionId = sessionId;
      }
   }

   record Snapshot<R>(AiSubtitleSessionRegistry.Status status, R result, String failureReason) {
      Snapshot(AiSubtitleSessionRegistry.Status status, R result, String failureReason) {
         Objects.requireNonNull(status, "status");
         failureReason = failureReason != null ? failureReason : "";
         this.status = status;
         this.result = result;
         this.failureReason = failureReason;
      }

      static <R> AiSubtitleSessionRegistry.Snapshot<R> loading() {
         return new AiSubtitleSessionRegistry.Snapshot<>(AiSubtitleSessionRegistry.Status.LOADING, null, "");
      }

      static <R> AiSubtitleSessionRegistry.Snapshot<R> unavailable() {
         return new AiSubtitleSessionRegistry.Snapshot<>(AiSubtitleSessionRegistry.Status.UNAVAILABLE, null, "");
      }
   }

   static enum Status {
      LOADING,
      READY,
      UNAVAILABLE,
      FAILED;
   }

   interface Task<R> {
      CompletableFuture<R> future();

      void cancel();
   }
}
