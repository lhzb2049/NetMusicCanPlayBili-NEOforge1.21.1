package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SyncedStreamRecoveryRegistry {
   private static final Logger LOGGER = System.getLogger(SyncedStreamRecoveryRegistry.class.getName());
   private static final AudioStreamProperties.Recovery PROPERTIES = AudioStreamProperties.recovery();
   private static final int MAX_ATTEMPTS = PROPERTIES.maxAttempts();
   private static final long MIN_INTERVAL_MILLIS = PROPERTIES.minIntervalMillis();
   private static final ConcurrentHashMap<PlaybackSessionId, SyncedStreamRecoveryRegistry.Entry> ENTRIES = new ConcurrentHashMap<>();
   private static final AtomicLong GENERATIONS = new AtomicLong();

   private SyncedStreamRecoveryRegistry() {
   }

   public static SyncedStreamRecoveryRegistry.Registration register(String sessionId, SyncedStreamRecoveryRegistry.RecoveryHandler handler) {
      return PlaybackSessionId.parse(sessionId).map(parsed -> register(parsed, handler)).orElse(SyncedStreamRecoveryRegistry.Registration.NONE);
   }

   public static SyncedStreamRecoveryRegistry.Registration register(PlaybackSessionId sessionId, SyncedStreamRecoveryRegistry.RecoveryHandler handler) {
      if (sessionId != null && handler != null) {
         long generation = GENERATIONS.incrementAndGet();
         ENTRIES.compute(
            sessionId,
            (ignored, existing) -> existing == null
               ? new SyncedStreamRecoveryRegistry.Entry(handler, new AtomicInteger(), 0L, generation)
               : new SyncedStreamRecoveryRegistry.Entry(handler, existing.attempts(), existing.lastAttemptMillis(), generation)
         );
         return new SyncedStreamRecoveryRegistry.Registration(sessionId, generation);
      } else {
         return SyncedStreamRecoveryRegistry.Registration.NONE;
      }
   }

   public static void unregister(String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(ENTRIES::remove);
   }

   public static void unregister(SyncedStreamRecoveryRegistry.Registration registration) {
      if (registration != null && registration != SyncedStreamRecoveryRegistry.Registration.NONE) {
         registration.playbackSessionId()
            .ifPresent(
               sessionId -> ENTRIES.computeIfPresent(
                  sessionId, (ignored, entry) -> (SyncedStreamRecoveryRegistry.Entry)(entry.generation() == registration.generation() ? null : entry)
               )
            );
      }
   }

   public static void clear() {
      ENTRIES.clear();
   }

   public static boolean reportFailure(String sessionId, URL failedUrl, Throwable error) {
      return PlaybackSessionId.parse(sessionId).map(parsed -> reportFailure(parsed, failedUrl, error)).orElse(false);
   }

   public static boolean reportFailure(PlaybackSessionId sessionId, URL failedUrl, Throwable error) {
      if (sessionId == null) {
         return false;
      } else {
         SyncedStreamRecoveryRegistry.Entry entry = ENTRIES.get(sessionId);
         if (entry == null) {
            return false;
         } else {
            long now = System.currentTimeMillis();
            if (now - entry.lastAttemptMillis() < MIN_INTERVAL_MILLIS) {
               LOGGER.log(Level.DEBUG, "忽略过密的媒体流恢复请求: session={0} reason={1}", sessionId, error != null ? error.toString() : "unknown");
               return true;
            } else {
               int attempt = entry.attempts().incrementAndGet();
               if (attempt > Math.max(0, MAX_ATTEMPTS)) {
                  LOGGER.log(
                     Level.WARNING, "媒体流自动恢复次数耗尽: session={0} attempts={1} lastError={2}", sessionId, attempt - 1, error != null ? error.toString() : "unknown"
                  );
                  ENTRIES.remove(sessionId, entry);
                  return false;
               } else {
                  SyncedStreamRecoveryRegistry.Entry attemptedEntry = entry.withLastAttemptMillis(now);
                  if (!ENTRIES.replace(sessionId, entry, attemptedEntry)) {
                     LOGGER.log(Level.DEBUG, "媒体流恢复处理器已由新播放代接管: session={0} attempt={1}", sessionId, attempt);
                     return true;
                  } else {
                     try {
                        boolean scheduled = attemptedEntry.handler()
                           .recover(new SyncedStreamRecoveryRegistry.RecoveryRequest(sessionId, failedUrl, error, attempt));
                        if (!scheduled) {
                           LOGGER.log(
                              Level.WARNING,
                              "媒体流恢复处理器拒绝恢复: session={0} attempt={1} reason={2}",
                              sessionId,
                              attempt,
                              error != null ? error.toString() : "unknown"
                           );
                        }

                        return scheduled;
                     } catch (RuntimeException var9) {
                        LOGGER.log(Level.WARNING, "媒体流恢复处理器异常: session=" + sessionId + " attempt=" + attempt, var9);
                        return false;
                     }
                  }
               }
            }
         }
      }
   }

   private record Entry(SyncedStreamRecoveryRegistry.RecoveryHandler handler, AtomicInteger attempts, long lastAttemptMillis, long generation) {
      SyncedStreamRecoveryRegistry.Entry withLastAttemptMillis(long value) {
         return new SyncedStreamRecoveryRegistry.Entry(this.handler, this.attempts, value, this.generation);
      }
   }

   @FunctionalInterface
   public interface RecoveryHandler {
      boolean recover(SyncedStreamRecoveryRegistry.RecoveryRequest var1);
   }

   public record RecoveryRequest(PlaybackSessionId playbackSessionId, URL failedUrl, Throwable error, int attempt) {
      public RecoveryRequest(PlaybackSessionId playbackSessionId, URL failedUrl, Throwable error, int attempt) {
         playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
         this.playbackSessionId = playbackSessionId;
         this.failedUrl = failedUrl;
         this.error = error;
         this.attempt = attempt;
      }

      public RecoveryRequest(String sessionId, URL failedUrl, Throwable error, int attempt) {
         this(PlaybackSessionId.of(sessionId), failedUrl, error, attempt);
      }

      public String sessionId() {
         return this.playbackSessionId.value();
      }
   }

   public record Registration(Optional<PlaybackSessionId> playbackSessionId, long generation) {
      private static final SyncedStreamRecoveryRegistry.Registration NONE = new SyncedStreamRecoveryRegistry.Registration(Optional.empty(), 0L);

      public Registration(Optional<PlaybackSessionId> playbackSessionId, long generation) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.playbackSessionId = playbackSessionId;
         this.generation = generation;
      }

      public Registration(String sessionId, long generation) {
         this(PlaybackSessionId.parse(sessionId), generation);
      }

      public Registration(PlaybackSessionId sessionId, long generation) {
         this(Optional.ofNullable(sessionId), generation);
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }
   }
}
