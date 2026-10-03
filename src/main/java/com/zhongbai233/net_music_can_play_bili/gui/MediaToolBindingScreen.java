package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolClearBindingPacket;
import com.zhongbai233.net_music_can_play_bili.network.MediaToolConfirmBindingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public class MediaToolBindingScreen extends AbstractContainerScreen<MediaToolBindingMenu> {
   private static final int WIDTH = 220;
   private static final int HEIGHT = 210;

   public MediaToolBindingScreen(MediaToolBindingMenu menu, Inventory inventory, Component title) {
      super(menu, inventory, title);
      this.imageWidth = 220;
      this.imageHeight = 210;
   }

   protected void init() {
      super.init();
      int lx = this.leftPos;
      int ty = this.topPos;
      this.addRenderableWidget(
         new BlackGoldButton(
            lx + 153,
            ty + 62,
            20,
            20,
            Component.literal("↓"),
            button -> PacketDistributor.sendToServer(new MediaToolConfirmBindingPacket(), new CustomPacketPayload[0]),
            -2840509
         )
      );
      this.addRenderableWidget(
         new BlackGoldButton(
            lx + 22,
            ty + 80,
            70,
            20,
            Component.translatable("gui.net_music_can_play_bili.media_tool.clear"),
            button -> PacketDistributor.sendToServer(new MediaToolClearBindingPacket(), new CustomPacketPayload[0]),
            -2840509
         )
      );
   }

   protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
      BlackGoldUi.drawBackground(guiGraphics, this.width, this.height);
      int lx = this.leftPos;
      int ty = this.topPos;
      BlackGoldUi.drawPanel(guiGraphics, lx, ty, this.imageWidth, this.imageHeight);
      BlackGoldUi.drawHeader(guiGraphics, this.font, this.title, lx, ty, this.imageWidth, 26);
      int contentTop = ty + 30;
      int contentBottom = ty + 112;
      guiGraphics.fillGradient(lx + 10, contentTop, lx + this.imageWidth - 10, contentBottom, -1441261544, -1440735208);
      int dividerY = contentBottom + 2;
      guiGraphics.fillGradient(lx + 10, dividerY, lx + this.imageWidth - 10, dividerY + 1, -9744622, -9744622);
      BlackGoldUi.drawSlotFrame(guiGraphics, lx + 42, ty + 42, -9744622);
      BlackGoldUi.drawSlotFrame(guiGraphics, lx + 154, ty + 42, -10057558);
      BlackGoldUi.drawSlotFrame(guiGraphics, lx + 154, ty + 84, -10048905);
      Component targetLabel = Component.translatable(
         ((MediaToolBindingMenu)this.menu).usesManualMp4TargetSlot()
            ? (
               ((MediaToolBindingMenu)this.menu).targetKind() == MediaToolBindingMenu.TargetKind.PAD
                  ? "gui.net_music_can_play_bili.media_tool.pad_input"
                  : "gui.net_music_can_play_bili.media_tool.mp4_input"
            )
            : "gui.net_music_can_play_bili.media_tool.target"
      );
      guiGraphics.drawCenteredString(this.font, targetLabel, lx + 52, ty + 28, -2840509);
      guiGraphics.drawString(
         this.font,
         Component.translatable(
            "gui.net_music_can_play_bili.media_tool.bound_count",
            new Object[]{
               ((MediaToolBindingMenu)this.menu).headphoneBindingCount(),
               ((MediaToolBindingMenu)this.menu).holographicBindingCount(),
               ((MediaToolBindingMenu)this.menu).totalTargetBindingCount()
            }
         ),
         lx + 16,
         ty + 68,
         -6252408,
         false
      );
      guiGraphics.drawCenteredString(this.font, Component.translatable("gui.net_music_can_play_bili.media_tool.input"), lx + 164, ty + 28, -2041656);
      guiGraphics.drawCenteredString(this.font, Component.translatable("gui.net_music_can_play_bili.media_tool.output"), lx + 164, ty + 108, -2041656);
   }

   public void render(GuiGraphics g, int mx, int my, float pt) {
      super.render(g, mx, my, pt);
   }

   protected void renderLabels(GuiGraphics g, int mx, int my) {
   }
}
