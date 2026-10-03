package com.zhongbai233.net_music_can_play_bili.client.pad;

import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;

final class PadMapViewProfileDetector {
   private final int ceilingScanBlocks;
   private final int ceilingMinHits;
   private final int artificialMinHits;

   PadMapViewProfileDetector(int ceilingScanBlocks, int ceilingMinHits, int artificialMinHits) {
      this.ceilingScanBlocks = ceilingScanBlocks;
      this.ceilingMinHits = ceilingMinHits;
      this.artificialMinHits = artificialMinHits;
   }

   PadMapViewProfile detect(Level level, BlockPos playerPos) {
      MutableBlockPos mutable = new MutableBlockPos();
      int ceiling = 0;
      int artificialCeiling = 0;
      int artificial = 0;

      for (int dz = -2; dz <= 2; dz++) {
         for (int dx = -2; dx <= 2; dx++) {
            int x = playerPos.getX() + dx;
            int z = playerPos.getZ() + dz;
            int surfaceY = level.getHeight(Types.WORLD_SURFACE, x, z) - 1;
            if (surfaceY >= playerPos.getY() + 2 && surfaceY <= playerPos.getY() + this.ceilingScanBlocks) {
               ceiling++;
               mutable.set(x, surfaceY, z);
               if (isArtificialProfileBlock(level, mutable, level.getBlockState(mutable))) {
                  artificialCeiling++;
               }
            }

            for (int dy = -1; dy <= 5; dy++) {
               mutable.set(x, playerPos.getY() + dy, z);
               BlockState state = level.getBlockState(mutable);
               if (isArtificialProfileBlock(level, mutable, state)) {
                  artificial++;
                  break;
               }
            }
         }
      }

      return PadMapViewProfilePolicy.isIndoorEvidence(ceiling, artificialCeiling, artificial, this.ceilingMinHits, this.artificialMinHits)
         ? PadMapViewProfile.INDOOR
         : PadMapViewProfile.OUTDOOR;
   }

   int normalizeIndoorFloorY(Level level, BlockPos playerPos) {
      MutableBlockPos mutable = new MutableBlockPos();

      for (int dy = 0; dy >= -2; dy--) {
         int y = playerPos.getY() + dy;
         if (y - 1 >= level.getMinBuildHeight()) {
            mutable.set(playerPos.getX(), y, playerPos.getZ());
            BlockState feet = level.getBlockState(mutable);
            boolean feetOpen = feet.isAir() || feet.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y + 1, playerPos.getZ());
            BlockState head = level.getBlockState(mutable);
            boolean headOpen = head.isAir() || head.getCollisionShape(level, mutable).isEmpty();
            mutable.set(playerPos.getX(), y - 1, playerPos.getZ());
            BlockState below = level.getBlockState(mutable);
            if (feetOpen && headOpen && !below.getCollisionShape(level, mutable).isEmpty()) {
               return y;
            }
         }
      }

      return playerPos.getY();
   }

   int outdoorLayerY() {
      return Integer.MIN_VALUE;
   }

   private static boolean isArtificialProfileBlock(Level level, MutableBlockPos mutable, BlockState state) {
      return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(level, mutable).isEmpty()
         ? !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !PadMapSampler.isNaturalTerrain(state)
         : false;
   }
}
