package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.PadRenderProperties;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapStyleProcessor;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadPerfLogger;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor.ABGR32;
import org.slf4j.Logger;

final class PadMapLayerTexture implements AutoCloseable {
   private static final PadRenderProperties.MapLayer PROPERTIES = PadRenderProperties.mapLayer();
   static final int CELL_PIXELS = PROPERTIES.cellPixels();
   static final int WIDTH = PadMapSampler.DEFAULT_WIDTH * CELL_PIXELS;
   static final int HEIGHT = PadMapSampler.DEFAULT_HEIGHT * CELL_PIXELS;
   static final int VIEW_WIDTH = PadMapSampler.DEFAULT_VIEW_WIDTH * CELL_PIXELS;
   static final int VIEW_HEIGHT = PadMapSampler.DEFAULT_VIEW_HEIGHT * CELL_PIXELS;
   private static final int SCALE = PROPERTIES.scale();
   private static final int TARGET_WIDTH = WIDTH * Math.max(1, SCALE);
   private static final int TARGET_HEIGHT = HEIGHT * Math.max(1, SCALE);
   private static final long MIN_BAKE_INTERVAL_NANOS = PROPERTIES.minBakeIntervalMillis() * 1000000L;
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ResourceLocation textureId;
   private final PadMapBakeScheduler bakeScheduler = new PadMapBakeScheduler(MIN_BAKE_INTERVAL_NANOS);
   private DynamicTexture texture;
   private final PadMapStyleProcessor styleProcessor = new PadMapStyleProcessor();
   private PadMapSnapshot bakedSnapshot;
   private PadMapStyleProcessor.StyledMap bakedStyled;
   private boolean failed;

   PadMapLayerTexture(String key) {
      this.textureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/pad_map_layer_" + key);
   }

   ResourceLocation textureId(PadMapSnapshot snapshot) {
      this.request(snapshot);
      return this.textureId;
   }

   void tick(PadMapSnapshot snapshot) {
      this.renderIfNeeded(snapshot);
   }

   PadMapSnapshot renderedSnapshotOr(PadMapSnapshot fallback) {
      return this.bakeScheduler.renderedSnapshotOr(fallback);
   }

   private void request(PadMapSnapshot snapshot) {
      if (!this.failed && snapshot != null) {
         this.ensureTexture();
      }
   }

   private void renderIfNeeded(PadMapSnapshot snapshot) {
      if (!this.failed && snapshot != null) {
         PadMapBakeScheduler.BakeDecision decision = this.bakeScheduler.request(snapshot, System.nanoTime());
         if (decision.shouldBake()) {
            snapshot = decision.snapshot();

            try {
               this.ensureTexture();
               long started = System.nanoTime();
               this.bake(snapshot);
               this.texture.upload();
               long completed = System.nanoTime();
               PadPerfLogger.recordMapBake(completed - started);
               this.bakeScheduler.complete(snapshot, decision.signature(), completed);
            } catch (RuntimeException var7) {
               this.failed = true;
               LOGGER.warn("Pad 地图纹理烘焙失败: {}", this.textureId, var7);
            }
         }
      }
   }

   private void ensureTexture() {
      if (this.texture == null) {
         this.texture = new DynamicTexture(TARGET_WIDTH, TARGET_HEIGHT, false);
         Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
      }
   }

