package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;

final class PadMapRenderReusePlan {
   private PadMapRenderReusePlan() {
   }

   static PadMapRenderReusePlan.Plan between(PadMapSnapshot previous, PadMapSnapshot next) {
      if (previous != null
         && next != null
         && previous.width() == next.width()
         && previous.height() == next.height()
         && previous.cellSizeBlocks() == next.cellSizeBlocks()
         && previous.centerY() == next.centerY()
         && previous.displayScale() == next.displayScale()) {
         int cellSize = next.cellSizeBlocks();
         int worldDx = next.centerX() - previous.centerX();
         int worldDz = next.centerZ() - previous.centerZ();
         if (cellSize > 0 && Math.floorMod(worldDx, cellSize) == 0 && Math.floorMod(worldDz, cellSize) == 0) {
            int cellDx = worldDx / cellSize;
            int cellDz = worldDz / cellSize;
            return Math.abs(cellDx) < next.width() && Math.abs(cellDz) < next.height()
               ? new PadMapRenderReusePlan.Plan(true, cellDx, -cellDz)
               : PadMapRenderReusePlan.Plan.none();
         } else {
            return PadMapRenderReusePlan.Plan.none();
         }
      } else {
         return PadMapRenderReusePlan.Plan.none();
      }
   }

   record Plan(boolean reusable, int textureCellShiftX, int textureCellShiftY) {
      static PadMapRenderReusePlan.Plan none() {
         return new PadMapRenderReusePlan.Plan(false, 0, 0);
      }
   }
}
