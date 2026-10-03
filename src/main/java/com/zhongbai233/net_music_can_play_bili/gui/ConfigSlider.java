package com.zhongbai233.net_music_can_play_bili.gui;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class ConfigSlider extends AbstractSliderButton {
   private final float min;
   private final float max;
   private final Consumer<Float> onApply;
   EditBox linkedBox;

   public ConfigSlider(int x, int y, int w, int h, float min, float max, float cur, Consumer<Float> onApply) {
      super(x, y, w, h, Component.empty(), (cur - min) / (max - min));
      this.min = min;
      this.max = max;
      this.onApply = onApply;
   }

   public void setFromValue(float v) {
      this.value = (v - this.min) / (this.max - this.min);
   }

   public double getSliderValue() {
      return this.value;
   }

   protected void updateMessage() {
      this.setMessage(Component.literal(fmt(this.min + (float)this.value * (this.max - this.min))));
   }

   protected void applyValue() {
      float v = this.min + (float)this.value * (this.max - this.min);
      this.onApply.accept(v);
      if (this.linkedBox != null && !this.linkedBox.isFocused()) {
         this.linkedBox.setValue(fmt(v));
      }
   }

   public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
   }

   public static String fmt(float v) {
      return v == (int)v ? String.valueOf((int)v) : String.format("%.1f", v);
   }
}
