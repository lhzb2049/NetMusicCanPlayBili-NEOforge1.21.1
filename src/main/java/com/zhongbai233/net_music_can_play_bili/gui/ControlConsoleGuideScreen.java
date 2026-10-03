package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ClientPlayerPreferences;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

public final class ControlConsoleGuideScreen extends Screen {
   private static final int PANEL_W = 390;
   private static final int PANEL_H = 238;
   private static final int HEADER_H = 30;
   private final BlockPos consolePos;
   private final Level openingLevel;
   private final LocalPlayer openingPlayer;
   private Checkbox doNotShowAgain;
   private boolean transferringToEditor;

   public ControlConsoleGuideScreen(BlockPos consolePos) {
      super(Component.translatable("gui.net_music_can_play_bili.control_console.guide.title"));
      this.consolePos = Objects.requireNonNull(consolePos, "consolePos").immutable();
      Minecraft minecraft = Minecraft.getInstance();
      this.openingLevel = minecraft.level;
      this.openingPlayer = minecraft.player;
   }

   protected void init() {
      int x = this.panelX();
      int y = this.panelY();
      this.doNotShowAgain = (Checkbox)this.addRenderableWidget(
         Checkbox.builder(Component.translatable("gui.net_music_can_play_bili.control_console.guide.do_not_show_again"), this.font)
            .pos(x + 28, y + 164)
            .selected(false)
            .maxWidth(334)
            .build()
      );
      this.addRenderableWidget(
         new BlackGoldButton(
            x + 28, y + 198, 160, 22, Component.translatable("gui.net_music_can_play_bili.control_console.guide.enter"), button -> this.enterEditor(), -2840509
         )
      );
      this.addRenderableWidget(
         new BlackGoldButton(
            x + 202, y + 198, 160, 22, Component.translatable("gui.net_music_can_play_bili.control_console.guide.cancel"), button -> this.onClose(), -9744622
         )
      );
   }

   public void tick() {
      super.tick();
      if (!this.validHost()) {
         this.onClose();
      } else {
         ControlConsoleClient.tickLease(this.consolePos);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      BlackGoldUi.drawBackground(graphics, this.width, this.height);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics, mouseX, mouseY, partialTick);
      int x = this.panelX();
      int y = this.panelY();
      BlackGoldUi.drawPanel(graphics, x, y, 390, 238);
      BlackGoldUi.drawHeader(graphics, this.font, this.getTitle(), x, y, 390, 30);
      graphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_1"), x + 28, y + 50, -2041656);
      graphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_2"), x + 28, y + 72, -6252408);
      graphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_3"), x + 28, y + 94, -6252408);
      graphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_4"), x + 28, y + 116, -6252408);
      graphics.drawString(this.font, Component.translatable("gui.net_music_can_play_bili.control_console.guide.line_5"), x + 28, y + 138, -10463160);

      for (Renderable renderable : this.renderables) {
         renderable.render(graphics, mouseX, mouseY, partialTick);
      }
   }

   public void onClose() {
      if (!this.transferringToEditor) {
         ControlConsoleClient.releaseLease(this.consolePos);
      }

      if (this.minecraft != null) {
         this.minecraft.setScreen(null);
      }
   }

   private void enterEditor() {
      if (this.validHost() && this.minecraft != null && this.openingPlayer != null) {
         if (this.doNotShowAgain != null && this.doNotShowAgain.selected()) {
            ClientPlayerPreferences.defaults().dismissControlConsoleGuide(this.openingPlayer.getUUID());
         }

         this.transferringToEditor = true;
         ControlConsoleClient.openEditor(this.consolePos);
      } else {
         this.onClose();
      }
   }

   private boolean validHost() {
      return this.minecraft != null
            && this.minecraft.level == this.openingLevel
            && this.minecraft.player == this.openingPlayer
            && this.openingPlayer != null
            && this.openingPlayer.isAlive()
            && this.openingLevel != null
         ? this.openingPlayer.distanceToSqr(this.consolePos.getX() + 0.5, this.consolePos.getY() + 0.5, this.consolePos.getZ() + 0.5) <= 64.0
            && this.openingLevel.hasChunk(Math.floorDiv(this.consolePos.getX(), 16), Math.floorDiv(this.consolePos.getZ(), 16))
            && this.openingLevel.getBlockEntity(this.consolePos) instanceof ControlConsoleBlockEntity
         : false;
   }

   private int panelX() {
      return (this.width - 390) / 2;
   }

   private int panelY() {
      return (this.height - 238) / 2;
   }
}
