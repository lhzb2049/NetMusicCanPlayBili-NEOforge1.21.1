package com.zhongbai233.net_music_can_play_bili.client.renderer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ProjectorScreenBounds {
   public static final double BASE_HEIGHT = 1.35;

   private ProjectorScreenBounds() {
   }

   public static AABB aroundBlock(
      BlockPos blockPos, double offsetX, double height, double offsetZ, double yawDegrees, double pitchDegrees, double scale, double aspect, double margin
   ) {
      double centerX = blockPos.getX() + 0.5 + offsetX;
      double centerY = blockPos.getY() + height;
      double centerZ = blockPos.getZ() + 0.5 + offsetZ;
      return toAabb(ProjectorScreenGeometry.aroundCenter(centerX, centerY, centerZ, yawDegrees, pitchDegrees, scale, aspect, margin));
   }

   public static AABB aroundCenter(
      double centerX, double centerY, double centerZ, double yawDegrees, double pitchDegrees, double scale, double aspect, double margin
   ) {
      return toAabb(ProjectorScreenGeometry.aroundCenter(centerX, centerY, centerZ, yawDegrees, pitchDegrees, scale, aspect, margin));
   }

   public static double distanceToSqr(AABB bounds, Vec3 point) {
      return ProjectorScreenGeometry.distanceToSqr(
         new ProjectorScreenGeometry.Bounds(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ), point.x, point.y, point.z
      );
   }

   private static AABB toAabb(ProjectorScreenGeometry.Bounds bounds) {
      return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
   }
}
