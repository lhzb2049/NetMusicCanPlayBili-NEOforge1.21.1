package com.zhongbai233.net_music_can_play_bili.client.pad;

public final class PadMapProjection {
   private PadMapProjection() {
   }

   public static PadMapProjection.Viewport viewport(PadMapSnapshot map, PadMapProjection.Rect rect) {
      return viewport(map, rect, map != null ? map.centerX() : 0.0F, map != null ? map.centerZ() : 0.0F, 1.0F);
   }

   public static PadMapProjection.Viewport viewport(PadMapSnapshot map, PadMapProjection.Rect rect, double focusX, double focusZ) {
      return viewport(map, rect, (float)focusX, (float)focusZ, 1.0F);
   }

   public static PadMapProjection.Viewport viewport(PadMapSnapshot map, PadMapProjection.Rect rect, float focusX, float focusZ, float cellPixelScale) {
      if (map != null && rect != null) {
         float cell = Math.max(0.01F, cellPixelScale) * Math.max(1.0F, map.displayScale());
         float drawW = map.width() * cell;
         float drawH = map.height() * cell;
         float centerX = rect.x() + rect.w() / 2.0F;
         float centerY = rect.y() + rect.h() / 2.0F;
         float offsetX = (focusX - map.centerX()) / map.cellSizeBlocks() * cell;
         float offsetY = -(focusZ - map.centerZ()) / map.cellSizeBlocks() * cell;
         return new PadMapProjection.Viewport(
            Math.round(centerX - drawW / 2.0F + offsetX), Math.round(centerY - drawH / 2.0F - offsetY), Math.round(drawW), Math.round(drawH), cell, cell
         );
      } else {
         return new PadMapProjection.Viewport(0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
      }
   }

   public static PadMapProjection.Rect fitRect(int x, int y, int w, int h, int preferredW, int preferredH, float fallbackAspect) {
      if (preferredW > 0 && preferredH > 0 && preferredW <= w && preferredH <= h) {
         int fittedX = x + (w - preferredW) / 2;
         int fittedY = y + (h - preferredH) / 2;
         return new PadMapProjection.Rect(fittedX, fittedY, preferredW, preferredH);
      } else {
         float aspect = preferredW > 0 && preferredH > 0 ? (float)preferredW / preferredH : Math.max(0.01F, fallbackAspect);
         int fittedW = w;
         int fittedH = Math.round(w / aspect);
         if (fittedH > h) {
            fittedH = h;
            fittedW = Math.round(h * aspect);
         }

         int fittedX = x + (w - fittedW) / 2;
         int fittedY = y + (h - fittedH) / 2;
         return new PadMapProjection.Rect(fittedX, fittedY, fittedW, fittedH);
      }
   }

   public static float mapScreenX(float worldX, PadMapSnapshot map, PadMapProjection.Viewport viewport) {
      return viewport.x() + viewport.w() / 2.0F - (worldX - map.centerX()) / map.cellSizeBlocks() * viewport.cellX();
   }

   public static float mapScreenY(float worldZ, PadMapSnapshot map, PadMapProjection.Viewport viewport) {
      return viewport.y() + viewport.h() / 2.0F - (worldZ - map.centerZ()) / map.cellSizeBlocks() * viewport.cellY();
   }

   public static float screenToWorldX(float screenX, PadMapSnapshot map, PadMapProjection.Viewport viewport) {
      return map.centerX() - (screenX - viewport.x() - viewport.w() / 2.0F) * map.cellSizeBlocks() / viewport.cellX();
   }

   public static float screenToWorldZ(float screenY, PadMapSnapshot map, PadMapProjection.Viewport viewport) {
      return map.centerZ() - (screenY - viewport.y() - viewport.h() / 2.0F) * map.cellSizeBlocks() / viewport.cellY();
   }

   public record Rect(int x, int y, int w, int h) {
   }

   public record Viewport(float x, float y, float w, float h, float cellX, float cellY) {
   }
}
