package com.zhongbai233.net_music_can_play_bili.client.renderer;

final class DiscRotationPolicy {
   private static final float RADIANS_PER_TICK = (float) (Math.PI / 20);

   private DiscRotationPolicy() {
   }

   static float rotationAt(long gameTime, float partialTick) {
      double ticks = (float)Math.floorMod(gameTime, 40L) + Math.clamp(partialTick, 0.0F, 1.0F);
      return (float)(ticks * (float) (Math.PI / 20));
   }
}
