package com.zhongbai233.net_music_can_play_bili.terrain.core;

public record TerrainSectionKey(int x, int y, int z) {
   public static final int SIZE = 16;
   public static final int CELL_COUNT = 4096;

   public static TerrainSectionKey fromBlock(int blockX, int blockY, int blockZ) {
      return new TerrainSectionKey(Math.floorDiv(blockX, 16), Math.floorDiv(blockY, 16), Math.floorDiv(blockZ, 16));
   }

   public int minBlockX() {
      return this.x * 16;
   }

   public int minBlockY() {
      return this.y * 16;
   }

   public int minBlockZ() {
      return this.z * 16;
   }
}
