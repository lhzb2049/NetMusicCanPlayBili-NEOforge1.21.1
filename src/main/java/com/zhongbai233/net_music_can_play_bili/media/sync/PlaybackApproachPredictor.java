package com.zhongbai233.net_music_can_play_bili.media.sync;

public final class PlaybackApproachPredictor {
   public static final double HORIZON_TICKS = 40.0;
   public static final double MAX_LEAD_BLOCKS = 48.0;
   public static final double MIN_SPEED_PER_TICK = 0.03;

   private PlaybackApproachPredictor() {
   }

   public static boolean willEnterSphere(
      double x, double y, double z, double velocityX, double velocityY, double velocityZ, double targetX, double targetY, double targetZ, double radius
   ) {
      if (finite(x, y, z, velocityX, velocityY, velocityZ, targetX, targetY, targetZ, radius) && !(radius <= 0.0)) {
         double dx = targetX - x;
         double dy = targetY - y;
         double dz = targetZ - z;
         double radiusSquared = radius * radius;
         if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
            return false;
         } else {
            PlaybackApproachPredictor.Segment segment = projectedSegment(velocityX, velocityY, velocityZ);
            if (segment != null && !(dx * segment.x() + dy * segment.y() + dz * segment.z() <= 0.0)) {
               double lengthSquared = segment.lengthSquared();
               double t = Math.clamp((dx * segment.x() + dy * segment.y() + dz * segment.z()) / lengthSquared, 0.0, 1.0);
               double closestX = x + segment.x() * t;
               double closestY = y + segment.y() * t;
               double closestZ = z + segment.z() * t;
               double closestDx = targetX - closestX;
               double closestDy = targetY - closestY;
               double closestDz = targetZ - closestZ;
               return closestDx * closestDx + closestDy * closestDy + closestDz * closestDz <= radiusSquared;
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   public static boolean willEnterAabb(
      double x,
      double y,
      double z,
      double velocityX,
      double velocityY,
      double velocityZ,
      double centerX,
      double centerY,
      double centerZ,
      double halfX,
      double halfY,
      double halfZ
   ) {
      if (finite(x, y, z, velocityX, velocityY, velocityZ, centerX, centerY, centerZ, halfX, halfY, halfZ)
         && !(halfX <= 0.0)
         && !(halfY <= 0.0)
         && !(halfZ <= 0.0)
         && !insideAabb(x, y, z, centerX, centerY, centerZ, halfX, halfY, halfZ)) {
         PlaybackApproachPredictor.Segment segment = projectedSegment(velocityX, velocityY, velocityZ);
         if (segment == null) {
            return false;
         } else {
            double[] interval = new double[]{0.0, 1.0};
            return intersectsAxis(x, segment.x(), centerX - halfX, centerX + halfX, interval)
               && intersectsAxis(y, segment.y(), centerY - halfY, centerY + halfY, interval)
               && intersectsAxis(z, segment.z(), centerZ - halfZ, centerZ + halfZ, interval);
         }
      } else {
         return false;
      }
   }

   private static PlaybackApproachPredictor.Segment projectedSegment(double velocityX, double velocityY, double velocityZ) {
      double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ);
      if (Double.isFinite(speed) && !(speed < 0.03)) {
         double travel = Math.min(48.0, speed * 40.0);
         double scale = travel / speed;
         return new PlaybackApproachPredictor.Segment(velocityX * scale, velocityY * scale, velocityZ * scale);
      } else {
         return null;
      }
   }

   private static boolean intersectsAxis(double origin, double direction, double minimum, double maximum, double[] interval) {
      if (!(Math.abs(direction) < 1.0E-9)) {
         double first = (minimum - origin) / direction;
         double second = (maximum - origin) / direction;
         double entry = Math.min(first, second);
         double exit = Math.max(first, second);
         interval[0] = Math.max(interval[0], entry);
         interval[1] = Math.min(interval[1], exit);
         return interval[0] <= interval[1];
      } else {
         return origin >= minimum && origin <= maximum;
      }
   }

   private static boolean insideAabb(double x, double y, double z, double centerX, double centerY, double centerZ, double halfX, double halfY, double halfZ) {
      return Math.abs(x - centerX) < halfX && Math.abs(y - centerY) < halfY && Math.abs(z - centerZ) < halfZ;
   }

   private static boolean finite(double... values) {
      for (double value : values) {
         if (!Double.isFinite(value)) {
            return false;
         }
      }

      return true;
   }

   private record Segment(double x, double y, double z) {
      double lengthSquared() {
         return this.x * this.x + this.y * this.y + this.z * this.z;
      }
   }
}
