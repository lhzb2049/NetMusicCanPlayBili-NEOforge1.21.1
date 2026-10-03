package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class LegacyPreviewSessionState<T, R> {
   private volatile LegacyPreviewSessionState.Snapshot<T, R> snapshot = LegacyPreviewSessionState.Snapshot.empty();

   synchronized void begin(String sessionId, Collection<? extends T> projectors, R request) {
      List<T> positions = immutableDistinct(projectors);
      Optional<PlaybackSessionId> parsedSessionId = PlaybackSessionId.parse(sessionId);
      this.snapshot = new LegacyPreviewSessionState.Snapshot<>(
         parsedSessionId, positions, first(positions), parsedSessionId.isPresent() && !positions.isEmpty(), request
      );
   }

   synchronized void replaceProjectors(Collection<? extends T> projectors) {
      LegacyPreviewSessionState.Snapshot<T, R> current = this.snapshot;
      List<T> positions = immutableDistinct(projectors);
      this.snapshot = new LegacyPreviewSessionState.Snapshot<>(
         current.playbackSessionId(), positions, first(positions), current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request()
      );
   }

   synchronized void detachProjector(T projector) {
      if (projector != null) {
         LegacyPreviewSessionState.Snapshot<T, R> current = this.snapshot;
         List<T> positions = current.projectors().stream().filter(candidate -> !candidate.equals(projector)).toList();
         if (positions.size() != current.projectors().size()) {
            T primary = projector.equals(current.primaryProjector()) ? first(positions) : current.primaryProjector();
            this.snapshot = new LegacyPreviewSessionState.Snapshot<>(
               current.playbackSessionId(), positions, primary, current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request()
            );
         }
      }
   }

   synchronized void removeProjectorsIf(Predicate<? super T> predicate) {
      if (predicate != null) {
         LegacyPreviewSessionState.Snapshot<T, R> current = this.snapshot;
         List<T> positions = current.projectors().stream().filter(predicate.negate()).toList();
         if (positions.size() != current.projectors().size()) {
            T primary = positions.contains(current.primaryProjector()) ? current.primaryProjector() : first(positions);
            this.snapshot = new LegacyPreviewSessionState.Snapshot<>(
               current.playbackSessionId(), positions, primary, current.playbackSessionId().isPresent() && !positions.isEmpty(), current.request()
            );
         }
      }
   }

   synchronized void setPrimaryProjector(T projector) {
      LegacyPreviewSessionState.Snapshot<T, R> current = this.snapshot;
      this.snapshot = new LegacyPreviewSessionState.Snapshot<>(
         current.playbackSessionId(), current.projectors(), projector, current.requiresProjector(), current.request()
      );
   }

   synchronized void clearForReplacement() {
      LegacyPreviewSessionState.Snapshot<T, R> current = this.snapshot;
      this.snapshot = new LegacyPreviewSessionState.Snapshot<>(current.playbackSessionId(), List.of(), null, false, current.request());
   }

   synchronized void clear() {
      this.snapshot = LegacyPreviewSessionState.Snapshot.empty();
   }

   LegacyPreviewSessionState.Snapshot<T, R> snapshot() {
      return this.snapshot;
   }

   String sessionId() {
      return this.snapshot.sessionId();
   }

   List<T> projectors() {
      return this.snapshot.projectors();
   }

   T primaryProjector() {
      return this.snapshot.primaryProjector();
   }

   boolean requiresProjector() {
      return this.snapshot.requiresProjector();
   }

   R request() {
      return this.snapshot.request();
   }

   boolean matchesSession(String sessionId) {
      return this.snapshot.playbackSessionId().equals(PlaybackSessionId.parse(sessionId));
   }

   private static <T> List<T> immutableDistinct(Collection<? extends T> values) {
      if (values != null && !values.isEmpty()) {
         LinkedHashSet<T> distinct = new LinkedHashSet<>();

         for (T value : values) {
            if (value != null) {
               distinct.add(value);
            }
         }

         return List.copyOf(distinct);
      } else {
         return List.of();
      }
   }

   private static <T> T first(List<T> values) {
      return values.isEmpty() ? null : values.get(0);
   }

   record Snapshot<T, R>(Optional<PlaybackSessionId> playbackSessionId, List<T> projectors, T primaryProjector, boolean requiresProjector, R request) {
      Snapshot(Optional<PlaybackSessionId> playbackSessionId, List<T> projectors, T primaryProjector, boolean requiresProjector, R request) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.playbackSessionId = playbackSessionId;
         this.projectors = projectors;
         this.primaryProjector = primaryProjector;
         this.requiresProjector = requiresProjector;
         this.request = request;
      }

      Snapshot(String sessionId, List<T> projectors, T primaryProjector, boolean requiresProjector, R request) {
         this(PlaybackSessionId.parse(sessionId), projectors, primaryProjector, requiresProjector, request);
      }

      String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }

      private static <T, R> LegacyPreviewSessionState.Snapshot<T, R> empty() {
         return new LegacyPreviewSessionState.Snapshot<>(Optional.empty(), List.of(), null, false, null);
      }
   }
}
