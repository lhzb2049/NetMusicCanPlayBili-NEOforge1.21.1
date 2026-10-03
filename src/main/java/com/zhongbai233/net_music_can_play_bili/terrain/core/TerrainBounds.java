package com.zhongbai233.net_music_can_play_bili.terrain.core;

public record TerrainBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
   public TerrainBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      if (maxX >= minX && maxY >= minY && maxZ >= minZ) {
         this.minX = minX;
         this.minY = minY;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxY = maxY;
         this.maxZ = maxZ;
      } else {
         throw new IllegalArgumentException("terrain bounds are inverted");
      }
   }

   public long volume() {
      long width = (long)this.maxX - this.minX + 1L;
      long height = (long)this.maxY - this.minY + 1L;
      long depth = (long)this.maxZ - this.minZ + 1L;
      return Math.multiplyExact(Math.multiplyExact(width, height), depth);
   }

   public boolean contains(int x, int y, int z) {
      return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
   }

   public boolean intersects(TerrainSectionKey section) {
      int sectionMaxX = section.minBlockX() + 16 - 1;
      int sectionMaxY = section.minBlockY() + 16 - 1;
      int sectionMaxZ = section.minBlockZ() + 16 - 1;
      return sectionMaxX >= this.minX
         && section.minBlockX() <= this.maxX
         && sectionMaxY >= this.minY
         && section.minBlockY() <= this.maxY
         && sectionMaxZ >= this.minZ
         && section.minBlockZ() <= this.maxZ;
   }
}
