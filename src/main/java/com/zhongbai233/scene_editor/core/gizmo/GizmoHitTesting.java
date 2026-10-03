package com.zhongbai233.scene_editor.core.gizmo;

import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.projection.ProjectedPoint;
import java.util.Objects;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class GizmoHitTesting {
   private static final GizmoHandle[] AXIS_HANDLES = new GizmoHandle[]{GizmoHandle.X, GizmoHandle.Y, GizmoHandle.Z};
   private static final int RING_SEGMENTS = 48;

   private GizmoHitTesting() {
   }

   public static GizmoHandle moveHandleAt(
      double mouseX, double mouseY, Vector3dc origin, CameraMatrices matrices, EditorViewport viewport, double axisLength, double hitRadius
   ) {
      requirePointerAndDimensions(mouseX, mouseY, origin, matrices, viewport, axisLength, hitRadius);
      ProjectedPoint center = EditorProjection.project(origin, matrices, viewport);
      if (center.visible() && !(Math.hypot(mouseX - center.screenX(), mouseY - center.screenY()) <= hitRadius)) {
         GizmoHandle best = GizmoHandle.NONE;
         double bestDistance = hitRadius;

         for (GizmoHandle handle : AXIS_HANDLES) {
            for (double direction : new double[]{-1.0, 1.0}) {
               ProjectedPoint endpoint = EditorProjection.project(axisEndpoint(origin, handle, axisLength * direction), matrices, viewport);
               if (endpoint.visible()) {
                  double distance = distanceToSegment(mouseX, mouseY, center.screenX(), center.screenY(), endpoint.screenX(), endpoint.screenY());
                  if (distance <= bestDistance) {
                     bestDistance = distance;
                     best = handle;
                  }
               }
            }
         }

         return best;
      } else {
         return GizmoHandle.NONE;
      }
   }

   public static GizmoHandle rotateHandleAt(
      double mouseX, double mouseY, Vector3dc origin, CameraMatrices matrices, EditorViewport viewport, double ringRadius, double hitRadius
   ) {
      requirePointerAndDimensions(mouseX, mouseY, origin, matrices, viewport, ringRadius, hitRadius);
      GizmoHandle best = GizmoHandle.NONE;
      double bestDistance = hitRadius;

      for (GizmoHandle handle : AXIS_HANDLES) {
         ProjectedPoint previous = EditorProjection.project(ringPoint(origin, handle, ringRadius, 0.0), matrices, viewport);

         for (int segment = 1; segment <= 48; segment++) {
            double angle = (Math.PI * 2) * segment / 48.0;
            ProjectedPoint current = EditorProjection.project(ringPoint(origin, handle, ringRadius, angle), matrices, viewport);
            if (previous.visible() && current.visible()) {
               double distance = distanceToSegment(mouseX, mouseY, previous.screenX(), previous.screenY(), current.screenX(), current.screenY());
               if (distance <= bestDistance) {
                  bestDistance = distance;
                  best = handle;
               }
            }

            previous = current;
         }
      }

      return best;
   }

   public static GizmoHandle scaleHandleAt(
      double mouseX, double mouseY, Vector3dc origin, CameraMatrices matrices, EditorViewport viewport, double handleLength, double hitRadius
   ) {
      requirePointerAndDimensions(mouseX, mouseY, origin, matrices, viewport, handleLength, hitRadius);
      GizmoHandle best = GizmoHandle.NONE;
      double bestDistance = hitRadius;

      for (GizmoHandle handle : AXIS_HANDLES) {
         for (double direction : new double[]{-1.0, 1.0}) {
            ProjectedPoint endpoint = EditorProjection.project(axisEndpoint(origin, handle, handleLength * direction), matrices, viewport);
            if (endpoint.visible()) {
               double distance = Math.hypot(mouseX - endpoint.screenX(), mouseY - endpoint.screenY());
               if (distance <= bestDistance) {
                  bestDistance = distance;
                  best = handle;
               }
            }
         }
      }

      return best;
   }

   public static Vector3d axisEndpoint(Vector3dc origin, GizmoHandle handle, double signedLength) {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(handle, "handle");
      if (Double.isFinite(signedLength) && signedLength != 0.0) {
         return new Vector3d(origin).fma(signedLength, handle.axis());
      } else {
         throw new IllegalArgumentException("axis length must be finite and non-zero");
      }
   }

   public static Vector3d ringPoint(Vector3dc origin, GizmoHandle handle, double radius, double angle) {
      Objects.requireNonNull(origin, "origin");
      if (Double.isFinite(radius) && !(radius <= 0.0) && Double.isFinite(angle)) {
         double first = Math.cos(angle) * radius;
         double second = Math.sin(angle) * radius;

         return switch (handle) {
            case X -> new Vector3d(origin).add(0.0, first, second);
            case Y -> new Vector3d(origin).add(first, 0.0, second);
            case Z -> new Vector3d(origin).add(first, second, 0.0);
            case NONE, UNIFORM -> throw new IllegalArgumentException(handle + " has no rotation ring");
         };
      } else {
         throw new IllegalArgumentException("ring radius and angle must be finite");
      }
   }

   private static void requirePointerAndDimensions(
      double mouseX, double mouseY, Vector3dc origin, CameraMatrices matrices, EditorViewport viewport, double size, double hitRadius
   ) {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(matrices, "matrices");
      Objects.requireNonNull(viewport, "viewport");
      if (!Double.isFinite(mouseX) || !Double.isFinite(mouseY) || !Double.isFinite(size) || size <= 0.0 || !Double.isFinite(hitRadius) || hitRadius <= 0.0) {
         throw new IllegalArgumentException("gizmo coordinates and dimensions must be finite");
      }
   }

   static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
      double dx = x2 - x1;
      double dy = y2 - y1;
      double lengthSquared = dx * dx + dy * dy;
      if (lengthSquared <= 1.0E-12) {
         return Math.hypot(px - x1, py - y1);
      } else {
         double parameter = Math.clamp(((px - x1) * dx + (py - y1) * dy) / lengthSquared, 0.0, 1.0);
         return Math.hypot(px - (x1 + parameter * dx), py - (y1 + parameter * dy));
      }
   }
}
