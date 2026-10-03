package com.zhongbai233.net_music_can_play_bili.client.terrain;

public final class TerrainCompilationAdmission {
   private TerrainCompilationAdmission() {
   }

   public static boolean isCurrent(TerrainPreviewFrame frame, TerrainBlockSectionSnapshot source, long requestEpoch, long currentEpoch) {
      if (requestEpoch == currentEpoch && !frame.removedSections().contains(source.section())) {
         for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
            if (snapshot == source) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }
}
