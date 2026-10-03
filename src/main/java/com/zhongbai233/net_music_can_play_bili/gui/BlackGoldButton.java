package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.Component;

public class BlackGoldButton extends Button {
   private final int accentColor;

   public BlackGoldButton(int x, int y, int w, int h, Component msg, OnPress onPress, int accent) {
      super(x, y, w, h, msg, onPress, DEFAULT_NARRATION);
      this.accentColor = accent;
   }

   protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
      Font font = Minecraft.getInstance().font;
      int bg;
      int borderC;
      if (!this.active) {
         bg = -15658735;
         borderC = -14540254;
      } else if (this.isHoveredOrFocused()) {
         bg = -14013920;
         borderC = this.accentColor;
      } else {
         bg = -15066598;
         borderC = -13421773;
      }

      g.fillGradient(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg, bg);
      g.fillGradient(this.getX(), this.getY(), this.getX() + 2, this.getY() + this.height, borderC, borderC);
      g.fillGradient(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, borderC, borderC);
      int tc = this.active ? (this.isHoveredOrFocused() ? this.accentColor : -2041656) : -10463160;
      Component visibleMessage = Component.literal(BlackGoldUi.ellipsize(font, this.getMessage().getString(), Math.max(0, this.width - 10)));
      g.drawCenteredString(font, visibleMessage, this.getX() + this.width / 2, this.getY() + (this.height - 8) / 2, tc);
   }
}
