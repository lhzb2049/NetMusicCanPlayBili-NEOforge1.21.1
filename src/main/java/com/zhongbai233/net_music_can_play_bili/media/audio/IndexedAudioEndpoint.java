package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Objects;
import java.util.UUID;

public record IndexedAudioEndpoint(
   UUID endpointId,
   PlaybackSourceId sourceId,
   String dimension,
   double x,
   double y,
   double z,
   float configuredDistance,
   float rangeScale,
   float outputGain,
   IndexedAudioEndpoint.Kind kind,
   long revision
) {
   public IndexedAudioEndpoint(
      UUID endpointId,
      PlaybackSourceId sourceId,
      String dimension,
      double x,
      double y,
      double z,
      float configuredDistance,
      float rangeScale,
      float outputGain,
      IndexedAudioEndpoint.Kind kind,
      long revision
   ) {
      endpointId = Objects.requireNonNull(endpointId, "endpointId");
      sourceId = Objects.requireNonNull(sourceId, "sourceId");
      dimension = Objects.requireNonNull(dimension, "dimension").trim();
      kind = Objects.requireNonNull(kind, "kind");
      if (!dimension.isEmpty() && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && revision >= 0L) {
         configuredDistance = AudioPlaybackRange.normalizeConfiguredDistance(configuredDistance);
         rangeScale = AudioPlaybackRange.clampVolume(rangeScale);
         outputGain = AudioPlaybackRange.clampVolume(outputGain);
         this.endpointId = endpointId;
         this.sourceId = sourceId;
         this.dimension = dimension;
         this.x = x;
         this.y = y;
         this.z = z;
         this.configuredDistance = configuredDistance;
         this.rangeScale = rangeScale;
         this.outputGain = outputGain;
         this.kind = kind;
         this.revision = revision;
      } else {
         throw new IllegalArgumentException("invalid indexed audio endpoint");
      }
   }

   public AudioPlaybackRange.SphereResult evaluate(double listenerX, double listenerY, double listenerZ, boolean previouslyActive) {
      double dx = listenerX - this.x;
      double dy = listenerY - this.y;
      double dz = listenerZ - this.z;
      return AudioPlaybackRange.evaluateSphere(
         (float)Math.sqrt(dx * dx + dy * dy + dz * dz), this.configuredDistance, this.rangeScale, this.outputGain, previouslyActive
      );
   }

   public boolean hasAudibleDemand(double listenerX, double listenerY, double listenerZ) {
      return this.evaluate(listenerX, listenerY, listenerZ, false).audible();
   }

   public static enum Kind {
      SOURCE,
      SPEAKER,
      CONSOLE,
      HEADPHONES,
      HANDHELD;
   }
}
