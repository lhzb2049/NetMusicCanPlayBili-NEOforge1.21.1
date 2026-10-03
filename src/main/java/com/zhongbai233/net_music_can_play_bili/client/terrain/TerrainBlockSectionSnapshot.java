package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record TerrainBlockSectionSnapshot(
   TerrainSectionKey section,
   List<TerrainBlockSectionSnapshot.VisibleBlock> blocks,
   List<BlockState> neighborhoodStates,
   byte[] neighborhoodLight,
   long estimatedBytes
) {
   public TerrainBlockSectionSnapshot(TerrainSectionKey section, List<TerrainBlockSectionSnapshot.VisibleBlock> blocks, long estimatedBytes) {
      this(section, blocks, List.of(), new byte[0], estimatedBytes);
   }

   public TerrainBlockSectionSnapshot(
      TerrainSectionKey section, List<TerrainBlockSectionSnapshot.VisibleBlock> blocks, List<BlockState> neighborhoodStates, long estimatedBytes
   ) {
      this(section, blocks, neighborhoodStates, new byte[0], estimatedBytes);
   }

   public TerrainBlockSectionSnapshot(
      TerrainSectionKey section,
      List<TerrainBlockSectionSnapshot.VisibleBlock> blocks,
      List<BlockState> neighborhoodStates,
      byte[] neighborhoodLight,
      long estimatedBytes
   ) {
      Objects.requireNonNull(section, "section");
      blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
      neighborhoodStates = List.copyOf(Objects.requireNonNull(neighborhoodStates, "neighborhoodStates"));
      neighborhoodLight = (byte[])Objects.requireNonNull(neighborhoodLight, "neighborhoodLight").clone();
      if (!neighborhoodStates.isEmpty() && neighborhoodStates.size() != 8000) {
         throw new IllegalArgumentException("neighborhoodStates must be empty or contain exactly 20^3 states");
      } else if (neighborhoodLight.length != 0 && neighborhoodLight.length != 8000) {
         throw new IllegalArgumentException("neighborhoodLight must be empty or contain exactly 20^3 values");
      } else if (estimatedBytes < 0L) {
         throw new IllegalArgumentException("estimatedBytes must be non-negative");
      } else {
         this.section = section;
         this.blocks = blocks;
         this.neighborhoodStates = neighborhoodStates;
         this.neighborhoodLight = neighborhoodLight;
         this.estimatedBytes = estimatedBytes;
      }
   }

   public BlockState neighborhoodState(int localX, int localY, int localZ) {
      return !this.neighborhoodStates.isEmpty() && TerrainNeighborhoodIndex.contains(localX, localY, localZ)
         ? this.neighborhoodStates.get(TerrainNeighborhoodIndex.index(localX, localY, localZ))
         : Blocks.AIR.defaultBlockState();
   }

   public byte[] neighborhoodLight() {
      return (byte[])this.neighborhoodLight.clone();
   }

   public byte neighborhoodLight(int localX, int localY, int localZ) {
      return this.neighborhoodLight.length != 0 && TerrainNeighborhoodIndex.contains(localX, localY, localZ)
         ? this.neighborhoodLight[TerrainNeighborhoodIndex.index(localX, localY, localZ)]
         : 0;
   }

   public record VisibleBlock(
      int localX, int localY, int localZ, int cellSize, BlockState state, TerrainTintColors tintColors, List<Integer> tintLayers, byte packedLight
   ) {
      public VisibleBlock(int localX, int localY, int localZ, BlockState state) {
         this(localX, localY, localZ, 1, state, TerrainTintColors.UNTINTED, List.of(), (byte)0);
      }

      public VisibleBlock(int localX, int localY, int localZ, BlockState state, TerrainTintColors tintColors) {
         this(localX, localY, localZ, 1, state, tintColors, List.of(), (byte)0);
      }

      public VisibleBlock(
         int localX, int localY, int localZ, int cellSize, BlockState state, TerrainTintColors tintColors, List<Integer> tintLayers, byte packedLight
      ) {
         if (cellSize > 0
            && 16 % cellSize == 0
            && localX >= 0
            && localY >= 0
            && localZ >= 0
            && localX + cellSize <= 16
            && localY + cellSize <= 16
            && localZ + cellSize <= 16) {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(tintColors, "tintColors");
            tintLayers = List.copyOf(Objects.requireNonNull(tintLayers, "tintLayers"));
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.cellSize = cellSize;
            this.state = state;
            this.tintColors = tintColors;
            this.tintLayers = tintLayers;
            this.packedLight = packedLight;
         } else {
            throw new IllegalArgumentException("visible terrain cell must fit inside its section");
         }
      }
   }
}
