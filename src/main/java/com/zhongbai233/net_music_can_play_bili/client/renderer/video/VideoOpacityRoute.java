package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

public enum VideoOpacityRoute {
   SKIP,
   TRANSLUCENT,
   OPAQUE;

   public static VideoOpacityRoute choose(float opacity) {
      float normalized = normalize(opacity);
      if (normalized <= 0.0F) {
         return SKIP;
      } else {
         return normalized >= 1.0F ? OPAQUE : TRANSLUCENT;
      }
   }

   public static float normalize(float opacity) {
      if (Float.isNaN(opacity) || opacity == Float.NEGATIVE_INFINITY) {
         return 0.0F;
      } else {
         return opacity == Float.POSITIVE_INFINITY ? 1.0F : Math.clamp(opacity, 0.0F, 1.0F);
      }
   }

   public static int whiteVertexColor(float opacity) {
      int alpha = Math.round(normalize(opacity) * 255.0F);
      return alpha << 24 | 16777215;
   }
}
