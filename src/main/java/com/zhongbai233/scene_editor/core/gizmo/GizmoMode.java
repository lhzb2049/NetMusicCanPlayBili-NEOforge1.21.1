package com.zhongbai233.scene_editor.core.gizmo;

public enum GizmoMode {
   MOVE("Move"),
   ROTATE("Rotate"),
   SCALE("Scale");

   private final String label;

   private GizmoMode(String label) {
      this.label = label;
   }

   public String label() {
      return this.label;
   }
}
