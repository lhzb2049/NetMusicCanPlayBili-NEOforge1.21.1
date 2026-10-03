package com.zhongbai233.net_music_can_play_bili.terrain.core;

public final class TerrainFixedCorePolicy {
   public static final double RADIUS = 12.5;
   public static final double FADE_WIDTH = 3.0;
   public static final double SOLID_RADIUS = 9.5;

   private TerrainFixedCorePolicy() {
   }

   public static double retention(double centerX, double centerY, double centerZ, int blockX, int blockY, int blockZ) {
      double dx = blockX + 0.5 - centerX;
      double dy = blockY + 0.5 - centerY;
      double dz = blockZ + 0.5 - centerZ;
      double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (distance <= 9.5) {
         return 1.0;
      } else if (distance >= 12.5) {
         return 0.0;
      } else {
         double t = (distance - 9.5) / 3.0;
         double smooth = t * t * (3.0 - 2.0 * t);
         return 1.0 - smooth;
      }
   }

   public static boolean rendersBlock(long seed, double centerX, double centerY, double centerZ, int blockX, int blockY, int blockZ) {
      double retention = retention(centerX, centerY, centerZ, blockX, blockY, blockZ);
      return retention >= 1.0 || retention > 0.0 && unitHash(seed, blockX, blockY, blockZ, 0) < retention;
   }

   public static boolean emitsBranch(long seed, int x, int y, int z, int axis) {
      return unitHash(seed, x, y, z, axis + 17) < 0.22;
   }

   public static double branchLength(long seed, int x, int y, int z, int axis) {
      return 0.5 + unitHash(seed, x, y, z, axis + 31) * 2.0;
   }

   public static int branchDirection(long seed, int x, int y, int z, int axis) {
      return (int)Math.floor(unitHash(seed, x, y, z, axis + 47) * 6.0);
   }

   public static int overviewCellSize(double distance) {
      return Double.isFinite(distance) && !(distance > 36.5) ? 4 : 8;
   }

   public static boolean sectionMayContainDetail(double centerX, double centerY, double centerZ, int minBlockX, int minBlockY, int minBlockZ) {
      double nearestX = Math.clamp(centerX, minBlockX + 0.5, minBlockX + 16 - 0.5);
      double nearestY = Math.clamp(centerY, minBlockY + 0.5, minBlockY + 16 - 0.5);
      double nearestZ = Math.clamp(centerZ, minBlockZ + 0.5, minBlockZ + 16 - 0.5);
      double dx = nearestX - centerX;
      double dy = nearestY - centerY;
      double dz = nearestZ - centerZ;
      return dx * dx + dy * dy + dz * dz < 156.25;
   }

   private static double unitHash(long seed, int x, int y, int z, int salt) {
      long value = seed ^ mix(x * -7046029254386353131L);
      value ^= mix(y * -4417276706812531889L);
      value ^= mix(z * 1609587929392839161L);
      value ^= mix(salt * -2960836687051489901L);
      return (mix(value) >>> 11) * 1.110223E-16F;
   }

   private static long mix(long value) {
      value ^= value >>> 30;
      value *= -4658895280553007687L;
      value ^= value >>> 27;
      value *= -7723592293110705685L;
      return value ^ value >>> 31;
   }
}
