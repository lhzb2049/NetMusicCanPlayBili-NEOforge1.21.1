package com.zhongbai233.net_music_can_play_bili.gui;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public class CycleWidget extends AbstractWidget {
   private final List<String> options;
   private int index;
   private final Consumer<Integer> onSwitch;

   public CycleWidget(int x, int y, int w, int h, List<String> options, int initIndex, Consumer<Integer> onSwitch) {
      super(x, y, w, h, Component.empty());
      this.options = options;
      this.index = Math.clamp((long)initIndex, 0, options.size() - 1);
      this.onSwitch = onSwitch;
   }

   public String currentOption() {
      return this.options.get(this.index);
   }

   protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.isMouseOver(mouseX, mouseY)) {
         this.index = (this.index + 1) % this.options.size();
         this.onSwitch.accept(this.index);
         return true;
      } else {
         return false;
      }
   }

   protected void updateWidgetNarration(NarrationElementOutput o) {
   }
}
