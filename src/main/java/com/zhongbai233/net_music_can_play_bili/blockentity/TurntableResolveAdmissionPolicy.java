package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import java.util.Objects;

final class TurntableResolveAdmissionPolicy {
   private TurntableResolveAdmissionPolicy() {
   }

   static TurntableResolveAdmissionPolicy.Decision decide(
      boolean removed,
      boolean sameLevel,
      ResolveGeneration currentGeneration,
      ResolveGeneration capturedGeneration,
      String currentSource,
      String requestedSource
   ) {
      if (removed) {
         return TurntableResolveAdmissionPolicy.Decision.DROP_REMOVED;
      } else if (!sameLevel) {
         return TurntableResolveAdmissionPolicy.Decision.DROP_LEVEL_CHANGED;
      } else if (!Objects.requireNonNull(currentGeneration, "currentGeneration").equals(Objects.requireNonNull(capturedGeneration, "capturedGeneration"))) {
         return TurntableResolveAdmissionPolicy.Decision.DROP_STALE_GENERATION;
      } else {
         return Objects.equals(currentSource, requestedSource)
            ? TurntableResolveAdmissionPolicy.Decision.APPLY
            : TurntableResolveAdmissionPolicy.Decision.DROP_SOURCE_CHANGED;
      }
   }

   static enum Decision {
      APPLY,
      DROP_REMOVED,
      DROP_LEVEL_CHANGED,
      DROP_STALE_GENERATION,
      DROP_SOURCE_CHANGED;
   }
}