   private void bake(PadMapSnapshot map) {
      NativeImage image = this.texture.getPixels();
      if (image != null) {
         int width = map.width();
         int height = map.height();
         float cell = Math.max(1.0F, Math.min((float)WIDTH / width, (float)HEIGHT / height));
         int drawW = Math.round(width * cell);
         int drawH = Math.round(height * cell);
         int ox = (WIDTH - drawW) / 2;
         int oy = (HEIGHT - drawH) / 2;
         PadMapStyleProcessor.StyledMap styled = this.styleProcessor.style(map);
         boolean[] repaint = this.prepareReuse(image, map, styled, this.bakedStyled, ox, oy, cell, cell);
         this.drawAreaMask(image, styled.farmlandArea(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -2042441);
         this.drawAreaMask(image, styled.greenArea(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -4402254);
         this.drawAreaMask(image, styled.waterArea(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -6439474);
         this.drawAreaMask(image, styled.indoorFloor(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -1515824);
         this.drawAreaMask(image, styled.buildingZone(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -3028029);
         this.drawAreaMask(image, styled.buildingCore(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -4278354);
         this.drawLineMask(image, styled.waterLine(), repaint, styled.width(), styled.height(), ox, oy, cell, cell, -7950396, 1);
         this.drawUnknownCells(image, map, repaint, ox, oy, cell, cell);
         this.vignette(image);
         this.bakedSnapshot = map;
         this.bakedStyled = styled;
      }
   }

   private boolean[] prepareReuse(
      NativeImage image,
      PadMapSnapshot map,
      PadMapStyleProcessor.StyledMap styled,
      PadMapStyleProcessor.StyledMap oldStyled,
      int ox,
      int oy,
      float cellX,
      float cellY
   ) {
      int size = map.width() * map.height();
      boolean[] repaint = new boolean[size];
      PadMapRenderReusePlan.Plan plan = PadMapRenderReusePlan.between(this.bakedSnapshot, map);
      if (plan.reusable() && oldStyled != null) {
         int shiftX = Math.round(plan.textureCellShiftX() * cellX) * Math.max(1, SCALE);
         int shiftY = Math.round(plan.textureCellShiftY() * cellY) * Math.max(1, SCALE);
         this.shiftPixels(image, shiftX, shiftY);

         for (int z = 0; z < map.height(); z++) {
            int oldZ = z - plan.textureCellShiftY();

            for (int x = 0; x < map.width(); x++) {
               int oldX = x - plan.textureCellShiftX();
               int index = z * map.width() + x;
               boolean reusable = oldX >= 0 && oldX < map.width() && oldZ >= 0 && oldZ < map.height();
               if (reusable) {
                  int oldIndex = oldZ * map.width() + oldX;
                  reusable = this.outsideVignette(oldX, oldZ, cellX, cellY)
                     && this.sameVisual(styled, index, oldStyled, oldIndex)
                     && map.tile(map.width() - 1 - x, z) == this.bakedSnapshot.tile(map.width() - 1 - oldX, oldZ);
               }

               repaint[index] = !reusable;
               if (!reusable) {
                  this.fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, -1646379);
               }
            }
         }

         return repaint;
      } else {
         Arrays.fill(repaint, true);
         this.fill(image, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, -1646379);
         return repaint;
      }
   }

   private boolean outsideVignette(int x, int z, float cellX, float cellY) {
      int left = Math.round(x * cellX);
      int top = Math.round(z * cellY);
      int right = Math.round((x + 1) * cellX) + 1;
      int bottom = Math.round((z + 1) * cellY) + 1;
      return left >= 5 && top >= 5 && right <= WIDTH - 5 && bottom <= HEIGHT - 5;
   }

   private boolean sameVisual(PadMapStyleProcessor.StyledMap a, int ai, PadMapStyleProcessor.StyledMap b, int bi) {
      return a.greenArea()[ai] == b.greenArea()[bi]
         && a.farmlandArea()[ai] == b.farmlandArea()[bi]
         && a.waterArea()[ai] == b.waterArea()[bi]
         && a.waterLine()[ai] == b.waterLine()[bi]
         && a.buildingZone()[ai] == b.buildingZone()[bi]
         && a.buildingCore()[ai] == b.buildingCore()[bi]
         && a.indoorFloor()[ai] == b.indoorFloor()[bi];
   }

   private void shiftPixels(NativeImage image, int shiftX, int shiftY) {
      int[] source = image.getPixelsRGBA();
      this.fill(image, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, -1646379);

      for (int y = 0; y < TARGET_HEIGHT; y++) {
         int sy = y - shiftY;
         if (sy >= 0 && sy < TARGET_HEIGHT) {
            for (int x = 0; x < TARGET_WIDTH; x++) {
               int sx = x - shiftX;
               if (sx >= 0 && sx < TARGET_WIDTH) {
                  image.setPixelRGBA(x, y, source[sy * TARGET_WIDTH + sx]);
               }
            }
         }
      }
   }

   private void drawUnknownCells(NativeImage image, PadMapSnapshot map, boolean[] repaint, int ox, int oy, float cellX, float cellY) {
      for (int z = 0; z < map.height(); z++) {
         for (int x = 0; x < map.width(); x++) {
            if (repaint[z * map.width() + x] && map.tile(map.width() - 1 - x, z) == PadMapTileKind.UNKNOWN) {
               int color = (x + z & 1) == 0 ? -7232591 : -8416865;
               this.fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, color);
            }
         }
      }
   }

   private void drawAreaMask(NativeImage image, boolean[] mask, boolean[] repaint, int width, int height, int ox, int oy, float cellX, float cellY, int color) {
      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            int i = this.index(width, x, z);
            if (repaint[i] && mask[i]) {
               this.fillCellRun(image, ox, oy, cellX, cellY, x, x + 1, z, color);
            }
         }
      }
   }

   private void drawLineMask(
      NativeImage image, boolean[] mask, boolean[] repaint, int width, int height, int ox, int oy, float cellX, float cellY, int color, int lineWidth
   ) {
      int half = Math.max(0, lineWidth / 2);

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (repaint[this.index(width, x, z)] && mask[this.index(width, x, z)]) {
               int cx = ox + Math.round((x + 0.5F) * cellX);
               int cy = oy + Math.round((z + 0.5F) * cellY);
               this.fillLogical(image, cx - half, cy - half, lineWidth, lineWidth, color);
               if (x + 1 < width && mask[this.index(width, x + 1, z)]) {
                  int nx = ox + Math.round((x + 1.5F) * cellX);
                  this.fillLogical(image, Math.min(cx, nx), cy - half, Math.abs(nx - cx) + lineWidth, lineWidth, color);
               }

               if (z + 1 < height && mask[this.index(width, x, z + 1)]) {
                  int ny = oy + Math.round((z + 1.5F) * cellY);
                  this.fillLogical(image, cx - half, Math.min(cy, ny), lineWidth, Math.abs(ny - cy) + lineWidth, color);
               }
            }
         }
      }
   }

