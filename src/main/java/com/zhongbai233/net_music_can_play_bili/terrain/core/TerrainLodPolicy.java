package com.zhongbai233.net_music_can_play_bili.terrain.core;

public final class TerrainLodPolicy {
   private final double nearDistance;
   private final double midDistance;
   private final double hysteresis;

   public TerrainLodPolicy(double nearDistance, double midDistance, double hysteresis) {
      if (Double.isFinite(nearDistance)
         && Double.isFinite(midDistance)
         && Double.isFinite(hysteresis)
         && !(nearDistance <= 0.0)
         && !(midDistance <= nearDistance)
         && !(hysteresis < 0.0)
         && !(hysteresis >= nearDistance)) {
         this.nearDistance = nearDistance;
         this.midDistance = midDistance;
         this.hysteresis = hysteresis;
      } else {
         throw new IllegalArgumentException("invalid terrain LOD thresholds");
      }
   }

   public TerrainLodLevel choose(double distance, TerrainLodLevel previous, boolean loaded, boolean selectedNearby, double memoryPressure) {
      if (!loaded) {
         return TerrainLodLevel.UNKNOWN;
      } else if (!Double.isFinite(distance) || distance < 0.0 || !Double.isFinite(memoryPressure) || memoryPressure < 0.0) {
         throw new IllegalArgumentException("invalid terrain LOD inputs");
      } else if (selectedNearby) {
         return TerrainLodLevel.NEAR;
      } else {
         TerrainLodLevel base = this.chooseWithHysteresis(distance, previous);
         int pressurePenalty = memoryPressure >= 0.95 ? 2 : (memoryPressure >= 0.8 ? 1 : 0);
         int degraded = Math.min(TerrainLodLevel.FAR.ordinal(), base.ordinal() + pressurePenalty);
         return TerrainLodLevel.values()[degraded];
      }
   }

   private TerrainLodLevel chooseWithHysteresis(double distance, TerrainLodLevel previous) {
      TerrainLodLevel stablePrevious = previous != null && previous != TerrainLodLevel.UNKNOWN ? previous : this.raw(distance);

      return switch (stablePrevious) {
         case NEAR -> distance > this.nearDistance + this.hysteresis ? this.raw(distance) : TerrainLodLevel.NEAR;
         case MID -> distance < this.nearDistance - this.hysteresis
            ? TerrainLodLevel.NEAR
            : (distance > this.midDistance + this.hysteresis ? TerrainLodLevel.FAR : TerrainLodLevel.MID);
         case FAR -> distance < this.midDistance - this.hysteresis ? this.raw(distance) : TerrainLodLevel.FAR;
         case UNKNOWN -> this.raw(distance);
      };
   }

   private TerrainLodLevel raw(double distance) {
      if (distance <= this.nearDistance) {
         return TerrainLodLevel.NEAR;
      } else {
         return distance <= this.midDistance ? TerrainLodLevel.MID : TerrainLodLevel.FAR;
      }
   }
}
