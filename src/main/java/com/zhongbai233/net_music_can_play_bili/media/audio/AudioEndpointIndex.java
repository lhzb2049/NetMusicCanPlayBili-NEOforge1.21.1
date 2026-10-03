package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioEndpointIndex {
   private final Map<PlaybackSourceId, Map<UUID, IndexedAudioEndpoint>> bySource = new ConcurrentHashMap<>();
   private final Map<UUID, IndexedAudioEndpoint> byEndpoint = new ConcurrentHashMap<>();

   public void upsert(IndexedAudioEndpoint endpoint) {
      if (endpoint != null) {
         IndexedAudioEndpoint previous = this.byEndpoint
            .compute(
               endpoint.endpointId(),
               (ignored, current) -> (IndexedAudioEndpoint)(current != null && endpoint.revision() < current.revision() ? current : endpoint)
            );
         if (previous == endpoint) {
            this.bySource.values().forEach(endpoints -> endpoints.remove(endpoint.endpointId()));
            this.bySource.computeIfAbsent(endpoint.sourceId(), ignored -> new ConcurrentHashMap<>()).put(endpoint.endpointId(), endpoint);
            this.bySource.entrySet().removeIf(entry -> entry.getValue().isEmpty());
         }
      }
   }

   public void replaceSource(PlaybackSourceId sourceId, Collection<IndexedAudioEndpoint> endpoints) {
      this.removeSource(sourceId);
      if (endpoints != null) {
         endpoints.stream().filter(endpoint -> sourceId != null && sourceId.equals(endpoint.sourceId())).forEach(this::upsert);
      }
   }

   public void remove(UUID endpointId) {
      IndexedAudioEndpoint removed = endpointId != null ? this.byEndpoint.remove(endpointId) : null;
      if (removed != null) {
         Map<UUID, IndexedAudioEndpoint> endpoints = this.bySource.get(removed.sourceId());
         if (endpoints != null) {
            endpoints.remove(endpointId);
            if (endpoints.isEmpty()) {
               this.bySource.remove(removed.sourceId(), endpoints);
            }
         }
      }
   }

   public void removeSource(PlaybackSourceId sourceId) {
      Map<UUID, IndexedAudioEndpoint> removed = sourceId != null ? this.bySource.remove(sourceId) : null;
      if (removed != null) {
         removed.forEach(this.byEndpoint::remove);
      }
   }

   public List<IndexedAudioEndpoint> endpointsFor(PlaybackSourceId sourceId) {
      Map<UUID, IndexedAudioEndpoint> endpoints = sourceId != null ? this.bySource.get(sourceId) : null;
      return endpoints != null ? List.copyOf(endpoints.values()) : List.of();
   }

   public Set<UUID> audibleDemands(PlaybackSourceId sourceId, String dimension, double listenerX, double listenerY, double listenerZ) {
      if (sourceId != null && dimension != null) {
         Map<UUID, IndexedAudioEndpoint> endpoints = this.bySource.get(sourceId);
         if (endpoints == null) {
            return Set.of();
         } else {
            Set<UUID> result = ConcurrentHashMap.newKeySet();

            for (IndexedAudioEndpoint endpoint : endpoints.values()) {
               if (dimension.equals(endpoint.dimension()) && endpoint.hasAudibleDemand(listenerX, listenerY, listenerZ)) {
                  result.add(endpoint.endpointId());
               }
            }

            return Set.copyOf(result);
         }
      } else {
         return Set.of();
      }
   }

   public Set<UUID> anticipatedDemands(
      PlaybackSourceId sourceId, String dimension, double listenerX, double listenerY, double listenerZ, double velocityX, double velocityY, double velocityZ
   ) {
      if (sourceId != null && dimension != null) {
         Map<UUID, IndexedAudioEndpoint> endpoints = this.bySource.get(sourceId);
         if (endpoints == null) {
            return Set.of();
         } else {
            Set<UUID> result = ConcurrentHashMap.newKeySet();

            for (IndexedAudioEndpoint endpoint : endpoints.values()) {
               AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(endpoint.configuredDistance(), endpoint.rangeScale(), endpoint.outputGain());
               if (dimension.equals(endpoint.dimension())
                  && profile.outputGain() > 0.0F
                  && PlaybackApproachPredictor.willEnterSphere(
                     listenerX, listenerY, listenerZ, velocityX, velocityY, velocityZ, endpoint.x(), endpoint.y(), endpoint.z(), profile.fadeEndDistance()
                  )) {
                  result.add(endpoint.endpointId());
               }
            }

            return Set.copyOf(result);
         }
      } else {
         return Set.of();
      }
   }

   public List<IndexedAudioEndpoint> snapshot() {
      return new ArrayList<>(this.byEndpoint.values());
   }

   public void clear() {
      this.bySource.clear();
      this.byEndpoint.clear();
   }
}
