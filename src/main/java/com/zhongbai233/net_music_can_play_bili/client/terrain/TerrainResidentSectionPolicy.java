package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class TerrainResidentSectionPolicy {
   private TerrainResidentSectionPolicy() {
   }

   public static Set<TerrainSectionKey> staleSections(Set<TerrainSectionKey> resident, TerrainPreviewFrame frame) {
      Objects.requireNonNull(resident, "resident");
      Objects.requireNonNull(frame, "frame");
      Set<TerrainSectionKey> stale = new HashSet<>();

      for (TerrainSectionKey key : resident) {
         if (!frame.fullDetailSectionKeys().contains(key)) {
            stale.add(key);
         }
      }

      stale.addAll(frame.removedSections());
      return Set.copyOf(stale);
   }
}
