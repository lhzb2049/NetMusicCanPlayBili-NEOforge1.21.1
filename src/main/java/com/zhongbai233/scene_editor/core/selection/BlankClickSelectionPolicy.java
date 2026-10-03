package com.zhongbai233.scene_editor.core.selection;

public final class BlankClickSelectionPolicy {
   private static final double CLICK_DISTANCE_SQUARED = 16.0;

   private BlankClickSelectionPolicy() {
   }

   public static boolean shouldDeselect(boolean blankCandidate, int mouseButton, double pressX, double pressY, double releaseX, double releaseY) {
      if (blankCandidate && mouseButton == 0) {
         double dx = releaseX - pressX;
         double dy = releaseY - pressY;
         return dx * dx + dy * dy < 16.0;
      } else {
         return false;
      }
   }
}
