package com.zhongbai233.scene_editor.core.gizmo;

import java.util.Objects;
import org.joml.Vector3fc;

public final class GizmoSizingPolicy {
   public static final double MIN_AXIS_LENGTH = 1.25;
   public static final double MIN_ROTATION_RADIUS = 1.05;
   public static final double MIN_SCALE_HANDLE_LENGTH = 1.35;
   private static final double AXIS_CLEARANCE = 0.35;
   private static final double ROTATION_CLEARANCE = 0.2;
   private static final double SCALE_CLEARANCE = 0.45;

   private GizmoSizingPolicy() {
   }

   public static GizmoSizingPolicy.Sizes calculate(double radius) {
      if (Double.isFinite(radius) && !(radius < 0.0)) {
         return new GizmoSizingPolicy.Sizes(Math.max(1.25, radius + 0.35), Math.max(1.05, radius + 0.2), Math.max(1.35, radius + 0.45));
      } else {
         throw new IllegalArgumentException("selection radius must be finite and non-negative");
      }
   }

   public static double worldBoundingRadius(Vector3fc scale) {
      Objects.requireNonNull(scale, "scale");
      if (Float.isFinite(scale.x()) && Float.isFinite(scale.y()) && Float.isFinite(scale.z())) {
         return 0.5 * Math.sqrt((double)scale.x() * scale.x() + (double)scale.y() * scale.y() + (double)scale.z() * scale.z());
      } else {
         throw new IllegalArgumentException("scale must be finite");
      }
   }

   public record Sizes(double axisLength, double rotationRadius, double scaleHandleLength) {
      public Sizes(double axisLength, double rotationRadius, double scaleHandleLength) {
         if (positiveFinite(axisLength) && positiveFinite(rotationRadius) && positiveFinite(scaleHandleLength)) {
            this.axisLength = axisLength;
            this.rotationRadius = rotationRadius;
            this.scaleHandleLength = scaleHandleLength;
         } else {
            throw new IllegalArgumentException("gizmo dimensions must be positive and finite");
         }
      }

      private static boolean positiveFinite(double value) {
         return Double.isFinite(value) && value > 0.0;
      }
   }
}
