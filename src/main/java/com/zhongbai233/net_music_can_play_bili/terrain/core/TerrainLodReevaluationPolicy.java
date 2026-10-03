package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Objects;

public final class TerrainLodReevaluationPolicy {
   private TerrainLodReevaluationPolicy() {
   }

   public static boolean shouldReplace(TerrainLodLevel current, TerrainLodLevel desired) {
      return Objects.requireNonNull(current, "current") != Objects.requireNonNull(desired, "desired");
   }
}
