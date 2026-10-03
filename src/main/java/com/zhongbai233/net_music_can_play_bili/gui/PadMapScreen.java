package com.zhongbai233.net_music_can_play_bili.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapTileKind;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortGuiTextures;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor.ABGR32;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class PadMapScreen extends Screen {
   private static final int PANEL_W = 360;
   private static final int PANEL_H = 240;
   private static final int HEADER_H = 24;
   private static final int MAP_PAD = 10;
   private static final int SIDEBAR_W = 96;
   private static final int CLOSE_SIZE = 14;
   private final InteractionHand hand;
   private PadMapSnapshot snapshot;
   private int panX;
   private int panZ;
   private float zoom = 1.0F;
   private boolean dragging;
   private double lastMouseX;
   private double lastMouseY;
   private double dragOffsetX;
   private double dragOffsetY;
   private int refreshCooldown;
   private boolean refreshRequested;
   private float lastPartialTick = 1.0F;
   private DynamicTexture mapTexture;
   private ResourceLocation mapTextureId;
   private PadMapSnapshot renderedSnapshot;

   public PadMapScreen(InteractionHand hand) {
      super(Component.translatable("gui.net_music_can_play_bili.pad.map"));
      this.hand = hand;
   }

   public boolean isPauseScreen() {
      return false;
   }

   protected void init() {
      this.centerOnPlayer();
      PadMapClientCache.setManualView(this.panX, this.panZ, this.zoom);
      this.refreshRequested = true;
   }

   public void tick() {
      if (!this.dragging) {
         if (this.refreshCooldown > 0) {
            this.refreshCooldown--;
         }

         if (this.refreshRequested && this.refreshCooldown <= 0) {
            this.refreshRequested = false;
            this.refreshMap(true);
         } else if (this.refreshCooldown <= 0) {
            this.refreshMap(false);
         }
      }
   }

   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      graphics.fillGradient(0, 0, this.width, this.height, -535816416, -267710440);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics, mouseX, mouseY, partialTick);
      this.lastPartialTick = partialTick;
      int x = this.panelX();
      int y = this.panelY();
      this.drawPanel(graphics, x, y, mouseX, mouseY);
      this.drawMap(graphics, this.mapX(), this.mapY(), this.mapW(), this.mapH());
      this.drawSidebar(graphics, x + 360 - 96, y + 24, 96, 216);
   }

   public void onClose() {
      PadMapClientCache.clearManualView();
      this.releaseMapTexture();
      super.onClose();
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int x = this.panelX();
      int y = this.panelY();
      int closeX = x + 360 - 14 - 7;
      int closeY = y + 5;
      if (mouseX >= closeX && mouseX <= closeX + 14 && mouseY >= closeY && mouseY <= closeY + 14) {
         this.onClose();
         return true;
      } else if (button == 0 && this.inMap(mouseX, mouseY)) {
         this.dragging = true;
         this.lastMouseX = mouseX;
         this.lastMouseY = mouseY;
         return true;
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.dragging && button == 0) {
         double dx = mouseX - this.lastMouseX;
         double dy = mouseY - this.lastMouseY;
         this.dragOffsetX += dx;
         this.dragOffsetY += dy;
         this.lastMouseX = mouseX;
         this.lastMouseY = mouseY;
         return true;
      } else {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.dragging && button == 0) {
         this.commitDragOffset();
         this.dragging = false;
         this.refreshRequested = true;
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.inMap(mouseX, mouseY)) {
         this.commitDragOffset();
         this.zoom = Math.max(0.35F, Math.min(4.0F, this.zoom + (float)scrollY * 0.18F));
         PadMapClientCache.setManualView(this.panX, this.panZ, this.zoom);
         this.refreshRequested = true;
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   private void centerOnPlayer() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null) {
         this.panX = minecraft.player.blockPosition().getX();
         this.panZ = minecraft.player.blockPosition().getZ();
      }
   }

   private void refreshMap(boolean immediate) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && (immediate || this.refreshCooldown <= 0)) {
         this.snapshot = PadMapClientCache.snapshot(this.panX, this.panZ);
         this.updateMapTexture(this.snapshot);
         this.refreshCooldown = immediate ? 4 : 20;
      }
   }

   private void updateMapTexture(PadMapSnapshot current) {
      if (current != null && current != this.renderedSnapshot) {
         this.ensureMapTexture(current.width(), current.height());
         if (this.mapTexture != null && this.mapTexture.getPixels() != null) {
            NativeImage image = this.mapTexture.getPixels();
            int width = current.width();
            int height = current.height();

            for (int z = 0; z < height; z++) {
               for (int x = 0; x < width; x++) {
                  PadMapTileKind tile = current.tile(x, z);
                  image.setPixelRGBA(x, z, tile == null ? ABGR32.fromArgb32(PadMapTileKind.UNKNOWN.color()) : ABGR32.fromArgb32(tile.color()));
               }
            }

            this.mapTexture.upload();
            this.renderedSnapshot = current;
         }
      }
   }

   private void ensureMapTexture(int width, int height) {
      boolean sizeMatches = this.mapTexture != null
         && this.mapTexture.getPixels() != null
         && this.mapTexture.getPixels().getWidth() == width
         && this.mapTexture.getPixels().getHeight() == height;
      if (!sizeMatches) {
         this.releaseMapTexture();
         this.mapTextureId = ResourceLocation.fromNamespaceAndPath(
            "net_music_can_play_bili", "dynamic/pad_map_screen_" + this.hand.name().toLowerCase(Locale.ROOT)
         );
         this.mapTexture = new DynamicTexture(width, height, false);
         Minecraft.getInstance().getTextureManager().register(this.mapTextureId, this.mapTexture);
      }
   }

   private void releaseMapTexture() {
      if (this.mapTexture != null) {
         if (this.mapTextureId != null) {
            Minecraft.getInstance().getTextureManager().release(this.mapTextureId);
         } else {
            this.mapTexture.close();
         }

         this.mapTexture = null;
      }

      this.mapTextureId = null;
      this.renderedSnapshot = null;
   }

   private void drawPanel(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
      g.fillGradient(x - 2, y - 2, x + 360 + 2, y + 240 + 2, 1429711359, 1429711359);
      g.fillGradient(x, y, x + 360, y + 240, -15722719, -15986407);
      g.fillGradient(x + 1, y + 1, x + 360 - 1, y + 24, -15261134, -15261134);
      g.drawCenteredString(this.font, this.getTitle(), x + 180, y + 8, -4331521);
      int closeX = x + 360 - 14 - 7;
      int closeY = y + 5;
      boolean hovered = mouseX >= closeX && mouseX <= closeX + 14 && mouseY >= closeY && mouseY <= closeY + 14;
      g.drawCenteredString(this.font, Component.literal("✕"), closeX + 7, closeY + 4, hovered ? -1 : -7429963);
   }

   private void drawMap(GuiGraphics g, int x, int y, int w, int h) {
      g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, -14273983, -14273983);
      g.fillGradient(x, y, x + w, y + h, -14866380, -14866380);
      if (this.snapshot == null) {
         g.drawCenteredString(this.font, Component.literal("Loading map..."), x + w / 2, y + h / 2 - 4, -4601897);
      } else {
         int drawW = this.snapshot.width();
         int drawH = this.snapshot.height();
         float cellPx = Math.max(1.0F, Math.min((float)w / drawW, (float)h / drawH));
         drawW = Math.round(drawW * cellPx);
         drawH = Math.round(drawH * cellPx);
         int originX = x + (w - drawW) / 2;
         int originY = y + (h - drawH) / 2;
         int visualOriginX = originX + (int)Math.round(this.dragOffsetX);
         int visualOriginY = originY + (int)Math.round(this.dragOffsetY);
         if (this.mapTextureId != null) {
            PortGuiTextures.blitRegion(g, this.mapTextureId, visualOriginX, visualOriginY, visualOriginX + drawW, visualOriginY + drawH, 0.0F, 0.0F, 1.0F, 1.0F);
         }

         this.drawGrid(g, visualOriginX, visualOriginY, drawW, drawH, cellPx);
         this.drawPlayerMarker(g, visualOriginX, visualOriginY, cellPx);
         this.drawTriggerPins(g, visualOriginX, visualOriginY, cellPx);
      }
   }

   private void commitDragOffset() {
      if (this.snapshot != null && (!(Math.abs(this.dragOffsetX) < 0.5) || !(Math.abs(this.dragOffsetY) < 0.5))) {
         float pixelsPerBlock = this.pixelsPerBlock(this.snapshot.cellSizeBlocks());
         this.panX = (int)(this.panX - Math.round(this.dragOffsetX / Math.max(0.5F, pixelsPerBlock)));
         this.panZ = (int)(this.panZ - Math.round(this.dragOffsetY / Math.max(0.5F, pixelsPerBlock)));
         this.dragOffsetX = 0.0;
         this.dragOffsetY = 0.0;
         PadMapClientCache.setManualView(this.panX, this.panZ, this.zoom);
      } else {
         this.dragOffsetX = 0.0;
         this.dragOffsetY = 0.0;
      }
   }

   private void drawGrid(GuiGraphics g, int originX, int originY, int drawW, int drawH, float cellPx) {
      if (!(cellPx < 3.0F)) {
         int lineColor = 541940843;

         for (int i = 0; i <= PadMapSampler.DEFAULT_WIDTH; i += 8) {
            int p = originX + Math.round(i * cellPx);
            g.fillGradient(p, originY, p + 1, originY + drawH, lineColor, lineColor);
         }

         for (int i = 0; i <= PadMapSampler.DEFAULT_HEIGHT; i += 8) {
            int q = originY + Math.round(i * cellPx);
            g.fillGradient(originX, q, originX + drawW, q + 1, lineColor, lineColor);
         }
      }
   }

   private void drawPlayerMarker(GuiGraphics g, int originX, int originY, float cellPx) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && this.snapshot != null) {
         double playerX = minecraft.player.xo + (minecraft.player.getX() - minecraft.player.xo) * this.lastPartialTick;
         double playerZ = minecraft.player.zo + (minecraft.player.getZ() - minecraft.player.zo) * this.lastPartialTick;
         int px = this.worldToMapX(playerX, originX, cellPx);
         int pz = this.worldToMapZ(playerZ, originY, cellPx);
         this.drawDiamond(g, px, pz, 5, -1, -14776065);
      }
   }

   private void drawTriggerPins(GuiGraphics g, int originX, int originY, float cellPx) {
      PadDocument document = this.document();
      int index = 1;

      for (PadTriggerPoint point : document.triggerPoints()) {
         int px = this.worldToMapX(point.x(), originX, cellPx);
         int pz = this.worldToMapZ(point.z(), originY, cellPx);
         if (px >= this.mapX() && pz >= this.mapY() && px <= this.mapX() + this.mapW() && pz <= this.mapY() + this.mapH()) {
            this.drawPin(g, px, pz, point.mediaId() > 0 ? -42394 : -14249);
            g.drawCenteredString(this.font, Component.literal(String.valueOf(index++)), px, pz - 18, -1);
         }
      }
   }

   private void drawSidebar(GuiGraphics g, int x, int y, int w, int h) {
      PadDocument document = this.document();
      g.fillGradient(x, y, x + w, y + h, -434823896, -434823896);
      g.drawString(this.font, Component.literal("地图"), x + 8, y + 10, -4331521);
      g.drawString(this.font, Component.literal("缩放 x" + String.format(Locale.ROOT, "%.1f", this.zoom)), x + 8, y + 26, -7429963);
      g.drawString(this.font, Component.literal("媒体 " + document.mediaEntries().size()), x + 8, y + 48, -1515320);
      g.drawString(this.font, Component.literal("点位 " + document.triggerPoints().size()), x + 8, y + 62, -14249);
      g.drawString(this.font, Component.literal(document.locked() ? "已锁定" : "草稿"), x + 8, y + 84, document.locked() ? -14249 : -9444726);
      g.drawString(this.font, Component.literal("拖拽平移"), x + 8, y + h - 42, -8483165);
      g.drawString(this.font, Component.literal("滚轮缩放"), x + 8, y + h - 28, -8483165);
      g.drawString(this.font, Component.literal("点位播放→Pad"), x + 8, y + h - 14, -9448705);
   }

   private void drawDiamond(GuiGraphics g, int x, int y, int radius, int border, int fill) {
      for (int dy = -radius; dy <= radius; dy++) {
         int half = radius - Math.abs(dy);
         g.fillGradient(x - half - 1, y + dy, x + half + 2, y + dy + 1, border, border);
      }

      for (int dy = -radius + 2; dy <= radius - 2; dy++) {
         int half = radius - 2 - Math.abs(dy);
         g.fillGradient(x - half, y + dy, x + half + 1, y + dy + 1, fill, fill);
      }
   }

   private void drawPin(GuiGraphics g, int x, int y, int color) {
      g.fillGradient(x - 4, y - 12, x + 5, y - 3, color, color);
      g.fillGradient(x - 2, y - 3, x + 3, y + 4, color, color);
      g.fillGradient(x - 1, y + 4, x + 2, y + 7, color, color);
      g.fillGradient(x - 2, y - 10, x + 3, y - 5, -1, -1);
   }

   private int worldToMapX(double worldX, int originX, float cellPx) {
      return originX
         + Math.round((float)((worldX - this.snapshot.centerX()) / this.snapshot.cellSizeBlocks() * cellPx) + this.snapshot.width() * cellPx / 2.0F);
   }

   private int worldToMapZ(double worldZ, int originY, float cellPx) {
      return originY
         + Math.round((float)((worldZ - this.snapshot.centerZ()) / this.snapshot.cellSizeBlocks() * cellPx) + this.snapshot.height() * cellPx / 2.0F);
   }

   private float pixelsPerBlock(int cellSize) {
      int mapWidth = this.snapshot != null ? this.snapshot.width() : PadMapSampler.DEFAULT_WIDTH;
      int mapHeight = this.snapshot != null ? this.snapshot.height() : PadMapSampler.DEFAULT_HEIGHT;
      return Math.min((float)this.mapW() / Math.max(1, mapWidth), (float)this.mapH() / Math.max(1, mapHeight)) / Math.max(1, cellSize);
   }

   private boolean inMap(double mouseX, double mouseY) {
      return mouseX >= this.mapX() && mouseX <= this.mapX() + this.mapW() && mouseY >= this.mapY() && mouseY <= this.mapY() + this.mapH();
   }

   private int panelX() {
      return (this.width - 360) / 2;
   }

   private int panelY() {
      return (this.height - 240) / 2;
   }

   private int mapX() {
      return this.panelX() + 10;
   }

   private int mapY() {
      return this.panelY() + 24 + 10;
   }

   private int mapW() {
      return 244;
   }

   private int mapH() {
      return 196;
   }

   private PadDocument document() {
      Player player = Minecraft.getInstance().player;
      if (player == null) {
         return PadDocument.DEFAULT;
      } else {
         ItemStack stack = player.getItemInHand(this.hand);
         return !(stack.getItem() instanceof PadItem) ? PadDocument.DEFAULT : PadItem.readDocument(stack);
      }
   }
}
