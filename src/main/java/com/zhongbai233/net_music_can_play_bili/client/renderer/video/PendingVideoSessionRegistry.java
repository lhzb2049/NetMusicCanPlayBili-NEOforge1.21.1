package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class PendingVideoSessionRegistry<T> {
   private final Map<PlaybackSessionId, PendingVideoSessionRegistry.Snapshot<T>> sessions = new ConcurrentHashMap<>();
   private final LongSupplier nanoTime;

   PendingVideoSessionRegistry() {
      this(System::nanoTime);
   }

   PendingVideoSessionRegistry(LongSupplier nanoTime) {
      this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
   }

   void beginLoading(String sessionId, Collection<? extends T> projectorPositions) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      List<T> positions = copyPositions(projectorPositions);
      if (parsedSessionId != null && !positions.isEmpty()) {
         long now = this.nanoTime.getAsLong();
         this.sessions
            .compute(
               parsedSessionId,
               (ignored, current) -> new PendingVideoSessionRegistry.Snapshot<>(
                  parsedSessionId,
                  PendingVideoSessionRegistry.State.LOADING,
                  positions,
                  current != null && current.state() == PendingVideoSessionRegistry.State.LOADING ? current.startedNanoTime() : now
               )
            );
      }
   }

   void markFailure(String sessionId, Collection<? extends T> projectorPositions) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      List<T> positions = copyPositions(projectorPositions);
      if (parsedSessionId != null && !positions.isEmpty()) {
         this.sessions
            .put(
               parsedSessionId,
               new PendingVideoSessionRegistry.Snapshot<>(parsedSessionId, PendingVideoSessionRegistry.State.FAILURE, positions, this.nanoTime.getAsLong())
            );
      }
   }

   void updateProjectors(String sessionId, Collection<? extends T> projectorPositions) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (parsedSessionId != null) {
         List<T> positions = copyPositions(projectorPositions);
         this.sessions.computeIfPresent(parsedSessionId, (ignored, current) -> current.withProjectors(positions));
      }
   }

   void detachProjector(T projector) {
      if (projector != null) {
         for (PlaybackSessionId sessionId : List.copyOf(this.sessions.keySet())) {
            this.sessions.computeIfPresent(sessionId, (ignored, current) -> {
               PendingVideoSessionRegistry.Snapshot<T> updated = current.withoutProjector(projector);
               return updated.projectorPositions().isEmpty() ? null : updated;
            });
         }
      }
   }

   PendingVideoSessionRegistry.Snapshot<T> findByProjector(PendingVideoSessionRegistry.State state, T projector) {
      if (state != null && projector != null) {
         for (PendingVideoSessionRegistry.Snapshot<T> snapshot : this.sessions.values()) {
            if (snapshot.state() == state && snapshot.containsProjector(projector)) {
               return snapshot;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   boolean hasFailure(String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      PendingVideoSessionRegistry.Snapshot<T> snapshot = parsedSessionId != null ? this.sessions.get(parsedSessionId) : null;
      return snapshot != null && snapshot.state() == PendingVideoSessionRegistry.State.FAILURE;
   }

   void clearLoading(String sessionId) {
      PlaybackSessionId.parse(sessionId)
         .ifPresent(
            parsedSessionId -> this.sessions
               .computeIfPresent(parsedSessionId, (ignored, current) -> current.state() == PendingVideoSessionRegistry.State.LOADING ? null : current)
         );
   }

   void clearSession(String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(this.sessions::remove);
   }

   void clear(PendingVideoSessionRegistry.State state) {
      if (state != null) {
         this.sessions.entrySet().removeIf(entry -> entry.getValue().state() == state);
      }
   }

   void clear() {
      this.sessions.clear();
   }

   int count(PendingVideoSessionRegistry.State state) {
      return state == null ? 0 : (int)this.sessions.values().stream().filter(snapshot -> snapshot.state() == state).count();
   }

   private static <T> List<T> copyPositions(Collection<? extends T> projectorPositions) {
      return projectorPositions == null ? List.of() : List.copyOf(projectorPositions);
   }

   record Snapshot<T>(PlaybackSessionId playbackSessionId, PendingVideoSessionRegistry.State state, List<T> projectorPositions, long startedNanoTime) {
      Snapshot(PlaybackSessionId playbackSessionId, PendingVideoSessionRegistry.State state, List<T> projectorPositions, long startedNanoTime) {
         playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
         state = Objects.requireNonNull(state, "state");
         projectorPositions = List.copyOf(projectorPositions);
         this.playbackSessionId = playbackSessionId;
         this.state = state;
         this.projectorPositions = projectorPositions;
         this.startedNanoTime = startedNanoTime;
      }

      Snapshot(String sessionId, PendingVideoSessionRegistry.State state, List<T> projectorPositions, long startedNanoTime) {
         this(PlaybackSessionId.of(sessionId), state, projectorPositions, startedNanoTime);
      }

      String sessionId() {
         return this.playbackSessionId.value();
      }

      boolean containsProjector(T projector) {
         return this.projectorPositions.contains(projector);
      }

      PendingVideoSessionRegistry.Snapshot<T> withProjectors(Collection<? extends T> projectors) {
         return new PendingVideoSessionRegistry.Snapshot<>(
            this.playbackSessionId, this.state, PendingVideoSessionRegistry.copyPositions(projectors), this.startedNanoTime
         );
      }

      PendingVideoSessionRegistry.Snapshot<T> withoutProjector(T projector) {
         return new PendingVideoSessionRegistry.Snapshot<>(
            this.playbackSessionId,
            this.state,
            this.projectorPositions.stream().filter(candidate -> !Objects.equals(candidate, projector)).toList(),
            this.startedNanoTime
         );
      }
   }

   static enum State {
      LOADING,
      FAILURE;
   }
}
