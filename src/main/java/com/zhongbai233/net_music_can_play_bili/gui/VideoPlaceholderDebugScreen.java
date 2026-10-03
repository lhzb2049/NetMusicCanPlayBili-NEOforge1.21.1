package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.port.shim.PortGuiTextures;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class VideoPlaceholderDebugScreen extends Screen {
   private static final int TEX_W = 320;
   private static final int TEX_H = 180;
   private static final ResourceLocation LOADING = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "textures/gui/video_loading/loading_base_phase2.png"
   );
   private static final ResourceLocation IRIS_WARNING = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "textures/gui/video_loading/iris_translucent_warning_base.png"
   );
   private static final ResourceLocation PRIVACY = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "textures/gui/holographic_privacy_overlay.png"
   );
   private static final ResourceLocation PROGRESS_FRAME = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "textures/gui/video_loading/progress_frame_204x10.png"
   );
   private static final ResourceLocation PROGRESS_SEGMENT = ResourceLocation.fromNamespaceAndPath(
      "net_music_can_play_bili", "textures/gui/video_loading/progress_segment_42x6.png"
   );

   public VideoPlaceholderDebugScreen() {
      super(Component.literal("Video Placeholder Debug"));
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      graphics.fill(0, 0, this.width, this.height, -16447733);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int scale = this.width >= 1040 ? 1 : Math.max(1, Math.min(this.width / 360, this.height / 240));
      int drawW = 320 * scale;
      int drawH = 180 * scale;
      int gap = 18;
      int totalW = drawW * 3 + gap * 2;
      int startX = Math.max(8, (this.width - totalW) / 2);
      int y = Math.max(28, (this.height - drawH) / 2);
      this.drawTexture(graphics, LOADING, startX, y, drawW, drawH, "LOADING + PROGRESS");
      this.drawProgress(graphics, startX, y, scale);
      this.drawTexture(graphics, IRIS_WARNING, startX + drawW + gap, y, drawW, drawH, "IRIS WARNING");
      this.drawTexture(graphics, PRIVACY, startX + (drawW + gap) * 2, y, drawW, drawH, "HOLO PRIVACY");
      graphics.drawCenteredString(this.font, Component.literal("Video placeholder textures - press Esc to close"), this.width / 2, 10, -1513240);
   }

   private void drawTexture(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h, String label) {
      graphics.fill(x - 2, y - 2, x + w + 2, y + h + 2, -14998480);
      PortGuiTextures.blitRegion(graphics, texture, x, y, x + w, y + h, 0.0F, 1.0F, 0.0F, 1.0F);
      graphics.drawCenteredString(this.font, Component.literal(label), x + w / 2, y + h + 6, -4331521);
   }

   private void drawProgress(GuiGraphics graphics, int x, int y, int scale) {
      int progressX = x + 58 * scale;
      int progressY = y + 126 * scale;
      PortGuiTextures.blitRegion(graphics, PROGRESS_FRAME, progressX, progressY, progressX + 204 * scale, progressY + 10 * scale, 0.0F, 1.0F, 0.0F, 1.0F);
      int movingX = progressX + 2 * scale + (int)(System.nanoTime() / 12000000L % 158L * scale);
      int movingY = progressY + 2 * scale;
      PortGuiTextures.blitRegion(graphics, PROGRESS_SEGMENT, movingX, movingY, movingX + 42 * scale, movingY + 6 * scale, 0.0F, 1.0F, 0.0F, 1.0F);
   }
}
