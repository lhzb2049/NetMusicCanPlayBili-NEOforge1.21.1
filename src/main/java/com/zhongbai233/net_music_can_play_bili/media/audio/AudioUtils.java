package com.zhongbai233.net_music_can_play_bili.media.audio;

import net.minecraft.core.BlockPos;

public final class AudioUtils {
   public static final float DISTANCE_REFERENCE = 8.0F;
   public static final float SPATIAL_RADIUS = 1.5F;
   public static final float MAX_AUDIBLE_DISTANCE = 64.0F;
   public static final float AUDIBLE_FADE_FRACTION = 0.2F;

   private AudioUtils() {
   }

   public static BlockPos copyPos(BlockPos pos) {
      return pos == null ? null : new BlockPos(pos.getX(), pos.getY(), pos.getZ());
   }

   public static float[] copyPos3(float[] pos) {
      return pos == null ? null : new float[]{pos[0], pos[1], pos[2]};
   }

   public static float[] centerFor(BlockPos pos) {
      return pos != null ? new float[]{pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F} : new float[]{0.0F, 0.0F, 0.0F};
   }

   public static float distance(float[] a, float[] b) {
      float dx = a[0] - b[0];
      float dy = a[1] - b[1];
      float dz = a[2] - b[2];
      return (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   public static float clampGain(float gain) {
      return Math.max(0.0F, Math.min(1.0F, gain));
   }

   public static float gainForDistance(float d) {
      float clamped = Math.max(1.5F, d);
      return clampGain(8.0F / (8.0F + clamped));
   }

   public static float spatialGainForDistance(float d, float volume) {
      return spatialGainForDistance(d, volume, volume);
   }

   public static float spatialGainForDistance(float d, float audibleRangeScale, float outputGain) {
      return AudioPlaybackRange.evaluateSphere(d, 64.0F, audibleRangeScale, outputGain, false).gain();
   }

   public static float spatialGainForDistance(float d, float maxDistance, float outputGain, boolean absoluteDistance) {
      return AudioPlaybackRange.evaluateSphere(d, maxDistance, 1.0F, outputGain, false).gain();
   }

   public static String fmtPos(float[] p) {
      return p == null ? "(null)" : String.format("(%.2f, %.2f, %.2f)", p[0], p[1], p[2]);
   }
}
