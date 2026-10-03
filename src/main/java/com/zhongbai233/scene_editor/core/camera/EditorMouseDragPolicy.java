package com.zhongbai233.scene_editor.core.camera;

public final class EditorMouseDragPolicy {
   private EditorMouseDragPolicy() {
   }

   public static EditorMouseDragPolicy.Action action(int mouseButton, boolean firstPerson, boolean gizmoHandleActive) {
      if (mouseButton == 1 && !firstPerson) {
         return EditorMouseDragPolicy.Action.PAN;
      } else {
         return mouseButton == 0 && gizmoHandleActive && !firstPerson ? EditorMouseDragPolicy.Action.GIZMO : EditorMouseDragPolicy.Action.ORBIT;
      }
   }

   public static enum Action {
      ORBIT,
      PAN,
      GIZMO;
   }
}
