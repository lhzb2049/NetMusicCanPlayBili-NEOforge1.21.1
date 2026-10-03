package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class BlackGoldUi {
   public static final int GOLD = -2840509;
   public static final int GOLD_DIM = -9744622;
   public static final int GOLD_GLOW = 819243075;
   public static final int BG_BLACK = -15921907;
   public static final int BG_HEADER = -14935012;
   public static final int TEXT_PRIMARY = -2041656;
   public static final int TEXT_SECONDARY = -6252408;
   public static final int TEXT_DIM = -10463160;

   private BlackGoldUi() {
   }

   public static String ellipsize(Font font, String text, int maxWidth) {
      String safe = text == null ? "" : text;
      if (maxWidth <= 0) {
         return "";
      } else if (font.width(safe) <= maxWidth) {
         return safe;
      } else {
         String ellipsis = "…";
         int ellipsisWidth = font.width(ellipsis);
         if (ellipsisWidth > maxWidth) {
            return "";
         } else {
            StringBuilder result = new StringBuilder();
            int width = 0;
            int offset = 0;

            while (offset < safe.length()) {
               int codePoint = safe.codePointAt(offset);
               String glyph = new String(Character.toChars(codePoint));
               int glyphWidth = font.width(glyph);
               if (width + glyphWidth + ellipsisWidth > maxWidth) {
                  break;
               }

               result.append(glyph);
               width += glyphWidth;
               offset += Character.charCount(codePoint);
            }

            return result.append(ellipsis).toString();
         }
      }
   }

   public static void drawBackground(GuiGraphics g, int width, int height) {
      g.fillGradient(0, 0, width, height, -872415232, -586873595);
   }

   public static void drawPanel(GuiGraphics g, int x, int y, int width, int height) {
      g.fillGradient(x - 2, y - 2, x + width + 2, y + height + 2, 819243075, 819243075);
      g.fillGradient(x, y, x + width, y + height, -15921907, -15921907);
      g.fillGradient(x + 1, y + 1, x + width - 1, y + 2, 1090519039, 553648127);
   }

   public static void drawHeader(GuiGraphics g, Font font, Component title, int x, int y, int width, int headerHeight) {
      g.fillGradient(x + 1, y + 1, x + width - 1, y + headerHeight, -14935012, -14935012);
      g.fillGradient(x + 8, y + headerHeight - 1, x + width - 8, y + headerHeight, -9744622, -9744622);
      g.drawCenteredString(font, title, x + width / 2, y + 9, -2840509);
   }

   public static void drawSlotFrame(GuiGraphics g, int x, int y, int accentColor) {
      g.fillGradient(x - 2, y - 2, x + 20, y + 20, 1713381408, 1713381408);
      g.fill(x - 1, y - 1, x + 19, y + 19, accentColor);
      g.fill(x, y, x + 18, y + 18, -14671840);
      g.fillGradient(x, y, x + 18, y + 1, 1090519039, 553648127);
   }
}
