package com.zhongbai233.net_music_can_play_bili.blockentity;

public final class ModernTurntableVolumePolicy {
   private ModernTurntableVolumePolicy() {
   }

   public static ModernTurntableVolumePolicy.Action decide(int previousPerMille, int nextPerMille, boolean playing) {
      int previous = clamp(previousPerMille);
      int next = clamp(nextPerMille);
      if (previous == next) {
         return ModernTurntableVolumePolicy.Action.NONE;
      } else {
         return !playing ? ModernTurntableVolumePolicy.Action.APPLY_ONLY : ModernTurntableVolumePolicy.Action.APPLY_ONLY;
      }
   }

   public static int clamp(int value) {
      return Math.max(0, Math.min(1000, value));
   }

   public static enum Action {
      NONE,
      APPLY_ONLY;
   }
}
