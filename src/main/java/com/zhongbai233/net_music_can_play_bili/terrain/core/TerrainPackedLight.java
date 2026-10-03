package com.zhongbai233.net_music_can_play_bili.terrain.core;

public final class TerrainPackedLight {
   private TerrainPackedLight() {
   }

   public static byte pack(int block, int sky) {
      checkLevel(block, "block");
      checkLevel(sky, "sky");
      return (byte)(sky << 4 | block);
   }

   public static int block(byte packed) {
      return packed & 15;
   }

   public static int sky(byte packed) {
      return packed >>> 4 & 15;
   }

   private static void checkLevel(int level, String name) {
      if (level < 0 || level > 15) {
         throw new IllegalArgumentException(name + " light level must be within [0, 15]");
      }
   }
}
