package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class VideoScreenOcclusion {
   private VideoScreenOcclusion() {
   }

   static boolean isOccluded(BlockGetter level, Vec3 cameraPos, Vec3 target, BlockPos ignoredSourcePos) {
      return level != null && cameraPos != null && target != null ? (Boolean)BlockGetter.traverseBlocks(cameraPos, target, level, (world, pos) -> {
         if (pos.equals(ignoredSourcePos)) {
            return null;
         } else {
            return blocksView(world, pos, world.getBlockState(pos)) ? Boolean.TRUE : null;
         }
      }, world -> Boolean.FALSE) : false;
   }

   static boolean blocksView(BlockGetter level, BlockPos pos, BlockState state) {
      return state == null
         ? false
         : blocksView(state.canOcclude(), state.isSolidRender(level, pos), Block.isShapeFullBlock(state.getOcclusionShape(level, pos)));
   }

   static boolean blocksView(boolean canOcclude, boolean solidRender, boolean fullOcclusionShape) {
      return canOcclude && solidRender && fullOcclusionShape;
   }
}
