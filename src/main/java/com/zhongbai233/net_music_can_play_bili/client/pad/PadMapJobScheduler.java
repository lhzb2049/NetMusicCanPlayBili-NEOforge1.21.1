package com.zhongbai233.net_music_can_play_bili.client.pad;

final class PadMapJobScheduler {
   private final int recenterBlocks;
   private final int indoorRecenterBlocks;
   private final int maxJobLagChunks;
   private final int resampleChunkDistance;
   private final int chunksPerTick;
   private final int fastChunksPerTick;

   PadMapJobScheduler(int recenterBlocks, int indoorRecenterBlocks, int maxJobLagChunks, int resampleChunkDistance, int chunksPerTick, int fastChunksPerTick) {
      this.recenterBlocks = Math.max(1, recenterBlocks);
      this.indoorRecenterBlocks = Math.max(1, indoorRecenterBlocks);
      this.maxJobLagChunks = Math.max(1, maxJobLagChunks);
      this.resampleChunkDistance = Math.max(1, resampleChunkDistance);
      this.chunksPerTick = Math.max(1, chunksPerTick);
      this.fastChunksPerTick = Math.max(1, fastChunksPerTick);
   }

   boolean shouldCancel(PadMapJobScheduler.JobView job, int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      if (job == null) {
         return false;
      } else {
         return job.profile() == profile && job.zoom() == zoom && job.floorY() == playerY
            ? this.chunkDistance(playerX, job.centerX()) >= this.maxJobLagChunks || this.chunkDistance(playerZ, job.centerZ()) >= this.maxJobLagChunks
            : true;
      }
   }

   int chunksPerTick(PadMapJobScheduler.JobView job, int playerX, int playerZ) {
      if (job == null) {
         return this.chunksPerTick;
      } else {
         int lagX = this.chunkDistance(playerX, job.centerX());
         int lagZ = this.chunkDistance(playerZ, job.centerZ());
         int lag = Math.max(lagX, lagZ);
         if (lag >= this.resampleChunkDistance * 2) {
            return this.fastChunksPerTick;
         } else {
            return lag >= this.resampleChunkDistance ? Math.max(this.chunksPerTick, this.fastChunksPerTick - 1) : this.chunksPerTick;
         }
      }
   }

   boolean shouldStart(PadMapSnapshot completed, PadMapViewProfile completedProfile, int playerX, int playerY, int playerZ, PadMapViewProfile profile) {
      int recenter = profile == PadMapViewProfile.INDOOR ? this.indoorRecenterBlocks : this.recenterBlocks;
      boolean hasNoMap = completed == null;
      boolean profileChanged = completed != null && completedProfile != profile;
      boolean floorChanged = completed != null && completed.centerY() != playerY;
      boolean moved = completed != null && (Math.abs(playerX - completed.centerX()) >= recenter || Math.abs(playerZ - completed.centerZ()) >= recenter);
      return hasNoMap || profileChanged || floorChanged || moved;
   }

   boolean canSeedPrevious(PadMapSnapshot completed, PadMapViewProfile completedProfile, PadMapViewProfile profile, float zoom) {
      return profile == completedProfile && completed != null && completed.cellSizeBlocks() == PadMapSamplingPolicy.cellSizeForZoom(zoom);
   }

   private int chunkDistance(int a, int b) {
      return Math.abs(a - b) / 16;
   }

   record JobView(int centerX, int centerZ, int floorY, PadMapViewProfile profile, float zoom) {
   }
}
