package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolReportPacket;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class MediaToolReportScreen extends AbstractContainerScreen<MediaToolReportMenu> {
   private static final int WIDTH = 260;
   private static final int HEIGHT = 220;
   private static final int LIST_TOP = 54;
   private static final int ROW_HEIGHT = 24;
   private int selectedIndex;

   public MediaToolReportScreen(MediaToolReportMenu menu, Inventory inventory, Component title) {
      super(menu, inventory, title);
      this.imageWidth = 260;
      this.imageHeight = 220;
      this.selectedIndex = menu.sources().size() == 1 ? 0 : -1;
   }

   protected void init() {
      super.init();
      this.rebuildButtons();
   }

   private void rebuildButtons() {
      this.clearWidgets();
      List<MediaToolReportMenu.ReportSourceInfo> sources = ((MediaToolReportMenu)this.menu).sources();
      int rows = Math.min(12, sources.size());

      for (int i = 0; i < rows; i++) {
         int index = i;
         MediaToolReportMenu.ReportSourceInfo source = sources.get(i);
         this.addRenderableWidget(
            new BlackGoldButton(this.leftPos + 14, this.topPos + 54 + i * 24, this.imageWidth - 28, 20, this.rowButtonText(source, i), button -> {
               this.selectedIndex = index;
               this.rebuildButtons();
            }, i == this.selectedIndex ? -2840509 : -11184811)
         );
      }

      BlackGoldButton confirm = new BlackGoldButton(
         this.leftPos + this.imageWidth - 104,
         this.topPos + this.imageHeight - 30,
         90,
         20,
         Component.translatable("gui.net_music_can_play_bili.media_tool_report.confirm"),
         button -> this.confirmSelected(),
         -2840509
      );
      confirm.active = this.selectedIndex >= 0 && this.selectedIndex < sources.size();
      this.addRenderableWidget(confirm);
      this.addRenderableWidget(
         new BlackGoldButton(
            this.leftPos + 14, this.topPos + this.imageHeight - 30, 70, 20, Component.translatable("gui.cancel"), button -> this.onClose(), -8947849
         )
      );
   }

   private Component rowButtonText(MediaToolReportMenu.ReportSourceInfo source, int index) {
      String prefix = index == this.selectedIndex ? "▶ " : "  ";
      return Component.literal(prefix + source.kindShortName() + " · " + source.shortSongName() + " · " + source.distanceText());
   }

   private void confirmSelected() {
      List<MediaToolReportMenu.ReportSourceInfo> sources = ((MediaToolReportMenu)this.menu).sources();
      if (this.selectedIndex >= 0 && this.selectedIndex < sources.size()) {
         PacketDistributor.sendToServer(new MediaToolReportPacket(sources.get(this.selectedIndex).key()), new CustomPacketPayload[0]);
      }
   }

   protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
      BlackGoldUi.drawBackground(guiGraphics, this.width, this.height);
      int lx = this.leftPos;
      int ty = this.topPos;
      BlackGoldUi.drawPanel(guiGraphics, lx, ty, this.imageWidth, this.imageHeight);
      BlackGoldUi.drawHeader(guiGraphics, this.font, this.title, lx, ty, this.imageWidth, 26);
      guiGraphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.media_tool_report.subtitle"), lx + 14, ty + 34, -6252408, false);
      if (this.selectedIndex >= 0 && this.selectedIndex < ((MediaToolReportMenu)this.menu).sources().size()) {
         this.drawDetails(guiGraphics, ((MediaToolReportMenu)this.menu).sources().get(this.selectedIndex), lx, ty);
      } else {
         guiGraphics.drawString(
            this.font,
            Component.translatable("gui.net_music_can_play_bili.media_tool_report.select_hint").withStyle(ChatFormatting.GRAY),
            lx + 14,
            ty + this.imageHeight - 54,
            -10463160,
            false
         );
      }
   }

   public void render(GuiGraphics g, int mx, int my, float pt) {
      super.render(g, mx, my, pt);
   }

   private void drawDetails(GuiGraphics g, MediaToolReportMenu.ReportSourceInfo source, int lx, int ty) {
      int y = ty + this.imageHeight - 72;
      g.drawString(this.font, Component.literal(source.kindDisplayName() + " · " + source.shortSongName()), lx + 14, y, -2840509, false);
      g.drawString(
         this.font,
         Component.translatable(
            "gui.net_music_can_play_bili.media_tool_report.detail_line", new Object[]{source.shortOwnerName(), source.progressText(), source.positionText()}
         ),
         lx + 14,
         y + 11,
         -6252408,
         false
      );
      if (!source.rawUrl().isBlank()) {
         g.drawString(this.font, Component.literal(trim(source.rawUrl(), 48)), lx + 14, y + 22, -10463160, false);
      }
   }

   private static String trim(String value, int maxLength) {
      String safe = value != null ? value : "";
      return safe.length() <= maxLength ? safe : safe.substring(0, Math.max(1, maxLength - 1)) + "…";
   }

   protected void renderLabels(GuiGraphics g, int mx, int my) {
   }
}
