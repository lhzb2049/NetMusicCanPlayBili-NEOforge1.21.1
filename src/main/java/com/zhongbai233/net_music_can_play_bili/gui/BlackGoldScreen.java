package com.zhongbai233.net_music_can_play_bili.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public abstract class BlackGoldScreen extends Screen {
   public static final int GOLD = -2840509;
   public static final int GOLD_DIM = -9744622;
   public static final int GOLD_GLOW = 819243075;
   public static final int BG_BLACK = -15921907;
   public static final int BG_HEADER = -14935012;
   public static final int TEXT_PRIMARY = -2041656;
   public static final int TEXT_SECONDARY = -6252408;
   public static final int TEXT_DIM = -10463160;
   protected static final int BOX_W = 320;
   protected static final int BOX_H = 310;
   protected static final int HEADER_H = 28;
   protected static final int CLOSE_SIZE = 14;
   protected static final int PAD = 16;
   protected static final int SLIDER_W = 145;
   protected static final int SLIDER_H = 20;
   protected static final int VAL_W = 42;
   protected final BlockPos blockPos;
   private boolean closeHovered;

   protected BlackGoldScreen(Component title, BlockPos blockPos) {
      super(title);
      this.blockPos = blockPos.immutable();
   }

   protected int boxH() {
      return 310;
   }

   protected abstract void buildWidgets();

   protected abstract void onSave();

   protected void init() {
      this.buildWidgets();
   }

   public void onClose() {
      this.onSave();
      if (this.minecraft != null) {
         this.minecraft.setScreen(null);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
      BlackGoldUi.drawBackground(g, this.width, this.height);
   }

   public void render(GuiGraphics g, int mx, int my, float pt) {
      this.renderBackground(g, mx, my, pt);
      int bx = this.boxX();
      int by = this.boxY();
      List<BlackGoldScreen.BreakoutImpact> impacts = this.collectSliderBreakoutImpacts(bx, by);
      this.drawBox(g, bx, by);
      this.drawHeader(g, bx, by, mx, my);
      this.drawContent(g, bx, by, mx, my);
      this.renderWidgets(g, mx, my, pt);
      this.drawBreakoutBorderGaps(g, impacts);
      this.drawSliderOverlays(g);
   }

   protected final void renderWidgets(GuiGraphics g, int mx, int my, float pt) {
      for (Renderable renderable : this.renderables) {
         renderable.render(g, mx, my, pt);
      }
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int bx = this.boxX();
      int by = this.boxY();
      int cx = bx + 320 - 14 - 8;
      int cy = by + 7;
      if (mouseX >= cx && mouseX <= cx + 14 && mouseY >= cy && mouseY <= cy + 14) {
         this.onClose();
         return true;
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   protected int boxX() {
      return (this.width - 320) / 2;
   }

   protected int boxY() {
      return (this.height - this.boxH()) / 2;
   }

   protected void drawBox(GuiGraphics g, int x, int y) {
      BlackGoldUi.drawPanel(g, x, y, 320, this.boxH());
   }

   protected void drawHeader(GuiGraphics g, int bx, int by, int mx, int my) {
      BlackGoldUi.drawHeader(g, this.font, this.getTitle(), bx, by, 320, 28);
      int cx = bx + 320 - 14 - 8;
      int cy = by + 7;
      this.closeHovered = mx >= cx && mx <= cx + 14 && my >= cy && my <= cy + 14;
      g.drawCenteredString(this.font, Component.literal("✕"), cx + 7, cy + 4, this.closeHovered ? -2840509 : -6252408);
   }

   protected void drawContent(GuiGraphics g, int bx, int by, int mx, int my) {
   }

   protected ConfigSlider addConfigSlider(int x, int y, float min, float max, float cur, Consumer<Float> onApply) {
      return this.addConfigSlider(new ConfigSlider(x, y, 145, 20, min, max, cur, onApply), cur, onApply);
   }

   protected ConfigSlider addConfigSlider(ConfigSlider s, float cur, Consumer<Float> onApply) {
      this.addRenderableWidget(s);
      EditBox box = new EditBox(this.font, s.getX() + 145 + 4, s.getY(), 42, 20, Component.empty());
      box.setValue(ConfigSlider.fmt(cur));
      box.setResponder(txt -> {
         try {
            float v = Float.parseFloat(txt);
            s.setFromValue(v);
            onApply.accept(v);
         } catch (NumberFormatException var4x) {
         }
      });
      this.addRenderableWidget(box);
      s.linkedBox = box;
      return s;
   }

   protected CycleWidget addCycleWidget(int x, int y, List<String> options, int initIndex, Consumer<Integer> onSwitch) {
      CycleWidget w = new CycleWidget(x, y, 145, 20, options, initIndex, onSwitch);
      this.addRenderableWidget(w);
      return w;
   }

   protected void addResetButton(int x, int y, float defaultValue, ConfigSlider slider) {
      this.addRenderableWidget(new BlackGoldButton(x, y, 14, 20, Component.literal("↺"), btn -> {
         slider.setFromValue(defaultValue);
         if (slider.linkedBox != null) {
            slider.linkedBox.setValue(ConfigSlider.fmt(defaultValue));
         }
      }, -2840509));
   }

   protected void drawSliderOverlays(GuiGraphics g) {
      for (GuiEventListener child : this.children()) {
         if (child instanceof ConfigSlider s) {
            this.drawOneSlider(g, s);
         }
      }
   }

   private void drawOneSlider(GuiGraphics g, ConfigSlider s) {
      int x = s.getX();
      int y = s.getY();
      int w = s.getWidth();
      int h = s.getHeight();
      double val = s.getSliderValue();
      int pad = 4;
      int trackY = y + h / 2 - 1;
      int trackH = 3;
      int trackL = x + pad;
      int trackW = w - pad * 2;
      g.fillGradient(trackL, trackY, trackL + trackW, trackY + trackH, -15066598, -15066598);
      int fillW = (int)(val * trackW);
      if (fillW > 0) {
         g.fillGradient(trackL, trackY, trackL + fillW, trackY + trackH, -9744622, -2840509);
      }

      int hx = trackL + fillW;
      int hr = s.isHoveredOrFocused() ? 5 : 4;
      int hc = s.isHoveredOrFocused() ? -2840509 : -2041656;
      g.fillGradient(hx - hr, trackY - hr + 1, hx + hr, trackY + trackH + hr - 1, hc, hc);
      g.fillGradient(hx - 2, trackY - 1, hx + 2, trackY + trackH + 1, -15921907, -15921907);
   }

   private List<BlackGoldScreen.BreakoutImpact> collectSliderBreakoutImpacts(int bx, int by) {
      ArrayList<BlackGoldScreen.BreakoutImpact> impacts = new ArrayList<>();
      int h = this.boxH();
      int leftEdge = bx - 2;
      int rightEdge = bx + 320 + 2;

      for (GuiEventListener child : this.children()) {
         if (child instanceof ConfigSlider s) {
            double val = s.getSliderValue();
            if (!(val >= 0.0) || !(val <= 1.0)) {
               int pad = 4;
               int trackL = s.getX() + pad;
               int trackW = s.getWidth() - pad * 2;
               int hx = trackL + (int)(val * trackW);
               int hr = s.isHoveredOrFocused() ? 5 : 4;
               boolean hitsLeftEdge = val < 0.0 && hx - hr <= leftEdge;
               boolean hitsRightEdge = val > 1.0 && hx + hr >= rightEdge;
               if (hitsLeftEdge || hitsRightEdge) {
                  int y = s.getY() + s.getHeight() / 2;
                  if (y >= by && y <= by + h) {
                     if (hitsRightEdge) {
                        impacts.add(new BlackGoldScreen.BreakoutImpact(false, rightEdge, y));
                     } else {
                        impacts.add(new BlackGoldScreen.BreakoutImpact(true, leftEdge, y));
                     }
                  }
               }
            }
         }
      }

      return impacts;
   }

   private void drawBreakoutBorderGaps(GuiGraphics g, List<BlackGoldScreen.BreakoutImpact> impacts) {
      for (BlackGoldScreen.BreakoutImpact impact : impacts) {
         this.drawBreakoutBorderGap(g, impact.leftSide(), impact.edgeX(), impact.centerY());
      }
   }

   private void drawBreakoutBorderGap(GuiGraphics g, boolean leftSide, int edgeX, int centerY) {
      int holeTop = centerY - 5;
      int holeBottom = centerY + 6;
      int gapW = 2;
      if (leftSide) {
         g.fillGradient(edgeX, holeTop, edgeX + gapW, holeBottom, -15921907, -15921907);
      } else {
         g.fillGradient(edgeX - gapW, holeTop, edgeX, holeBottom, -15921907, -15921907);
      }
   }

   private record BreakoutImpact(boolean leftSide, int edgeX, int centerY) {
   }
}
