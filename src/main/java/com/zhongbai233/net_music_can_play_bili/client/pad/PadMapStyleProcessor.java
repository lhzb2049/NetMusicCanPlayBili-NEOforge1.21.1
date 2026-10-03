package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.Arrays;

public final class PadMapStyleProcessor {
   private PadMapStyleProcessor.Workspace workspace;

   public PadMapStyleProcessor.StyledMap style(PadMapSnapshot map) {
      int width = map.width();
      int height = map.height();
      PadMapStyleProcessor.Workspace workspace = this.workspace(width, height);
      workspace.clearInputs();

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            PadMapTileKind kind = map.tile(width - 1 - x, z);
            int index = this.index(width, x, z);
            switch (kind) {
               case TREE:
                  workspace.tree[index] = true;
                  break;
               case FARMLAND:
                  workspace.farmland[index] = true;
                  break;
               case INDOOR_FLOOR:
                  workspace.indoorFloor[index] = true;
                  break;
               case BUILDING:
                  workspace.building[index] = true;
                  break;
               case WATER:
                  workspace.water[index] = true;
            }
         }
      }

      this.largeComponents(workspace.tree, workspace.component, workspace.visited, workspace.queue, width, height, 10);
      this.softenArea(workspace.component, workspace.greenArea, width, height, 2, 9);
      this.largeComponents(workspace.farmland, workspace.component, workspace.visited, workspace.queue, width, height, 8);
      this.softenArea(workspace.component, workspace.farmlandArea, width, height, 1, 4);
      this.largeComponents(workspace.water, workspace.component, workspace.visited, workspace.queue, width, height, 18);
      this.softenArea(workspace.component, workspace.waterArea, width, height, 1, 5);
      this.subtract(workspace.water, workspace.waterArea, workspace.waterLine);
      boolean indoorMap = this.count(workspace.indoorFloor) >= 8;
      this.largeComponents(workspace.building, workspace.buildingFootprint, workspace.visited, workspace.queue, width, height, indoorMap ? 2 : 4);
      if (indoorMap) {
         this.fillSmallInteriorGaps(workspace.buildingFootprint, width, height);
         this.outlineWithSource(workspace.buildingFootprint, workspace.buildingZone, width, height);
         this.buildingCore(workspace.buildingFootprint, workspace.buildingCore, width, height, 4);
      } else {
         this.fillSingleCellHoles(workspace.buildingFootprint, width, height);
         this.fillEnclosedHoles(workspace.buildingFootprint, workspace.visited, workspace.queue, width, height, Math.max(24, width * height / 18));
         this.copy(workspace.buildingFootprint, workspace.buildingZone);
         this.buildingCore(workspace.buildingFootprint, workspace.buildingCore, width, height, 6);
      }

      return new PadMapStyleProcessor.StyledMap(
         width,
         height,
         (boolean[])workspace.greenArea.clone(),
         (boolean[])workspace.farmlandArea.clone(),
         (boolean[])workspace.waterArea.clone(),
         (boolean[])workspace.waterLine.clone(),
         (boolean[])workspace.buildingZone.clone(),
         (boolean[])workspace.buildingCore.clone(),
         (boolean[])workspace.indoorFloor.clone()
      );
   }

   private PadMapStyleProcessor.Workspace workspace(int width, int height) {
      int size = width * height;
      if (this.workspace == null || this.workspace.size != size) {
         this.workspace = new PadMapStyleProcessor.Workspace(width, height);
      }

      return this.workspace;
   }

   private void largeComponents(boolean[] source, boolean[] output, boolean[] visited, int[] queue, int width, int height, int minArea) {
      Arrays.fill(output, false);
      Arrays.fill(visited, false);

      for (int start = 0; start < source.length; start++) {
         if (source[start] && !visited[start]) {
            int head = 0;
            int tail = 0;
            visited[start] = true;
            queue[tail++] = start;

            while (head < tail) {
               int index = queue[head++];
               int x = index % width;
               int z = index / width;

               for (int dz = -1; dz <= 1; dz++) {
                  for (int dx = -1; dx <= 1; dx++) {
                     if (Math.abs(dx) + Math.abs(dz) == 1) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx >= 0 && nx < width && nz >= 0 && nz < height) {
                           int neighbor = this.index(width, nx, nz);
                           if (source[neighbor] && !visited[neighbor]) {
                              visited[neighbor] = true;
                              queue[tail++] = neighbor;
                           }
                        }
                     }
                  }
               }
            }

            if (tail >= minArea) {
               for (int i = 0; i < tail; i++) {
                  output[queue[i]] = true;
               }
            }
         }
      }
   }

   private void softenArea(boolean[] source, boolean[] output, int width, int height, int radius, int threshold) {
      System.arraycopy(source, 0, output, 0, source.length);
      this.buildSummedArea(source, this.workspace.summedArea, width, height);

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (!source[this.index(width, x, z)] && this.countInRadius(this.workspace.summedArea, width, height, x, z, radius) >= threshold) {
               output[this.index(width, x, z)] = true;
            }
         }
      }
   }

   private void outlineWithSource(boolean[] source, boolean[] output, int width, int height) {
      System.arraycopy(source, 0, output, 0, source.length);
      this.buildSummedArea(source, this.workspace.summedArea, width, height);

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            int index = this.index(width, x, z);
            if (!source[index] && this.countInRadius(this.workspace.summedArea, width, height, x, z, 1) > 0) {
               output[index] = true;
            }
         }
      }
   }

   private void buildingCore(boolean[] source, boolean[] output, int width, int height, int threshold) {
      Arrays.fill(output, false);
      this.buildSummedArea(source, this.workspace.summedArea, width, height);

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            int index = this.index(width, x, z);
            if (source[index] && this.countInRadius(this.workspace.summedArea, width, height, x, z, 1) >= threshold) {
               output[index] = true;
            }
         }
      }
   }

   private void copy(boolean[] source, boolean[] output) {
      System.arraycopy(source, 0, output, 0, source.length);
   }

   private int count(boolean[] mask) {
      int count = 0;

      for (boolean value : mask) {
         if (value) {
            count++;
         }
      }

      return count;
   }

   private void fillSmallInteriorGaps(boolean[] mask, int width, int height) {
      this.buildSummedArea(mask, this.workspace.summedArea, width, height);

      for (int z = 1; z < height - 1; z++) {
         for (int x = 1; x < width - 1; x++) {
            int index = this.index(width, x, z);
            if (!mask[index]) {
               int cardinal = 0;
               if (mask[this.index(width, x - 1, z)]) {
                  cardinal++;
               }

               if (mask[this.index(width, x + 1, z)]) {
                  cardinal++;
               }

               if (mask[this.index(width, x, z - 1)]) {
                  cardinal++;
               }

               if (mask[this.index(width, x, z + 1)]) {
                  cardinal++;
               }

               if (cardinal >= 3 || cardinal >= 2 && this.countInRadius(this.workspace.summedArea, width, height, x, z, 1) >= 5) {
                  mask[index] = true;
               }
            }
         }
      }
   }

   private void fillSingleCellHoles(boolean[] mask, int width, int height) {
      for (int z = 1; z < height - 1; z++) {
         for (int x = 1; x < width - 1; x++) {
            int index = this.index(width, x, z);
            if (!mask[index]
               && mask[this.index(width, x - 1, z)]
               && mask[this.index(width, x + 1, z)]
               && mask[this.index(width, x, z - 1)]
               && mask[this.index(width, x, z + 1)]) {
               mask[index] = true;
            }
         }
      }
   }

   private void fillEnclosedHoles(boolean[] mask, boolean[] visited, int[] queue, int width, int height, int maxArea) {
      Arrays.fill(visited, false);

      for (int z = 0; z < height; z++) {
         this.floodOpenArea(mask, visited, queue, width, height, 0, z);
         this.floodOpenArea(mask, visited, queue, width, height, width - 1, z);
      }

      for (int x = 0; x < width; x++) {
         this.floodOpenArea(mask, visited, queue, width, height, x, 0);
         this.floodOpenArea(mask, visited, queue, width, height, x, height - 1);
      }

      for (int start = 0; start < mask.length; start++) {
         if (!mask[start] && !visited[start]) {
            int head = 0;
            int tail = 0;
            boolean touchesEdge = false;
            visited[start] = true;
            queue[tail++] = start;

            while (head < tail) {
               int index = queue[head++];
               int x = index % width;
               int z = index / width;
               if (x == 0 || x == width - 1 || z == 0 || z == height - 1) {
                  touchesEdge = true;
               }

               for (int dz = -1; dz <= 1; dz++) {
                  for (int dx = -1; dx <= 1; dx++) {
                     if (Math.abs(dx) + Math.abs(dz) == 1) {
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx >= 0 && nx < width && nz >= 0 && nz < height) {
                           int neighbor = this.index(width, nx, nz);
                           if (!mask[neighbor] && !visited[neighbor]) {
                              visited[neighbor] = true;
                              queue[tail++] = neighbor;
                           }
                        }
                     }
                  }
               }
            }

            if (!touchesEdge && tail <= maxArea && tail <= this.maxEnclosedHoleSpan(width, height) && this.hasBuildingRing(mask, queue, tail, width, height)) {
               for (int i = 0; i < tail; i++) {
                  mask[queue[i]] = true;
               }
            }
         }
      }
   }

   private int maxEnclosedHoleSpan(int width, int height) {
      int smallestSide = Math.min(width, height);
      return Math.max(9, smallestSide / 6);
   }

   private void floodOpenArea(boolean[] mask, boolean[] visited, int[] queue, int width, int height, int x, int z) {
      int start = this.index(width, x, z);
      if (!mask[start] && !visited[start]) {
         int head = 0;
         int tail = 0;
         visited[start] = true;
         queue[tail++] = start;

         while (head < tail) {
            int index = queue[head++];
            int cx = index % width;
            int cz = index / width;

            for (int dz = -1; dz <= 1; dz++) {
               for (int dx = -1; dx <= 1; dx++) {
                  if (Math.abs(dx) + Math.abs(dz) == 1) {
                     int nx = cx + dx;
                     int nz = cz + dz;
                     if (nx >= 0 && nx < width && nz >= 0 && nz < height) {
                        int neighbor = this.index(width, nx, nz);
                        if (!mask[neighbor] && !visited[neighbor]) {
                           visited[neighbor] = true;
                           queue[tail++] = neighbor;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private boolean hasBuildingRing(boolean[] mask, int[] component, int length, int width, int height) {
      int adjacent = 0;
      int required = Math.max(4, Math.min(48, length / 2));

      for (int i = 0; i < length; i++) {
         int index = component[i];
         int x = index % width;
         int z = index / width;
         if (this.hasAdjacentBuilding(mask, width, height, x, z)) {
            if (++adjacent >= required) {
               return true;
            }
         }
      }

      return adjacent >= required;
   }

   private boolean hasAdjacentBuilding(boolean[] mask, int width, int height, int x, int z) {
      if (x > 0 && mask[this.index(width, x - 1, z)]) {
         return true;
      } else if (x + 1 < width && mask[this.index(width, x + 1, z)]) {
         return true;
      } else {
         return z > 0 && mask[this.index(width, x, z - 1)] ? true : z + 1 < height && mask[this.index(width, x, z + 1)];
      }
   }

   private void subtract(boolean[] source, boolean[] remove, boolean[] output) {
      for (int i = 0; i < source.length; i++) {
         output[i] = source[i] && !remove[i];
      }
   }

   private void buildSummedArea(boolean[] mask, int[] summedArea, int width, int height) {
      int stride = width + 1;
      Arrays.fill(summedArea, 0);

      for (int z = 0; z < height; z++) {
         int rowSum = 0;
         int sourceRow = z * width;
         int outputRow = (z + 1) * stride;
         int previousRow = z * stride;

         for (int x = 0; x < width; x++) {
            if (mask[sourceRow + x]) {
               rowSum++;
            }

            summedArea[outputRow + x + 1] = summedArea[previousRow + x + 1] + rowSum;
         }
      }
   }

   private int countInRadius(int[] summedArea, int width, int height, int x, int z, int radius) {
      int stride = width + 1;
      int minX = Math.max(0, x - radius);
      int maxX = Math.min(width, x + radius + 1);
      int minZ = Math.max(0, z - radius);
      int maxZ = Math.min(height, z + radius + 1);
      return summedArea[maxZ * stride + maxX] - summedArea[minZ * stride + maxX] - summedArea[maxZ * stride + minX] + summedArea[minZ * stride + minX];
   }

   private int index(int width, int x, int z) {
      return z * width + x;
   }

   public record StyledMap(
      int width,
      int height,
      boolean[] greenArea,
      boolean[] farmlandArea,
      boolean[] waterArea,
      boolean[] waterLine,
      boolean[] buildingZone,
      boolean[] buildingCore,
      boolean[] indoorFloor
   ) {
   }

   private static final class Workspace {
      final int size;
      final boolean[] tree;
      final boolean[] farmland;
      final boolean[] indoorFloor;
      final boolean[] building;
      final boolean[] water;
      final boolean[] component;
      final boolean[] visited;
      final boolean[] greenArea;
      final boolean[] farmlandArea;
      final boolean[] waterArea;
      final boolean[] waterLine;
      final boolean[] buildingZone;
      final boolean[] buildingFootprint;
      final boolean[] buildingCore;
      final int[] queue;
      final int[] summedArea;

      Workspace(int width, int height) {
         int size = width * height;
         this.size = size;
         this.tree = new boolean[size];
         this.farmland = new boolean[size];
         this.indoorFloor = new boolean[size];
         this.building = new boolean[size];
         this.water = new boolean[size];
         this.component = new boolean[size];
         this.visited = new boolean[size];
         this.greenArea = new boolean[size];
         this.farmlandArea = new boolean[size];
         this.waterArea = new boolean[size];
         this.waterLine = new boolean[size];
         this.buildingZone = new boolean[size];
         this.buildingFootprint = new boolean[size];
         this.buildingCore = new boolean[size];
         this.queue = new int[size];
         this.summedArea = new int[(width + 1) * (height + 1)];
      }

      void clearInputs() {
         Arrays.fill(this.tree, false);
         Arrays.fill(this.farmland, false);
         Arrays.fill(this.indoorFloor, false);
         Arrays.fill(this.building, false);
         Arrays.fill(this.water, false);
      }
   }
}
