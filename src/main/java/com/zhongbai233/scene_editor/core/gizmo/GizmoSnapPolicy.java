package com.zhongbai233.scene_editor.core.gizmo;

import java.util.Objects;

public final class GizmoSnapPolicy {
   public static final double DEFAULT_MOVE_STEP = 0.1;
   public static final double SHIFT_MOVE_STEP = 0.05;
   public static final double FINE_MOVE_STEP = 0.001;
   public static final double DEFAULT_ROTATE_STEP = 15.0;
   public static final double SHIFT_ROTATE_STEP = 5.0;
   public static final double FINE_ROTATE_STEP = 0.001;

   private GizmoSnapPolicy() {
   }

   public static double step(GizmoMode mode, boolean shiftDown, boolean controlDown) {
      Objects.requireNonNull(mode, "mode");
      if (mode == GizmoMode.ROTATE) {
         if (controlDown) {
            return 0.001;
         } else {
            return shiftDown ? 5.0 : 15.0;
         }
      } else if (controlDown) {
         return 0.001;
      } else {
         return shiftDown ? 0.05 : 0.1;
      }
   }

   public static double snapDelta(double delta, double step) {
      if (!Double.isFinite(delta)) {
         throw new IllegalArgumentException("delta must be finite");
      } else if (Double.isFinite(step) && !(step <= 0.0)) {
         double snapped = Math.rint(delta / step) * step;
         return snapped == -0.0 ? 0.0 : snapped;
      } else {
         throw new IllegalArgumentException("step must be positive and finite");
      }
   }
}
