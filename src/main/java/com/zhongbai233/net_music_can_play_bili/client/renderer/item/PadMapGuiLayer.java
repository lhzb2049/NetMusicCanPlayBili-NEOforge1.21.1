package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

final class PadMapGuiLayer {
   private final PadMapLayerTexture mapLayer;

   PadMapGuiLayer(PadMapLayerTexture mapLayer) {
      this.mapLayer = mapLayer;
   }

   void draw(GuiGraphics g, PadGuiViewState view, PadMapProjection.Rect mapRect) {
      PadMapSnapshot renderedMap = this.mapLayer.renderedSnapshotOr(view.map());
      PadMapProjection.Viewport viewport = PadMapProjection.viewport(renderedMap, mapRect, view.playerX(), view.playerZ(), PadMapLayerTexture.CELL_PIXELS);
      this.blitClippedMap(g, this.mapLayer.textureId(view.map()), viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
      this.drawMapOverlayGrid(g, renderedMap, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
      this.drawMapLegend(g, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
      this.drawPins(g, renderedMap, view, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
      this.drawPlayerLocation(g, view, renderedMap, viewport, mapRect.x(), mapRect.y(), mapRect.w(), mapRect.h());
   }

   private void blitClippedMap(GuiGraphics g, ResourceLocation texture, PadMapProjection.Viewport viewport, int clipX, int clipY, int clipW, int clipH) {
      float left = Math.max(viewport.x(), (float)clipX);
      float top = Math.max(viewport.y(), (float)clipY);
      float right = Math.min(viewport.x() + viewport.w(), (float)(clipX + clipW));
      float bottom = Math.min(viewport.y() + viewport.h(), (float)(clipY + clipH));
      if (!(right <= left) && !(bottom <= top)) {
         float u0 = (left - viewport.x()) / viewport.w();
         float u1 = (right - viewport.x()) / viewport.w();
         float vTop = (top - viewport.y()) / viewport.h();
         float vBottom = (bottom - viewport.y()) / viewport.h();
         g.flush();
         RenderSystem.setShaderTexture(0, texture);
         RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
         Matrix4f matrix = g.pose().last().pose();
         BufferBuilder builder = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
         builder.addVertex(matrix, left, bottom, 0.0F).setUv(u0, vBottom).setColor(-1);
         builder.addVertex(matrix, right, bottom, 0.0F).setUv(u1, vBottom).setColor(-1);
         builder.addVertex(matrix, right, top, 0.0F).setUv(u1, vTop).setColor(-1);
         builder.addVertex(matrix, left, top, 0.0F).setUv(u0, vTop).setColor(-1);
         BufferUploader.drawWithShader(builder.buildOrThrow());
      }
   }

   private void drawMapOverlayGrid(GuiGraphics g, PadMapSnapshot map, PadMapProjection.Viewport viewport, int clipX, int clipY, int clipW, int clipH) {
      float cell = Math.min(viewport.cellX(), viewport.cellY());
      float chunkPixels = cell * 16.0F / Math.max(1, map.cellSizeBlocks());
      if (!(chunkPixels < 4.0F)) {
         int color = 1035519437;
         float worldX0 = PadMapProjection.screenToWorldX(clipX, map, viewport);
         float worldX1 = PadMapProjection.screenToWorldX(clipX + clipW, map, viewport);
         float worldZ0 = PadMapProjection.screenToWorldZ(clipY, map, viewport);
         float worldZ1 = PadMapProjection.screenToWorldZ(clipY + clipH, map, viewport);
         int minWorldX = this.ceilToChunk(Math.min(worldX0, worldX1));
         int maxWorldX = this.floorToChunk(Math.max(worldX0, worldX1));
         int minWorldZ = this.ceilToChunk(Math.min(worldZ0, worldZ1));
         int maxWorldZ = this.floorToChunk(Math.max(worldZ0, worldZ1));

         for (int worldZ = minWorldZ; worldZ <= maxWorldZ; worldZ += 16) {
            int lineY = Math.round(PadMapProjection.mapScreenY(worldZ, map, viewport));
            if (lineY >= clipY && lineY < clipY + clipH) {
               this.fillClipped(g, clipX, lineY, clipW, 1, color, clipX, clipY, clipW, clipH);
            }
         }

         for (int worldX = minWorldX; worldX <= maxWorldX; worldX += 16) {
            int lineX = Math.round(PadMapProjection.mapScreenX(worldX, map, viewport));
            if (lineX >= clipX && lineX < clipX + clipW) {
               this.fillClipped(g, lineX, clipY, 1, clipH, color, clipX, clipY, clipW, clipH);
            }
         }
      }
   }

   private int ceilToChunk(float world) {
      return (int)Math.ceil(world / 16.0F) * 16;
   }

   private int floorToChunk(float world) {
      return (int)Math.floor(world / 16.0F) * 16;
   }

   private void drawMapLegend(GuiGraphics g, int x, int y, int w, int h) {
      PadMapTileKind[] kinds = new PadMapTileKind[]{
         PadMapTileKind.WATER,
         PadMapTileKind.GRASS,
         PadMapTileKind.INDOOR_FLOOR,
         PadMapTileKind.TREE,
         PadMapTileKind.BUILDING,
         PadMapTileKind.FARMLAND,
         PadMapTileKind.ROCK
      };
      int rowH = 9;
      int boxW = 54;
      int boxH = kinds.length * rowH + 7;
      int lx = x + 5;
      int ly = y + h - boxH - 5;
      this.fill(g, lx, ly, boxW, boxH, 2047877171);
      this.outline(g, lx, ly, boxW, boxH, 1717090792);

      for (int i = 0; i < kinds.length; i++) {
         PadMapTileKind kind = kinds[i];
         int cy = ly + 4 + i * rowH;
         this.drawLegendSwatch(g, kind, lx + 4, cy + 2);
         String label = kind.label();
         this.text(g, lx + 13, cy, label, -287705857);
      }
   }

   private void drawLegendSwatch(GuiGraphics g, PadMapTileKind kind, int x, int y) {
      this.fill(g, x, y, 5, 5, kind.color());
      this.outline(g, x - 1, y - 1, 7, 7, -1711276033);
   }

   private void drawPlayerLocation(GuiGraphics g, PadGuiViewState view, PadMapSnapshot map, PadMapProjection.Viewport viewport, int ox, int oy, int w, int h) {
      int px = Math.round(PadMapProjection.mapScreenX(view.playerX(), map, viewport));
      int pz = Math.round(PadMapProjection.mapScreenY(view.playerZ(), map, viewport));
      this.drawLocationArrow(g, this.clamp(px, ox + 9, ox + w - 9), this.clamp(pz, oy + 9, oy + h - 9), view.playerYaw());
   }

   private void drawPins(GuiGraphics g, PadMapSnapshot map, PadGuiViewState view, PadMapProjection.Viewport viewport, int ox, int oy, int w, int h) {
      for (PadTriggerPoint point : view.document().triggerPoints()) {
         if (!view.document().locked() || point.visible()) {
            boolean draggingThis = point.pointId().equals(PadFocusState.draggingPointId());
            int px = draggingThis && PadFocusState.draggingPointTextureX() >= 0
               ? PadFocusState.draggingPointTextureX()
               : Math.round(PadMapProjection.mapScreenX((float)point.x(), map, viewport));
            int pz = draggingThis && PadFocusState.draggingPointTextureY() >= 0
               ? PadFocusState.draggingPointTextureY()
               : Math.round(PadMapProjection.mapScreenY((float)point.z(), map, viewport));
            if (draggingThis) {
               this.fill(g, px - 12, pz - 17, 25, 31, 872403302);
               this.outline(g, px - 12, pz - 17, 25, 31, -11930);
            }

            this.drawPoi(g, px, pz, point.name().isBlank() ? "点位" : point.name(), point.visible(), PadFocusState.selectedPoint(point.pointId()));
         }
      }

      this.drawMediaDragPreview(g, ox, oy, w, h);
   }

   private void drawMediaDragPreview(GuiGraphics g, int ox, int oy, int w, int h) {
      if (PadFocusState.draggingMedia() && PadFocusState.hoverControl("MAP")) {
         int px = this.clamp(PadFocusState.hoverTextureX(), ox + 6, ox + w - 6);
         int pz = this.clamp(PadFocusState.hoverTextureY(), oy + 10, oy + h - 8);
         this.fill(g, px - 12, pz - 17, 25, 31, 1157615974);
         this.outline(g, px - 12, pz - 17, 25, 31, -11930);
         this.drawPoi(g, px, pz, PadFocusState.draggingMediaName(), true, true);
      }
   }

   private void drawPoi(GuiGraphics g, int x, int y, String label, boolean visible, boolean selected) {
      int fillColor = visible ? -1553825 : -2004971869;
      int textColor = visible ? -1553825 : -6510410;
      if (selected) {
         this.outline(g, x - 9, y - 14, 19, 25, -11930);
         this.fill(g, x - 8, y - 13, 17, 23, 872403302);
      }

      this.fill(g, x - 6, y - 11, 13, 13, -1);
      this.fill(g, x - 4, y - 9, 9, 9, fillColor);
      this.fill(g, x - 1, y + 1, 3, 7, fillColor);
      Font font = Minecraft.getInstance().font;
      String text = label.length() > 5 ? label.substring(0, 5) : label;
      int w = font.width(text);
      this.fill(g, x + 8, y - 10, w + 6, 12, -1427246347);
      g.drawString(font, text, x + 11, y - 8, textColor, false);
   }

   private void drawLocationArrow(GuiGraphics g, int x, int y, float yawDegrees) {
      double yaw = Math.toRadians(yawDegrees);
      float forwardX = (float)Math.sin(yaw);
      float forwardY = (float)(-Math.cos(yaw));
      float rightX = -forwardY;
      this.drawNavigationArrow(g, x, y, forwardX, forwardY, rightX, forwardX, 7.6F, 5.4F, 3.2F, -1);
      this.drawNavigationArrow(g, x, y, forwardX, forwardY, rightX, forwardX, 6.1F, 4.0F, 2.4F, -14448385);
      this.fillTriangle(
         g,
         x - forwardX * 0.6F - rightX * 1.3F,
         y - forwardY * 0.6F - forwardX * 1.3F,
         x - forwardX * 0.6F + rightX * 1.3F,
         y - forwardY * 0.6F + forwardX * 1.3F,
         x - forwardX * 3.3F,
         y - forwardY * 3.3F,
         -15378492
      );
   }

   private void drawNavigationArrow(
      GuiGraphics g, int x, int y, float forwardX, float forwardY, float rightX, float rightY, float length, float halfWidth, float notchDepth, int color
   ) {
      float tipX = x + forwardX * length;
      float tipY = y + forwardY * length;
      float baseCenterX = x - forwardX * length * 0.62F;
      float baseCenterY = y - forwardY * length * 0.62F;
      float leftX = baseCenterX - rightX * halfWidth;
      float leftY = baseCenterY - rightY * halfWidth;
      float rightBaseX = baseCenterX + rightX * halfWidth;
      float rightBaseY = baseCenterY + rightY * halfWidth;
      float notchX = x - forwardX * notchDepth;
      float notchY = y - forwardY * notchDepth;
      this.fillTriangle(g, tipX, tipY, leftX, leftY, notchX, notchY, color);
      this.fillTriangle(g, tipX, tipY, notchX, notchY, rightBaseX, rightBaseY, color);
   }

   private void fillTriangle(GuiGraphics g, float ax, float ay, float bx, float by, float cx, float cy, int color) {
      int minX = (int)Math.floor(Math.min(ax, Math.min(bx, cx)));
      int maxX = (int)Math.ceil(Math.max(ax, Math.max(bx, cx)));
      int minY = (int)Math.floor(Math.min(ay, Math.min(by, cy)));
      int maxY = (int)Math.ceil(Math.max(ay, Math.max(by, cy)));

      for (int py = minY; py <= maxY; py++) {
         int runStart = Integer.MIN_VALUE;

         for (int px = minX; px <= maxX; px++) {
            boolean inside = this.pointInTriangle(px + 0.5F, py + 0.5F, ax, ay, bx, by, cx, cy);
            if (inside && runStart == Integer.MIN_VALUE) {
               runStart = px;
            } else if (!inside && runStart != Integer.MIN_VALUE) {
               this.fill(g, runStart, py, px - runStart, 1, color);
               runStart = Integer.MIN_VALUE;
            }
         }

         if (runStart != Integer.MIN_VALUE) {
            this.fill(g, runStart, py, maxX - runStart + 1, 1, color);
         }
      }
   }

   private boolean pointInTriangle(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
      float d1 = this.sign(px, py, ax, ay, bx, by);
      float d2 = this.sign(px, py, bx, by, cx, cy);
      float d3 = this.sign(px, py, cx, cy, ax, ay);
      boolean hasNeg = d1 < 0.0F || d2 < 0.0F || d3 < 0.0F;
      boolean hasPos = d1 > 0.0F || d2 > 0.0F || d3 > 0.0F;
      return !hasNeg || !hasPos;
   }

   private float sign(float px, float py, float ax, float ay, float bx, float by) {
      return (px - bx) * (ay - by) - (ax - bx) * (py - by);
   }

   private void fillClipped(GuiGraphics g, int x, int y, int w, int h, int color, int clipX, int clipY, int clipW, int clipH) {
      int left = Math.max(x, clipX);
      int top = Math.max(y, clipY);
      int right = Math.min(x + Math.max(0, w), clipX + clipW);
      int bottom = Math.min(y + Math.max(0, h), clipY + clipH);
      if (right > left && bottom > top) {
         this.fill(g, left, top, right - left, bottom - top, color);
      }
   }

   private void fill(GuiGraphics g, int x, int y, int w, int h, int color) {
      g.fill(x, y, x + w, y + h, color);
   }

   private void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
      g.renderOutline(x, y, w, h, color);
   }

   private void text(GuiGraphics g, int x, int y, String value, int color) {
      g.drawString(Minecraft.getInstance().font, value, x, y, color, false);
   }

   private int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }
}
