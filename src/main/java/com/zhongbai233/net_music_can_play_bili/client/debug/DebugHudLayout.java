package com.zhongbai233.net_music_can_play_bili.client.debug;

final class DebugHudLayout {
   static final double SINGLE_PANEL_WIDTH_RATIO = 0.44;
   static final double DUAL_PANEL_WIDTH_RATIO = 0.38;
   static final double PANEL_HEIGHT_RATIO = 0.46;

   private DebugHudLayout() {
   }

   static DebugHudLayout.Plan plan(int screenWidth, int screenHeight, boolean dualPanels, int baseWidth, int baseHeight, int margin) {
      if (baseWidth > 0 && baseHeight > 0) {
         int widthBudget = widthBudget(screenWidth, dualPanels, margin);
         int availableHeight = Math.max(0, screenHeight - margin * 2);
         int heightBudget = Math.min(availableHeight, (int)Math.floor(screenHeight * 0.46));
         if (widthBudget > 0 && heightBudget > 0) {
            float scale = Math.min(1.0F, Math.min((float)widthBudget / baseWidth, (float)heightBudget / baseHeight));
            return scale > 0.0F ? new DebugHudLayout.Plan(baseWidth, baseHeight, scale) : DebugHudLayout.Plan.hidden();
         } else {
            return DebugHudLayout.Plan.hidden();
         }
      } else {
         return DebugHudLayout.Plan.hidden();
      }
   }

   static int widthBudget(int screenWidth, boolean dualPanels, int margin) {
      int availableWidth = dualPanels ? (screenWidth - margin * 3) / 2 : screenWidth - margin * 2;
      double ratio = dualPanels ? 0.38 : 0.44;
      int ratioWidth = (int)Math.floor(screenWidth * ratio);
      return Math.max(0, Math.min(availableWidth, ratioWidth));
   }

   record Plan(int baseWidth, int baseHeight, float scale) {
      static DebugHudLayout.Plan hidden() {
         return new DebugHudLayout.Plan(0, 0, 0.0F);
      }

      boolean visible() {
         return this.scale > 0.0F;
      }

      float renderedWidth() {
         return this.baseWidth * this.scale;
      }

      float renderedHeight() {
         return this.baseHeight * this.scale;
      }
   }
}
