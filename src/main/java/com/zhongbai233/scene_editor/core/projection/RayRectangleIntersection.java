package com.zhongbai233.scene_editor.core.projection;

import java.util.Objects;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public record RayRectangleIntersection(double distance, Vector3dc worldPoint, double localX, double localY) {
   public RayRectangleIntersection(double distance, Vector3dc worldPoint, double localX, double localY) {
      if (Double.isFinite(distance) && !(distance < 0.0) && Double.isFinite(localX) && Double.isFinite(localY)) {
         Vector3dc var8 = new Vector3d(Objects.requireNonNull(worldPoint, "worldPoint"));
         this.distance = distance;
         this.worldPoint = var8;
         this.localX = localX;
         this.localY = localY;
      } else {
         throw new IllegalArgumentException("ray rectangle intersection values must be finite and non-negative");
      }
   }

   public Vector3dc worldPoint() {
      return new Vector3d(this.worldPoint);
   }
}