   private int index(int width, int x, int z) {
      return z * width + x;
   }

   private void fillCellRun(NativeImage image, int ox, int oy, float cellX, float cellY, int startX, int endX, int z, int color) {
      int left = ox + Math.round(startX * cellX);
      int top = oy + Math.round(z * cellY);
      int right = ox + Math.round(endX * cellX) + 1;
      int bottom = oy + Math.round((z + 1) * cellY) + 1;
      this.fillLogical(image, left, top, right - left, bottom - top, color);
   }

   private void vignette(NativeImage image) {
      this.fillLogical(image, 0, 0, WIDTH, 1, -1185567);
      this.fillLogical(image, 0, HEIGHT - 1, WIDTH, 1, -3554116);
      this.fillLogical(image, 0, 0, WIDTH, 5, 419430399);
      this.fillLogical(image, 0, HEIGHT - 5, WIDTH, 5, 285212672);
      this.fillLogical(image, 0, 0, 5, HEIGHT, 251658240);
      this.fillLogical(image, WIDTH - 5, 0, 5, HEIGHT, 251658240);
   }

   private void fillLogical(NativeImage image, int x, int y, int w, int h, int color) {
      int scale = Math.max(1, SCALE);
      this.fill(image, x * scale, y * scale, w * scale, h * scale, color);
   }

   private void fill(NativeImage image, int x, int y, int w, int h, int color) {
      int left = Math.max(0, x);
      int top = Math.max(0, y);
      int right = Math.min(TARGET_WIDTH, x + Math.max(0, w));
      int bottom = Math.min(TARGET_HEIGHT, y + Math.max(0, h));
      if (right > left && bottom > top) {
         image.fillRect(left, top, right - left, bottom - top, ABGR32.fromArgb32(color));
      }
   }

   @Override
   public void close() {
      if (this.texture != null) {
         Minecraft.getInstance().getTextureManager().release(this.textureId);
         this.texture = null;
      }
   }
}
