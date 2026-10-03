package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioPlaybackDemandIndex<T> {
   private final Map<PlaybackSourceId, AudioPlaybackDemandIndex.Entry<T>> entries = new ConcurrentHashMap<>();

   public void announce(PlaybackSourceId sourceId, PlaybackSessionId sessionId, T payload) {
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(payload, "payload");
      this.entries
         .compute(
            sourceId,
            (ignored, current) -> current != null && current.sessionId().equals(sessionId)
               ? current.withPayload(payload)
               : new AudioPlaybackDemandIndex.Entry<>(sessionId, payload, AudioPlaybackDemandIndex.State.METADATA, Set.of(), -1L)
         );
   }

   public boolean updateDemand(PlaybackSourceId sourceId, PlaybackSessionId sessionId, Set<UUID> endpointIds, long nowMillis) {
      if (sourceId != null && sessionId != null) {
         Set<UUID> demands = endpointIds != null ? Set.copyOf(endpointIds) : Set.of();
         AudioPlaybackDemandIndex.Entry<T> updated = this.entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (!current.sessionId().equals(sessionId)) {
               return current;
            } else {
               long idleSince = demands.isEmpty() ? (current.endpointIds().isEmpty() ? current.idleSinceMillis() : nowMillis) : -1L;
               return current.withDemand(demands, idleSince);
            }
         });
         return updated != null && updated.sessionId().equals(sessionId) && !updated.endpointIds().isEmpty();
      } else {
         return false;
      }
   }

   public Optional<T> claimStart(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         AudioPlaybackDemandIndex.Entry<T>[] claimed = new AudioPlaybackDemandIndex.Entry[1];
         this.entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (current.sessionId().equals(sessionId) && current.state() == AudioPlaybackDemandIndex.State.METADATA && !current.endpointIds().isEmpty()) {
               claimed[0] = (AudioPlaybackDemandIndex.Entry<T>)current;
               return current.withState(AudioPlaybackDemandIndex.State.STARTING);
            } else {
               return current;
            }
         });
         return claimed[0] != null ? Optional.of(claimed[0].payload()) : Optional.empty();
      } else {
         return Optional.empty();
      }
   }

   public boolean markPlaying(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      return this.transition(sourceId, sessionId, AudioPlaybackDemandIndex.State.STARTING, AudioPlaybackDemandIndex.State.PLAYING);
   }

   public boolean claimStopAfterIdle(PlaybackSourceId sourceId, PlaybackSessionId sessionId, long nowMillis, long idleGraceMillis) {
      if (sourceId != null && sessionId != null) {
         boolean[] claimed = new boolean[1];
         this.entries
            .computeIfPresent(
               sourceId,
               (ignored, current) -> {
                  if (current.sessionId().equals(sessionId)
                     && current.state() != AudioPlaybackDemandIndex.State.METADATA
                     && current.endpointIds().isEmpty()
                     && current.idleSinceMillis() >= 0L
                     && nowMillis - current.idleSinceMillis() >= Math.max(0L, idleGraceMillis)) {
                     claimed[0] = true;
                     return current.withState(AudioPlaybackDemandIndex.State.METADATA);
                  } else {
                     return current;
                  }
               }
            );
         return claimed[0];
      } else {
         return false;
      }
   }

   public Optional<AudioPlaybackDemandIndex.Snapshot<T>> snapshot(PlaybackSourceId sourceId) {
      AudioPlaybackDemandIndex.Entry<T> entry = sourceId != null ? this.entries.get(sourceId) : null;
      return entry != null ? Optional.of(entry.snapshot()) : Optional.empty();
   }

   public List<AudioPlaybackDemandIndex.SourceSnapshot<T>> snapshots() {
      return this.entries.entrySet().stream().map(entry -> new AudioPlaybackDemandIndex.SourceSnapshot<>(entry.getKey(), entry.getValue().snapshot())).toList();
   }

   public void remove(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         this.entries.computeIfPresent(sourceId, (ignored, current) -> current.sessionId().equals(sessionId) ? null : current);
      }
   }

   public void clear() {
      this.entries.clear();
   }

   private boolean transition(PlaybackSourceId sourceId, PlaybackSessionId sessionId, AudioPlaybackDemandIndex.State from, AudioPlaybackDemandIndex.State to) {
      if (sourceId != null && sessionId != null) {
         boolean[] transitioned = new boolean[1];
         this.entries.computeIfPresent(sourceId, (ignored, current) -> {
            if (current.sessionId().equals(sessionId) && current.state() == from) {
               transitioned[0] = true;
               return current.withState(to);
            } else {
               return current;
            }
         });
         return transitioned[0];
      } else {
         return false;
      }
   }

   private record Entry<T>(PlaybackSessionId sessionId, T payload, AudioPlaybackDemandIndex.State state, Set<UUID> endpointIds, long idleSinceMillis) {
      AudioPlaybackDemandIndex.Entry<T> withPayload(T value) {
         return new AudioPlaybackDemandIndex.Entry<>(this.sessionId, value, this.state, this.endpointIds, this.idleSinceMillis);
      }

      AudioPlaybackDemandIndex.Entry<T> withDemand(Set<UUID> value, long idleSince) {
         return new AudioPlaybackDemandIndex.Entry<>(this.sessionId, this.payload, this.state, value, idleSince);
      }

      AudioPlaybackDemandIndex.Entry<T> withState(AudioPlaybackDemandIndex.State value) {
         return new AudioPlaybackDemandIndex.Entry<>(this.sessionId, this.payload, value, this.endpointIds, this.idleSinceMillis);
      }

      AudioPlaybackDemandIndex.Snapshot<T> snapshot() {
         return new AudioPlaybackDemandIndex.Snapshot<>(this.sessionId, this.payload, this.state, this.endpointIds, this.idleSinceMillis);
      }
   }

   public record Snapshot<T>(PlaybackSessionId sessionId, T payload, AudioPlaybackDemandIndex.State state, Set<UUID> endpointIds, long idleSinceMillis) {
   }

   public record SourceSnapshot<T>(PlaybackSourceId sourceId, AudioPlaybackDemandIndex.Snapshot<T> playback) {
   }

   public static enum State {
      METADATA,
      STARTING,
      PLAYING;
   }
}
