package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

final class PreviewLineWidthPolicy {
   private static final float BASE_SCALE = 1.3F;
   private static final float REFERENCE_DISTANCE = 4.0F;
   private static final float REFERENCE_ORTHO_HALF_HEIGHT = 3.0F;
   private static final float MAX_SCALE = 4.0F;

   private PreviewLineWidthPolicy() {
   }

   static float perspective(float cameraDistance) {
      float distanceRatio = Math.max(1.0F, finitePositive(cameraDistance) / 4.0F);
      return clamp(1.3F * (float)Math.sqrt(distanceRatio));
   }

   static float orthographic(float halfHeight) {
      float viewRatio = Math.max(1.0F, finitePositive(halfHeight) / 3.0F);
      return clamp(1.3F * (float)Math.sqrt(viewRatio));
   }

   static float firstPerson() {
      return 1.3F;
   }

   private static float finitePositive(float value) {
      return Float.isFinite(value) && value > 0.0F ? value : 1.0F;
   }

   private static float clamp(float value) {
      return Math.clamp(value, 1.3F, 4.0F);
   }
}
