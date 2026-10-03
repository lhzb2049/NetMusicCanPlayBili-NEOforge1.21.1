package com.zhongbai233.scene_editor.core.gizmo;

import org.joml.Vector3d;

public enum GizmoHandle {
   NONE,
   X,
   Y,
   Z,
   UNIFORM;

   public Vector3d axis() {
      return switch (this) {
         case NONE, UNIFORM -> throw new IllegalStateException(this + " has no axis");
         case X -> new Vector3d(1.0, 0.0, 0.0);
         case Y -> new Vector3d(0.0, 1.0, 0.0);
         case Z -> new Vector3d(0.0, 0.0, 1.0);
      };
   }

   public GizmoConstraint constraint() {
      return switch (this) {
         case NONE, UNIFORM -> throw new IllegalStateException(this + " has no movement constraint");
         case X -> GizmoConstraint.X_AXIS;
         case Y -> GizmoConstraint.Y_AXIS;
         case Z -> GizmoConstraint.Z_AXIS;
      };
   }

   public GizmoConstraint rotationConstraint() {
      return switch (this) {
         case NONE, UNIFORM -> throw new IllegalStateException(this + " has no rotation constraint");
         case X -> GizmoConstraint.YZ_PLANE;
         case Y -> GizmoConstraint.XZ_PLANE;
         case Z -> GizmoConstraint.XY_PLANE;
      };
   }
}
