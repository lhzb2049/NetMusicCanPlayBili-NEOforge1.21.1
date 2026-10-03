package com.zhongbai233.net_music_can_play_bili.media.audio;

public final class AudioPlaybackRange {
   public static final float DEFAULT_DISTANCE = 64.0F;
   public static final float MAX_CONFIGURED_DISTANCE = 4096.0F;
   public static final float MAX_RANGE_SCALE = 2.0F;
   public static final float MAX_OUTPUT_GAIN = 2.0F;
   public static final float DISTANCE_REFERENCE = 8.0F;
   public static final float SPATIAL_RADIUS = 1.5F;
   public static final float FADE_FRACTION = 0.2F;
   public static final float NOTICE_LEAD_FRACTION = 0.1F;
   public static final float NOTICE_EXIT_HYSTERESIS = 2.0F;
   public static final float ZONE_FADE_BAND = 4.0F;
   public static final float ZONE_REENTRY_INSET = 2.0F;
   public static final double HEADPHONE_LINK_DISTANCE_SQUARED = 4096.0;
   public static final int SYNC_DISTANCE_BLOCKS = 96;

   private AudioPlaybackRange() {
   }

   public static AudioPlaybackRange.SphereResult evaluateSphere(float distance, float configuredDistance, float volume, boolean previouslyNoticeActive) {
      return evaluateSphere(distance, configuredDistance, volume, volume, previouslyNoticeActive);
   }

   public static AudioPlaybackRange.SphereResult evaluateSphere(
      float distance, float configuredDistance, float rangeScale, float outputGain, boolean previouslyNoticeActive
   ) {
      AudioPlaybackRange.Profile profile = profile(configuredDistance, rangeScale, outputGain);
      float safeDistance = Float.isFinite(distance) ? Math.max(0.0F, distance) : Float.POSITIVE_INFINITY;
      float noticeLimit = previouslyNoticeActive ? profile.noticeExitDistance() : profile.noticeDistance();
      boolean noticeActive = profile.outputGain() > 0.0F && safeDistance < noticeLimit;
      float gain = gain(safeDistance, profile);
      return new AudioPlaybackRange.SphereResult(noticeActive, gain > 0.0F, gain, profile);
   }

   public static AudioPlaybackRange.Profile profile(float configuredDistance, float rangeScale, float outputGain) {
      float configured = normalizeConfiguredDistance(configuredDistance);
      float scale = clamp(rangeScale, 0.0F, 2.0F);
      float output = clamp(outputGain, 0.0F, 2.0F);
      float nominalDistance = configured * scale;
      float fadeEndDistance = nominalDistance * 1.2F;
      float noticeDistance = fadeEndDistance + nominalDistance * 0.1F;
      float noticeExitDistance = noticeDistance + 2.0F;
      return new AudioPlaybackRange.Profile(configured, scale, output, nominalDistance, fadeEndDistance, noticeDistance, noticeExitDistance);
   }

   public static AudioPlaybackRange.ZoneResult evaluateAabb(
      boolean previouslyActive, double relativeX, double relativeY, double relativeZ, double halfX, double halfY, double halfZ
   ) {
      validateRadius(halfX);
      validateRadius(halfY);
      validateRadius(halfZ);
      boolean inside = strictlyInsideAabb(relativeX, relativeY, relativeZ, halfX, halfY, halfZ);
      boolean active = previouslyActive
         ? inside
         : strictlyInsideAabb(relativeX, relativeY, relativeZ, reentryHalfExtent(halfX), reentryHalfExtent(halfY), reentryHalfExtent(halfZ));
      double margin = Math.min(halfX - Math.abs(relativeX), Math.min(halfY - Math.abs(relativeY), halfZ - Math.abs(relativeZ)));
      if (!active) {
         return new AudioPlaybackRange.ZoneResult(false, 0.0F, margin);
      } else {
         double t = Math.clamp(margin / 4.0, 0.0, 1.0);
         return new AudioPlaybackRange.ZoneResult(true, smoothStep((float)t), margin);
      }
   }

