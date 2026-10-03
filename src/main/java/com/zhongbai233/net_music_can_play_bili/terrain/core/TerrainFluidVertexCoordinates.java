package com.zhongbai233.net_music_can_play_bili.terrain.core;

public final class TerrainFluidVertexCoordinates {
   private TerrainFluidVertexCoordinates() {
   }

   public static float offsetX() {
      return -0.5F;
   }

   public static float offsetY() {
      return 0.0F;
   }

   public static float offsetZ() {
      return -0.5F;
   }

   public static float previewX(float sectionLocalX) {
      return sectionLocalX + offsetX();
   }

   public static float previewY(float sectionLocalY) {
      return sectionLocalY + offsetY();
   }

   public static float previewZ(float sectionLocalZ) {
      return sectionLocalZ + offsetZ();
   }
}
