package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

final class TerrainPreviewBlockAndTintGetter implements BlockAndTintGetter {
   private static final int SIZE = 16;
   private static final int CELL_COUNT = 4096;
   private final int[] grassColors = tintArray();
   private final int[] foliageColors = tintArray();
   private final int[] waterColors = tintArray();
   private final Map<Integer, List<Integer>> tintLayers = new HashMap<>();
   private final TerrainBlockSectionSnapshot snapshot;
   private final LevelLightEngine lightEngine = new LevelLightEngine(new LightChunkGetter() {
      @Nullable
      public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
         return null;
      }

      public BlockGetter getLevel() {
         return TerrainPreviewBlockAndTintGetter.this;
      }
   }, true, true);

   TerrainPreviewBlockAndTintGetter(TerrainBlockSectionSnapshot snapshot) {
      this.snapshot = Objects.requireNonNull(snapshot, "snapshot");

      for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
         int index = index(block.localX(), block.localY(), block.localZ());
         this.grassColors[index] = block.tintColors().color(TerrainTintColors.TintType.GRASS);
         this.foliageColors[index] = block.tintColors().color(TerrainTintColors.TintType.FOLIAGE);
         this.waterColors[index] = block.tintColors().color(TerrainTintColors.TintType.WATER);
         if (!block.tintLayers().isEmpty()) {
            this.tintLayers.put(index, block.tintLayers());
         }
      }
   }

   public LevelLightEngine getLightEngine() {
      return this.lightEngine;
   }

   public int getBrightness(LightLayer layer, BlockPos pos) {
      int localX = pos.getX() - this.snapshot.section().minBlockX();
      int localY = pos.getY() - this.snapshot.section().minBlockY();
      int localZ = pos.getZ() - this.snapshot.section().minBlockZ();
      byte packed = this.snapshot.neighborhoodLight(localX, localY, localZ);
      return layer == LightLayer.SKY ? TerrainPackedLight.sky(packed) : TerrainPackedLight.block(packed);
   }

   public int getBlockTint(BlockPos pos, ColorResolver resolver) {
      int localX = pos.getX() - this.snapshot.section().minBlockX();
      int localY = pos.getY() - this.snapshot.section().minBlockY();
      int localZ = pos.getZ() - this.snapshot.section().minBlockZ();
      if (!inside(localX, localY, localZ)) {
         return -1;
      } else {
         int index = index(localX, localY, localZ);
         if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return this.grassColors[index];
         } else if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return this.foliageColors[index];
         } else {
            return resolver == BiomeColors.WATER_COLOR_RESOLVER ? this.waterColors[index] : -1;
         }
      }
   }

   int precomputedTint(BlockPos pos, int layer) {
      int localX = pos.getX() - this.snapshot.section().minBlockX();
      int localY = pos.getY() - this.snapshot.section().minBlockY();
      int localZ = pos.getZ() - this.snapshot.section().minBlockZ();
      if (inside(localX, localY, localZ) && layer >= 0) {
         List<Integer> colors = this.tintLayers.get(index(localX, localY, localZ));
         return colors != null && layer < colors.size() ? colors.get(layer) : -1;
      } else {
         return -1;
      }
   }

   @Nullable
   public BlockEntity getBlockEntity(BlockPos pos) {
      return null;
   }

   public BlockState getBlockState(BlockPos pos) {
      int localX = pos.getX() - this.snapshot.section().minBlockX();
      int localY = pos.getY() - this.snapshot.section().minBlockY();
      int localZ = pos.getZ() - this.snapshot.section().minBlockZ();
      return TerrainNeighborhoodIndex.contains(localX, localY, localZ)
         ? this.snapshot.neighborhoodState(localX, localY, localZ)
         : Blocks.AIR.defaultBlockState();
   }

   public FluidState getFluidState(BlockPos pos) {
      return this.getBlockState(pos).getFluidState();
   }

   public int getHeight() {
      return 20;
   }

   public int getMinBuildHeight() {
      return this.snapshot.section().minBlockY() + -2;
   }

   public float getShade(Direction direction, boolean shaded) {
      return shaded ? 0.8F : 1.0F;
   }

   private static int[] tintArray() {
      int[] colors = new int[4096];
      Arrays.fill(colors, -1);
      return colors;
   }

   private static boolean inside(int x, int y, int z) {
      return x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16;
   }

   private static int index(int x, int y, int z) {
      return (y * 16 + z) * 16 + x;
   }
}
