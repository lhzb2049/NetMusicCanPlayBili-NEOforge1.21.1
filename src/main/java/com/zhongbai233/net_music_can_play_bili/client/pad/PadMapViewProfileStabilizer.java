package com.zhongbai233.net_music_can_play_bili.client.pad;

final class PadMapViewProfileStabilizer {
   static final int OUTDOOR_LAYER_Y = Integer.MIN_VALUE;
   private final int indoorEnterConfirmTicks;
   private final int indoorExitConfirmTicks;
   private final int indoorFloorChangeConfirmTicks;
   private final int indoorJumpToleranceBlocks;
   private PadMapViewProfile stableProfile = PadMapViewProfile.OUTDOOR;
   private int indoorCandidateTicks;
   private int outdoorCandidateTicks;
   private int stableIndoorFloorY = Integer.MIN_VALUE;
   private int candidateIndoorFloorY = Integer.MIN_VALUE;
   private int candidateIndoorFloorTicks;

   PadMapViewProfileStabilizer(int indoorEnterConfirmTicks, int indoorExitConfirmTicks, int indoorFloorChangeConfirmTicks, int indoorJumpToleranceBlocks) {
      this.indoorEnterConfirmTicks = Math.max(1, indoorEnterConfirmTicks);
      this.indoorExitConfirmTicks = Math.max(1, indoorExitConfirmTicks);
      this.indoorFloorChangeConfirmTicks = Math.max(1, indoorFloorChangeConfirmTicks);
      this.indoorJumpToleranceBlocks = Math.max(0, indoorJumpToleranceBlocks);
   }

   PadMapViewProfileStabilizer.Result update(PadMapViewProfile rawProfile, int rawFloorY) {
      PadMapViewProfile profile = this.stabilize(rawProfile, rawFloorY);
      return new PadMapViewProfileStabilizer.Result(profile, profile == PadMapViewProfile.INDOOR ? this.stableIndoorFloorY : Integer.MIN_VALUE);
   }

   void reset() {
      this.stableProfile = PadMapViewProfile.OUTDOOR;
      this.indoorCandidateTicks = 0;
      this.outdoorCandidateTicks = 0;
      this.stableIndoorFloorY = Integer.MIN_VALUE;
      this.candidateIndoorFloorY = Integer.MIN_VALUE;
      this.candidateIndoorFloorTicks = 0;
   }

   private PadMapViewProfile stabilize(PadMapViewProfile rawProfile, int rawFloorY) {
      if (this.stableProfile == PadMapViewProfile.INDOOR) {
         if (rawProfile == PadMapViewProfile.INDOOR) {
            this.outdoorCandidateTicks = 0;
            this.stabilizeIndoorFloor(rawFloorY);
            return PadMapViewProfile.INDOOR;
         } else {
            this.indoorCandidateTicks = 0;
            if (++this.outdoorCandidateTicks < this.indoorExitConfirmTicks) {
               return PadMapViewProfile.INDOOR;
            } else {
               this.stableProfile = PadMapViewProfile.OUTDOOR;
               this.stableIndoorFloorY = Integer.MIN_VALUE;
               this.candidateIndoorFloorY = Integer.MIN_VALUE;
               this.candidateIndoorFloorTicks = 0;
               return PadMapViewProfile.OUTDOOR;
            }
         }
      } else {
         if (rawProfile == PadMapViewProfile.INDOOR) {
            this.outdoorCandidateTicks = 0;
            this.candidateIndoorFloorY = rawFloorY;
            if (++this.indoorCandidateTicks >= this.indoorEnterConfirmTicks) {
               this.stableProfile = PadMapViewProfile.INDOOR;
               this.stableIndoorFloorY = rawFloorY;
               this.candidateIndoorFloorTicks = 0;
               return PadMapViewProfile.INDOOR;
            }
         } else {
            this.indoorCandidateTicks = 0;
            this.outdoorCandidateTicks = 0;
         }

         return PadMapViewProfile.OUTDOOR;
      }
   }

   private void stabilizeIndoorFloor(int rawFloorY) {
      if (this.stableIndoorFloorY == Integer.MIN_VALUE) {
         this.stableIndoorFloorY = rawFloorY;
         this.candidateIndoorFloorY = rawFloorY;
         this.candidateIndoorFloorTicks = 0;
      } else if (Math.abs(rawFloorY - this.stableIndoorFloorY) <= this.indoorJumpToleranceBlocks) {
         this.candidateIndoorFloorY = this.stableIndoorFloorY;
         this.candidateIndoorFloorTicks = 0;
      } else if (this.candidateIndoorFloorY != rawFloorY) {
         this.candidateIndoorFloorY = rawFloorY;
         this.candidateIndoorFloorTicks = 1;
      } else {
         if (++this.candidateIndoorFloorTicks >= this.indoorFloorChangeConfirmTicks) {
            this.stableIndoorFloorY = rawFloorY;
            this.candidateIndoorFloorTicks = 0;
         }
      }
   }

   record Result(PadMapViewProfile profile, int floorY) {
   }
}
