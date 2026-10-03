package com.zhongbai233.scene_editor.core.projection;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Matrix4d;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class PickingRay {
   private final Vector3d origin;
   private final Vector3d direction;

   public PickingRay(Vector3dc origin, Vector3dc direction) {
      this.origin = new Vector3d(origin);
      this.direction = new Vector3d(direction);
      if (this.direction.lengthSquared() <= 1.0E-12) {
         throw new IllegalArgumentException("ray direction must be non-zero");
      } else {
         this.direction.normalize();
      }
   }

   public Vector3d origin() {
      return new Vector3d(this.origin);
   }

   public Vector3d direction() {
      return new Vector3d(this.direction);
   }

   public Vector3d pointAt(double distance) {
      return new Vector3d(this.direction).mul(distance).add(this.origin);
   }

   public Optional<RayRectangleIntersection> intersectRectangle(
      Vector3dc center, Vector3dc localXAxis, Vector3dc localYAxis, double halfWidth, double halfHeight
   ) {
      Objects.requireNonNull(center, "center");
      Vector3d xAxis = normalized(localXAxis, "localXAxis");
      Vector3d yAxis = normalized(localYAxis, "localYAxis");
      if (Double.isFinite(halfWidth) && Double.isFinite(halfHeight) && !(halfWidth <= 0.0) && !(halfHeight <= 0.0)) {
         Vector3d normal = xAxis.cross(yAxis, new Vector3d());
         if (normal.lengthSquared() <= 1.0E-12) {
            throw new IllegalArgumentException("rectangle axes must not be parallel");
         } else {
            normal.normalize();
            double denominator = normal.dot(this.direction);
            if (Math.abs(denominator) <= 1.0E-9) {
               return Optional.empty();
            } else {
               double distance = normal.dot(new Vector3d(center).sub(this.origin)) / denominator;
               if (Double.isFinite(distance) && !(distance < 0.0)) {
                  Vector3d point = this.pointAt(distance);
                  Vector3d offset = new Vector3d(point).sub(center);
                  double localX = offset.dot(xAxis);
                  double localY = offset.dot(yAxis);
                  return !(Math.abs(localX) > halfWidth + 1.0E-9) && !(Math.abs(localY) > halfHeight + 1.0E-9)
                     ? Optional.of(new RayRectangleIntersection(distance, point, localX, localY))
                     : Optional.empty();
               } else {
                  return Optional.empty();
               }
            }
         }
      } else {
         throw new IllegalArgumentException("rectangle half dimensions must be positive and finite");
      }
   }

   public Optional<RayRectangleIntersection> intersectTransformedRectangle(Matrix4fc localToWorld, double halfWidth, double halfHeight) {
      Objects.requireNonNull(localToWorld, "localToWorld");
      if (Double.isFinite(halfWidth) && Double.isFinite(halfHeight) && !(halfWidth <= 0.0) && !(halfHeight <= 0.0)) {
         Matrix4d inverse = new Matrix4d(localToWorld);
         if (Double.isFinite(inverse.determinant()) && !(Math.abs(inverse.determinant()) <= 1.0E-12)) {
            inverse.invert();
            Vector3d localOrigin = inverse.transformPosition(new Vector3d(this.origin));
            Vector3d localDirection = inverse.transformDirection(new Vector3d(this.direction));
            if (Math.abs(localDirection.z) <= 1.0E-12) {
               return Optional.empty();
            } else {
               double distance = -localOrigin.z / localDirection.z;
               if (Double.isFinite(distance) && !(distance < 0.0)) {
                  double localX = localOrigin.x + localDirection.x * distance;
                  double localY = localOrigin.y + localDirection.y * distance;
                  return !(Math.abs(localX) > halfWidth + 1.0E-9) && !(Math.abs(localY) > halfHeight + 1.0E-9)
                     ? Optional.of(new RayRectangleIntersection(distance, this.pointAt(distance), localX, localY))
                     : Optional.empty();
               } else {
                  return Optional.empty();
               }
            }
         } else {
            return Optional.empty();
         }
      } else {
         throw new IllegalArgumentException("rectangle half dimensions must be positive and finite");
      }
   }

   public OptionalDouble intersectAabb(Vector3dc minimum, Vector3dc maximum) {
      Vector3d min = finiteCopy(minimum, "minimum");
      Vector3d max = finiteCopy(maximum, "maximum");
      if (!(min.x > max.x) && !(min.y > max.y) && !(min.z > max.z)) {
         double near = 0.0;
         double far = Double.POSITIVE_INFINITY;

         for (int axis = 0; axis < 3; axis++) {
            double axisOrigin = this.origin.get(axis);
            double axisDirection = this.direction.get(axis);
            double axisMinimum = min.get(axis);
            double axisMaximum = max.get(axis);
            if (Math.abs(axisDirection) <= 1.0E-12) {
               if (axisOrigin < axisMinimum || axisOrigin > axisMaximum) {
                  return OptionalDouble.empty();
               }
            } else {
               double first = (axisMinimum - axisOrigin) / axisDirection;
               double second = (axisMaximum - axisOrigin) / axisDirection;
               if (first > second) {
                  double swap = first;
                  first = second;
                  second = swap;
               }

               near = Math.max(near, first);
               far = Math.min(far, second);
               if (near > far) {
                  return OptionalDouble.empty();
               }
            }
         }

         return far >= 0.0 ? OptionalDouble.of(near) : OptionalDouble.empty();
      } else {
         throw new IllegalArgumentException("AABB minimum must not exceed maximum");
      }
   }

   private static Vector3d normalized(Vector3dc value, String name) {
      Objects.requireNonNull(value, name);
      Vector3d result = new Vector3d(value);
      if (Double.isFinite(result.x) && Double.isFinite(result.y) && Double.isFinite(result.z) && !(result.lengthSquared() <= 1.0E-12)) {
         return result.normalize();
      } else {
         throw new IllegalArgumentException(name + " must be finite and non-zero");
      }
   }

   private static Vector3d finiteCopy(Vector3dc value, String name) {
      Objects.requireNonNull(value, name);
      if (Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z())) {
         return new Vector3d(value);
      } else {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }
}
