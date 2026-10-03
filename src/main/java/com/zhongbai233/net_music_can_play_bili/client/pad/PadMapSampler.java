package com.zhongbai233.net_music_can_play_bili.client.pad;

import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PadMapSampler {
   private static final PadMapProperties.Layout PROPERTIES = PadMapProperties.layout();
   public static final int DEFAULT_VIEW_WIDTH = PROPERTIES.viewWidth();
   public static final int DEFAULT_VIEW_HEIGHT = PROPERTIES.viewHeight();
   public static final int DEFAULT_OVERSCAN = PROPERTIES.overscan();
   public static final int DEFAULT_WIDTH = PROPERTIES.width();
   public static final int DEFAULT_HEIGHT = PROPERTIES.height();
   public static final int DEFAULT_SIZE = DEFAULT_WIDTH;
   private static final int CELL_SAMPLES = PROPERTIES.cellSamples();
   private static final int INTERIOR_SCAN_DEPTH = 12;
   private static final int CEILING_SCAN_HEIGHT = 32;
   private static final int[] STAND_OFFSETS = new int[]{0, 1, -1, 2, -2, 3};

   private PadMapSampler() {
   }

   public static PadMapSnapshot sample(Level level, int centerX, int centerZ, float zoom) {
      int width = DEFAULT_WIDTH;
      int height = DEFAULT_HEIGHT;
      int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
      PadMapTileKind[] tiles = new PadMapTileKind[width * height];
      int halfW = width / 2;
      int halfH = height / 2;
      MutableBlockPos mutable = new MutableBlockPos();

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            int worldX = centerX + (x - halfW) * cellSize;
            int worldZ = centerZ + (z - halfH) * cellSize;
            tiles[z * width + x] = classifyCell(level, mutable, worldX, worldZ, cellSize);
         }
      }

      smoothTreeCanopy(width, height, tiles);
      return new PadMapSnapshot(centerX, centerZ, cellSize, width, height, tiles);
   }

   public static PadMapTileKind classifyInteriorCell(Level level, MutableBlockPos mutable, int worldX, int worldZ, int floorY, int cellSize) {
      if (!isCellReady(level, worldX, worldZ, cellSize)) {
         return PadMapTileKind.UNKNOWN;
      } else {
         int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));
         int floor = 0;
         int wall = 0;
         int water = 0;
         int outdoor = 0;
         int loadedSamples = 0;

         for (int sample = 0; sample < samples; sample++) {
            int sx = worldX + sampleOffset(sample, cellSize, true);
            int sz = worldZ + sampleOffset(sample, cellSize, false);
            if (level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
               loadedSamples++;
               PadMapTileKind kind = classifyInteriorColumn(level, mutable, sx, sz, floorY);
               if (kind == PadMapTileKind.WATER) {
                  water++;
               } else if (kind == PadMapTileKind.BUILDING) {
                  wall++;
               } else if (kind == PadMapTileKind.INDOOR_FLOOR) {
                  floor++;
               } else {
                  outdoor++;
               }
            }
         }

         if (loadedSamples == 0) {
            return PadMapTileKind.UNKNOWN;
         } else if (water > 0 && water >= floor + wall) {
            return PadMapTileKind.WATER;
         } else if (wall > floor && wall >= outdoor) {
            return PadMapTileKind.BUILDING;
         } else {
            return floor > 0 && floor >= outdoor ? PadMapTileKind.INDOOR_FLOOR : PadMapTileKind.GRASS;
         }
      }
   }

   public static PadMapTileKind classifyCell(Level level, MutableBlockPos mutable, int worldX, int worldZ, int cellSize) {
      if (!isCellReady(level, worldX, worldZ, cellSize)) {
         return PadMapTileKind.UNKNOWN;
      } else {
         int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));
         int grass = 0;
         int building = 0;
         int water = 0;
         int tree = 0;
         int farmland = 0;
         int rock = 0;
         int snow = 0;
         int loadedSamples = 0;

         for (int sample = 0; sample < samples; sample++) {
            int sx = worldX + sampleOffset(sample, cellSize, true);
            int sz = worldZ + sampleOffset(sample, cellSize, false);
            if (level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
               loadedSamples++;
               PadMapTileKind kind = classifyColumn(level, mutable, sx, sz);
               if (kind == PadMapTileKind.UNKNOWN) {
                  return PadMapTileKind.UNKNOWN;
               }

               if (kind == PadMapTileKind.GRASS) {
                  grass++;
               } else if (kind == PadMapTileKind.BUILDING) {
                  building++;
               } else if (kind == PadMapTileKind.WATER) {
                  water++;
               } else if (kind == PadMapTileKind.TREE) {
                  tree++;
               } else if (kind == PadMapTileKind.FARMLAND) {
                  farmland++;
               } else if (kind == PadMapTileKind.SNOW) {
                  snow++;
               } else {
                  rock++;
               }
            }
         }

         if (loadedSamples == 0) {
            return PadMapTileKind.UNKNOWN;
         } else if (water > 0 && water >= Math.max(1, loadedSamples / 3) && water >= building) {
            return PadMapTileKind.WATER;
         } else if (building >= Math.max(1, loadedSamples / 2) || building > grass + tree + farmland + rock) {
            return PadMapTileKind.BUILDING;
         } else if (tree >= Math.max(1, loadedSamples / 3) || tree > grass + farmland) {
            return PadMapTileKind.TREE;
         } else if (farmland >= Math.max(1, loadedSamples / 3)) {
            return PadMapTileKind.FARMLAND;
         } else if (snow > 0) {
            return PadMapTileKind.SNOW;
         } else if (grass > 0 && rock == 0) {
            return PadMapTileKind.GRASS;
         } else {
            return grass >= rock ? PadMapTileKind.GRASS : PadMapTileKind.ROCK;
         }
      }
   }

   static boolean isCellReady(Level level, int worldX, int worldZ, int cellSize) {
      int samples = Math.max(1, Math.min(CELL_SAMPLES, cellSize * cellSize));

      for (int sample = 0; sample < samples; sample++) {
         int sx = worldX + sampleOffset(sample, cellSize, true);
         int sz = worldZ + sampleOffset(sample, cellSize, false);
         if (!level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
            return false;
         }
      }

      return true;
   }

   private static int sampleOffset(int sample, int cellSize, boolean xAxis) {
      if (cellSize <= 1) {
         return 0;
      } else {
         int max = cellSize - 1;
         int mid = cellSize / 2;

         return switch (sample) {
            case 0 -> mid;
            case 1 -> 0;
            case 2 -> xAxis ? max : 0;
            case 3 -> xAxis ? 0 : max;
            case 4 -> xAxis ? max : max;
            default -> max;
         };
      }
   }

   private static PadMapTileKind classifyColumn(Level level, MutableBlockPos mutable, int x, int z) {
      int y = level.getHeight(Types.WORLD_SURFACE, x, z) - 1;
      if (!PadMapSamplingPolicy.isSurfaceHeightReady(level.getMinBuildHeight(), y)) {
         return PadMapTileKind.UNKNOWN;
      } else {
         mutable.set(x, y, z);
         BlockState top = level.getBlockState(mutable);

         for (int descend = 0;
            descend < 6 && y > level.getMinBuildHeight() && top.getFluidState().isEmpty() && top.getCollisionShape(level, mutable).isEmpty();
            top = level.getBlockState(mutable)
         ) {
            PadMapTileKind soft = classifySoftCover(top);
            if (soft != null) {
               return soft;
            }

            y--;
            descend++;
            mutable.set(x, y, z);
         }

         if (isWaterLike(top)) {
            return PadMapTileKind.WATER;
         } else if (top.is(BlockTags.LEAVES) || isMushroomCap(top)) {
            return PadMapTileKind.TREE;
         } else if (top.is(BlockTags.LOGS)) {
            return hasLeavesNearby(level, mutable, x, y, z) ? PadMapTileKind.TREE : PadMapTileKind.BUILDING;
         } else if (isFarmlandLike(top)) {
            return PadMapTileKind.FARMLAND;
         } else if (isNaturalTerrain(top)) {
            return naturalGroundKind(top);
         } else if (hasInteriorSpaceBelow(level, mutable, x, y, z)) {
            return PadMapTileKind.BUILDING;
         } else {
            return isElevatedAboveSurroundings(level, mutable, x, y, z) ? PadMapTileKind.BUILDING : PadMapTileKind.GRASS;
         }
      }
   }

   private static PadMapTileKind classifySoftCover(BlockState state) {
      if (state.is(BlockTags.CROPS)) {
         return PadMapTileKind.FARMLAND;
      } else {
         return state.is(BlockTags.SNOW) ? PadMapTileKind.SNOW : null;
      }
   }

   private static boolean hasInteriorSpaceBelow(Level level, MutableBlockPos mutable, int x, int y, int z) {
      int airRun = 0;
      int naturalRun = 0;

      for (int dy = 1; dy <= 12; dy++) {
         int by = y - dy;
         if (by < level.getMinBuildHeight()) {
            return false;
         }

         mutable.set(x, by, z);
         BlockState state = level.getBlockState(mutable);
         if (state.isAir()) {
            naturalRun = 0;
            if (++airRun >= 2) {
               return true;
            }
         } else {
            airRun = 0;
            if (isNaturalTerrain(state)) {
               if (++naturalRun >= 3) {
                  return false;
               }
            } else {
               naturalRun = 0;
            }
         }
      }

      return false;
   }

   private static boolean isElevatedAboveSurroundings(Level level, MutableBlockPos mutable, int x, int y, int z) {
      int drops = 0;

      for (int dz = -2; dz <= 2; dz += 2) {
         for (int dx = -2; dx <= 2; dx += 2) {
            if (dx != 0 || dz != 0) {
               int sx = x + dx;
               int sz = z + dz;
               if (level.hasChunk(Math.floorDiv(sx, 16), Math.floorDiv(sz, 16))) {
                  int surface = level.getHeight(Types.WORLD_SURFACE, sx, sz) - 1;
                  if (y - surface >= 3) {
                     if (++drops >= 2) {
                        return true;
                     }
                  }
               }
            }
         }
      }

      return false;
   }

   private static boolean hasLeavesNearby(Level level, MutableBlockPos mutable, int x, int y, int z) {
      int leaves = 0;

      for (int dy = 0; dy <= 3; dy++) {
         for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
               mutable.set(x + dx, y + dy, z + dz);
               if (level.getBlockState(mutable).is(BlockTags.LEAVES)) {
                  if (++leaves >= 2) {
                     return true;
                  }
               }
            }
         }
      }

      return false;
   }

   private static boolean isMushroomCap(BlockState state) {
      return state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.MUSHROOM_STEM);
   }

   private static PadMapTileKind classifyInteriorColumn(Level level, MutableBlockPos mutable, int x, int z, int floorY) {
      mutable.set(x, floorY, z);
      BlockState feet = level.getBlockState(mutable);
      mutable.set(x, floorY + 1, z);
      BlockState head = level.getBlockState(mutable);
      if (feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()) {
         int standY = findStandableY(level, mutable, x, z, floorY);
         if (standY != Integer.MIN_VALUE) {
            return hasArtificialCeiling(level, mutable, x, z, standY + 2) ? PadMapTileKind.INDOOR_FLOOR : PadMapTileKind.GRASS;
         } else {
            boolean feetBlocked = hasBlockingCollision(level, mutable, x, floorY, z);
            boolean headBlocked = hasBlockingCollision(level, mutable, x, floorY + 1, z);
            if (!feetBlocked || !headBlocked) {
               return PadMapTileKind.INDOOR_FLOOR;
            } else {
               return isNaturalTerrain(feet) && isNaturalTerrain(head) ? PadMapTileKind.GRASS : PadMapTileKind.BUILDING;
            }
         }
      } else {
         return PadMapTileKind.WATER;
      }
   }

   private static int findStandableY(Level level, MutableBlockPos mutable, int x, int z, int floorY) {
      for (int offset : STAND_OFFSETS) {
         int fy = floorY + offset;
         if (fy - 1 >= level.getMinBuildHeight()) {
            mutable.set(x, fy, z);
            BlockState feet = level.getBlockState(mutable);
            if (isPassableForFeet(level, mutable, feet)) {
               mutable.set(x, fy + 1, z);
               BlockState head = level.getBlockState(mutable);
               if (head.isAir() || head.getCollisionShape(level, mutable).isEmpty()) {
                  mutable.set(x, fy - 1, z);
                  BlockState below = level.getBlockState(mutable);
                  if (!below.getCollisionShape(level, mutable).isEmpty()) {
                     return fy;
                  }
               }
            }
         }
      }

      return Integer.MIN_VALUE;
   }

   private static boolean isPassableForFeet(Level level, MutableBlockPos mutable, BlockState state) {
      if (state.isAir()) {
         return true;
      } else if (!state.getFluidState().isEmpty()) {
         return false;
      } else {
         VoxelShape shape = state.getCollisionShape(level, mutable);
         return shape.isEmpty() || shape.max(Axis.Y) <= 0.51;
      }
   }

   private static boolean hasBlockingCollision(Level level, MutableBlockPos mutable, int x, int y, int z) {
      mutable.set(x, y, z);
      BlockState state = level.getBlockState(mutable);
      return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(level, mutable).isEmpty();
   }

   private static boolean hasArtificialCeiling(Level level, MutableBlockPos mutable, int x, int z, int fromY) {
      for (int dy = 0; dy <= 32; dy++) {
         int y = fromY + dy;
         mutable.set(x, y, z);
         BlockState state = level.getBlockState(mutable);
         if (!state.isAir()) {
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
               return false;
            }

            if (isNaturalTerrain(state)) {
               return false;
            }

            if (!state.getCollisionShape(level, mutable).isEmpty()) {
               return true;
            }
         }
      }

      return false;
   }

   private static boolean isWaterLike(BlockState state) {
      return !state.getFluidState().isEmpty() || state.is(Blocks.WATER) || state.is(BlockTags.ICE);
   }

   private static boolean isFarmlandLike(BlockState state) {
      return state.is(Blocks.FARMLAND) || state.is(BlockTags.CROPS) || state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN) || state.is(Blocks.HAY_BLOCK);
   }

   static boolean isNaturalTerrain(BlockState state) {
      if (state.is(BlockTags.DIRT)
         || state.is(BlockTags.SAND)
         || state.is(BlockTags.BASE_STONE_OVERWORLD)
         || state.is(BlockTags.BASE_STONE_NETHER)
         || state.is(BlockTags.NYLIUM)
         || state.is(BlockTags.SNOW)
         || state.is(BlockTags.ICE)
         || state.is(BlockTags.TERRACOTTA)) {
         return true;
      } else if (!state.is(Blocks.GRAVEL)
         && !state.is(Blocks.CLAY)
         && !state.is(Blocks.SANDSTONE)
         && !state.is(Blocks.RED_SANDSTONE)
         && !state.is(Blocks.CALCITE)
         && !state.is(Blocks.TUFF)
         && !state.is(Blocks.DRIPSTONE_BLOCK)
         && !state.is(Blocks.POINTED_DRIPSTONE)
         && !state.is(Blocks.OBSIDIAN)
         && !state.is(Blocks.MAGMA_BLOCK)
         && !state.is(Blocks.SOUL_SAND)
         && !state.is(Blocks.SOUL_SOIL)
         && !state.is(Blocks.END_STONE)
         && !state.is(Blocks.AMETHYST_BLOCK)
         && !state.is(Blocks.BUDDING_AMETHYST)
         && !state.is(Blocks.BEDROCK)
         && !state.is(Blocks.SMOOTH_BASALT)
         && !state.is(Blocks.POWDER_SNOW)
         && !state.is(Blocks.DIRT_PATH)
         && !state.is(Blocks.FARMLAND)
         && !state.is(Blocks.MYCELIUM)
         && !state.is(Blocks.TERRACOTTA)) {
         String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
         return name.endsWith("_ore") || name.startsWith("infested_");
      } else {
         return true;
      }
   }

   private static PadMapTileKind naturalGroundKind(BlockState state) {
      if (state.is(BlockTags.SNOW) || state.is(Blocks.POWDER_SNOW)) {
         return PadMapTileKind.SNOW;
      } else {
         return !state.is(BlockTags.DIRT)
               && !state.is(Blocks.DIRT_PATH)
               && !state.is(Blocks.MYCELIUM)
               && !state.is(Blocks.CLAY)
               && !state.is(BlockTags.NYLIUM)
               && !state.is(Blocks.FARMLAND)
            ? PadMapTileKind.ROCK
            : PadMapTileKind.GRASS;
      }
   }

   private static void smoothTreeCanopy(int width, int height, PadMapTileKind[] tiles) {
      PadMapTileKind[] copy = (PadMapTileKind[])tiles.clone();

      for (int z = 1; z < height - 1; z++) {
         for (int x = 1; x < width - 1; x++) {
            int index = z * width + x;
            int trees = countNeighbors(copy, width, height, x, z, PadMapTileKind.TREE);
            if (copy[index] == PadMapTileKind.TREE) {
               if (trees <= 1) {
                  tiles[index] = dominantNeighbor(copy, width, x, z);
               }
            } else if ((copy[index] == PadMapTileKind.GRASS || copy[index] == PadMapTileKind.ROCK || copy[index] == PadMapTileKind.UNKNOWN) && trees >= 4) {
               tiles[index] = PadMapTileKind.TREE;
            }
         }
      }
   }

   private static int countNeighbors(PadMapTileKind[] tiles, int width, int height, int x, int z, PadMapTileKind kind) {
      int count = 0;

      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dz != 0) {
               int nx = x + dx;
               int nz = z + dz;
               if (nx >= 0 && nx < width && nz >= 0 && nz < height && tiles[nz * width + nx] == kind) {
                  count++;
               }
            }
         }
      }

      return count;
   }

   private static PadMapTileKind dominantNeighbor(PadMapTileKind[] tiles, int size, int x, int z) {
      int grass = 0;
      int building = 0;
      int water = 0;
      int tree = 0;
      int rock = 0;

      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            PadMapTileKind kind = tiles[(z + dz) * size + x + dx];
            switch (kind) {
               case BUILDING:
                  building++;
                  break;
               case WATER:
                  water++;
                  break;
               case TREE:
                  tree++;
                  break;
               case ROCK:
                  rock++;
                  break;
               default:
                  grass++;
            }
         }
      }

      if (building >= grass && building >= water && building >= tree && building >= rock) {
         return PadMapTileKind.BUILDING;
      } else if (water >= grass && water >= tree && water >= rock) {
         return PadMapTileKind.WATER;
      } else if (tree >= grass && tree >= rock) {
         return PadMapTileKind.TREE;
      } else {
         return rock > grass ? PadMapTileKind.ROCK : PadMapTileKind.GRASS;
      }
   }
}
