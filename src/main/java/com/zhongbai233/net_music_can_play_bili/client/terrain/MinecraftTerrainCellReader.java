package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionCaptureJob;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public final class MinecraftTerrainCellReader implements TerrainSectionCaptureJob.TerrainCellReader {
   private final ClientLevel level;
   private final Thread ownerThread;
   private final MutableBlockPos cursor = new MutableBlockPos();

   public MinecraftTerrainCellReader(ClientLevel level) {
      this.level = Objects.requireNonNull(level, "level");
      this.ownerThread = Thread.currentThread();
   }

   @Override
   public TerrainCellSample read(int blockX, int blockY, int blockZ) {
      this.requireOwnerThread();
      if (blockY >= this.level.getMinBuildHeight()
         && blockY < this.level.getMaxBuildHeight()
         && this.level.hasChunk(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))) {
         try {
            this.cursor.set(blockX, blockY, blockZ);
            BlockState state = this.level.getBlockState(this.cursor);
            FluidState fluid = state.getFluidState();
            String fluidId = fluid.isEmpty() ? "" : BuiltInRegistries.FLUID.getKey(fluid.getType()).toString();
            if (state.isAir() && fluid.isEmpty()) {
               return TerrainCellSample.air();
            } else {
               RenderShape renderShape = state.getRenderShape();

               TerrainCellSample.RenderCategory category = switch (renderShape) {
                  case MODEL -> TerrainCellSample.RenderCategory.MODEL;
                  case ENTITYBLOCK_ANIMATED -> TerrainCellSample.RenderCategory.ENTITY_ANIMATED;
                  default -> TerrainCellSample.RenderCategory.INVISIBLE;
               };
               boolean hasBlockEntity = state.hasBlockEntity();
               return new TerrainCellSample(
                  TerrainCellSample.Availability.LOADED,
                  category,
                  BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                  fluidId,
                  hasBlockEntity,
                  hasBlockEntity || category == TerrainCellSample.RenderCategory.ENTITY_ANIMATED
               );
            }
         } catch (Throwable var10) {
            if (var10 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               return TerrainCellSample.unknown();
            }
         }
      } else {
         return TerrainCellSample.unknown();
      }
   }

   private void requireOwnerThread() {
      if (Thread.currentThread() != this.ownerThread) {
         throw new IllegalStateException("Minecraft terrain cells must be sampled on the owning client thread");
      }
   }
}
