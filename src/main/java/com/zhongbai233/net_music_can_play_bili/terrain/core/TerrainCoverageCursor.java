package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TerrainCoverageCursor {
   private final int minX;
   private final int minY;
   private final int minZ;
   private final int maxX;
   private final int maxY;
   private final int maxZ;
   private final int centerX;
   private final int centerZ;
   private final int maxRing;
   private int ring;
   private int columnIndex;
   private int y;
   private boolean exhausted;

   public TerrainCoverageCursor(TerrainBounds bounds, TerrainSectionKey focus) {
      Objects.requireNonNull(bounds, "bounds");
      Objects.requireNonNull(focus, "focus");
      this.minX = Math.floorDiv(bounds.minX(), 16);
      this.minY = Math.floorDiv(bounds.minY(), 16);
      this.minZ = Math.floorDiv(bounds.minZ(), 16);
      this.maxX = Math.floorDiv(bounds.maxX(), 16);
      this.maxY = Math.floorDiv(bounds.maxY(), 16);
      this.maxZ = Math.floorDiv(bounds.maxZ(), 16);
      this.centerX = Math.clamp((long)focus.x(), this.minX, this.maxX);
      this.centerZ = Math.clamp((long)focus.z(), this.minZ, this.maxZ);
      this.maxRing = Math.max(Math.max(this.centerX - this.minX, this.maxX - this.centerX), Math.max(this.centerZ - this.minZ, this.maxZ - this.centerZ));
      this.y = this.minY;
   }

   public List<TerrainSectionKey> next(int candidateBudget) {
      if (candidateBudget > 0 && !this.exhausted) {
         List<TerrainSectionKey> result = new ArrayList<>(candidateBudget);
         int checked = 0;

         while (checked < candidateBudget && !this.exhausted) {
            int[] column = column(this.ring, this.columnIndex);
            TerrainSectionKey key = new TerrainSectionKey(this.centerX + column[0], this.y, this.centerZ + column[1]);
            checked++;
            this.advance();
            if (key.x() >= this.minX && key.x() <= this.maxX && key.z() >= this.minZ && key.z() <= this.maxZ) {
               result.add(key);
            }
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   public boolean exhausted() {
      return this.exhausted;
   }

   private void advance() {
      if (this.y < this.maxY) {
         this.y++;
      } else {
         this.y = this.minY;
         this.columnIndex++;
         int columns = this.ring == 0 ? 1 : 8 * this.ring;
         if (this.columnIndex >= columns) {
            this.columnIndex = 0;
            this.ring++;
            if (this.ring > this.maxRing) {
               this.exhausted = true;
            }
         }
      }
   }

   private static int[] column(int ring, int index) {
      if (ring == 0) {
         return new int[]{0, 0};
      } else {
         int top = 2 * ring + 1;
         if (index < top) {
            return new int[]{-ring + index, -ring};
         } else {
            index -= top;
            int side = 2 * ring;
            if (index < side) {
               return new int[]{ring, -ring + 1 + index};
            } else {
               index -= side;
               if (index < side) {
                  return new int[]{ring - 1 - index, ring};
               } else {
                  index -= side;
                  return new int[]{-ring, ring - 1 - index};
               }
            }
         }
      }
   }
}
