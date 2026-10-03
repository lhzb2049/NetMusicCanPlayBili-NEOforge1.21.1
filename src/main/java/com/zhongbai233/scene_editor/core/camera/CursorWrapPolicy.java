package com.zhongbai233.scene_editor.core.camera;

import java.util.OptionalDouble;

public final class CursorWrapPolicy {
   private CursorWrapPolicy() {
   }

   public static OptionalDouble horizontalTarget(double cursorX, double width, double margin) {
      return target(cursorX, width, margin);
   }

   public static OptionalDouble verticalTarget(double cursorY, double height, double margin) {
      return target(cursorY, height, margin);
   }

   private static OptionalDouble target(double cursor, double extent, double margin) {
      if (!Double.isFinite(cursor) || !Double.isFinite(extent) || !Double.isFinite(margin) || extent <= 0.0 || margin < 0.0 || margin * 2.0 >= extent) {
         throw new IllegalArgumentException("cursor wrap dimensions must be finite and usable");
      } else if (cursor <= margin) {
         return OptionalDouble.of(extent - margin);
      } else {
         return cursor >= extent - margin ? OptionalDouble.of(margin) : OptionalDouble.empty();
      }
   }
}
