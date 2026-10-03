package com.zhongbai233.net_music_can_play_bili.client.pad;

import java.util.Arrays;

final class PadMapSnapshotComposer {
   private final float indoorDisplayScale;

   PadMapSnapshotComposer(float indoorDisplayScale) {
      this.indoorDisplayScale = indoorDisplayScale;
   }

   PadMapSnapshot compose(int centerX, int centerY, int centerZ, int cellSize, int width, int height, PadMapTileKind[] tiles, PadMapViewProfile profile) {
      float displayScale = this.displayScale(profile);
      return new PadMapSnapshot(centerX, centerY, centerZ, cellSize, width, height, Arrays.copyOf(tiles, tiles.length), displayScale);
   }

   float displayScale(PadMapViewProfile profile) {
      return profile == PadMapViewProfile.INDOOR ? this.indoorDisplayScale : 1.0F;
   }
}