   public static AudioPlaybackRange.ZoneResult evaluateEllipsoid(
      boolean previouslyActive, double relativeX, double relativeY, double relativeZ, double radiusX, double radiusY, double radiusZ
   ) {
      validateRadius(radiusX);
      validateRadius(radiusY);
      validateRadius(radiusZ);
      if (Double.isFinite(relativeX) && Double.isFinite(relativeY) && Double.isFinite(relativeZ)) {
         double normalized = Math.sqrt(
            relativeX * relativeX / (radiusX * radiusX) + relativeY * relativeY / (radiusY * radiusY) + relativeZ * relativeZ / (radiusZ * radiusZ)
         );
         boolean inside = normalized < 1.0;
         double distance = Math.sqrt(relativeX * relativeX + relativeY * relativeY + relativeZ * relativeZ);
         double margin = normalized > 1.0E-12 ? distance / normalized - distance : Math.min(radiusX, Math.min(radiusY, radiusZ));
         double reentryInset = Math.min(2.0, Math.max(0.0, Math.min(radiusX, Math.min(radiusY, radiusZ)) - 1.0E-6));
         boolean active = inside && (previouslyActive || margin > reentryInset);
         if (!active) {
            return new AudioPlaybackRange.ZoneResult(false, 0.0F, margin);
         } else {
            double t = Math.clamp(margin / 4.0, 0.0, 1.0);
            return new AudioPlaybackRange.ZoneResult(true, smoothStep((float)t), margin);
         }
      } else {
         return new AudioPlaybackRange.ZoneResult(false, 0.0F, Double.NEGATIVE_INFINITY);
      }
   }

   public static float normalizeConfiguredDistance(float distance) {
      return Float.isFinite(distance) && distance > 0.0F ? Math.min(distance, 4096.0F) : 64.0F;
   }

   public static float clampVolume(float volume) {
      return clamp(volume, 0.0F, 2.0F);
   }

   private static float gain(float distance, AudioPlaybackRange.Profile profile) {
      if (!(profile.outputGain() <= 0.0F) && !(profile.nominalDistance() <= 0.0F) && !(distance >= profile.fadeEndDistance())) {
         float clampedDistance = Math.max(1.5F, distance);
         float baseGain = 8.0F / (8.0F + clampedDistance) * profile.outputGain();
         if (distance <= profile.nominalDistance()) {
            return baseGain;
         } else {
            float fadeDistance = Math.max(0.001F, profile.fadeEndDistance() - profile.nominalDistance());
            float remaining = clamp((profile.fadeEndDistance() - distance) / fadeDistance, 0.0F, 1.0F);
            return baseGain * smoothStep(remaining);
         }
      } else {
         return 0.0F;
      }
   }

   private static float smoothStep(float value) {
      float t = clamp(value, 0.0F, 1.0F);
      return t * t * (3.0F - 2.0F * t);
   }

   private static boolean strictlyInsideAabb(double x, double y, double z, double halfX, double halfY, double halfZ) {
      return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Math.abs(x) < halfX && Math.abs(y) < halfY && Math.abs(z) < halfZ;
   }

   private static double reentryHalfExtent(double halfExtent) {
      return Math.max(1.0E-6, halfExtent - 2.0);
   }

   private static float clamp(float value, float minimum, float maximum) {
      return !Float.isFinite(value) ? minimum : Math.max(minimum, Math.min(maximum, value));
   }

   private static void validateRadius(double value) {
      if (!Double.isFinite(value) || value <= 0.0) {
         throw new IllegalArgumentException("radius must be finite and positive");
      }
   }

   public record Profile(
      float configuredDistance,
      float rangeScale,
      float outputGain,
      float nominalDistance,
      float fadeEndDistance,
      float noticeDistance,
      float noticeExitDistance
   ) {
   }

   public record SphereResult(boolean noticeActive, boolean audible, float gain, AudioPlaybackRange.Profile profile) {
   }

   public record ZoneResult(boolean active, float gain, double boundaryMargin) {
   }
}
