package com.zhongbai233.net_music_can_play_bili.client.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

public final class ClientMP4QueueTooltip implements ClientTooltipComponent {
   private static final int MAX_VISIBLE_ROWS = 8;
   private static final int PADDING_X = 7;
   private static final int PADDING_Y = 6;
   private static final int ROW_HEIGHT = 12;
   private static final int MIN_WIDTH = 118;
   private final MP4QueueTooltip tooltip;

   public ClientMP4QueueTooltip(MP4QueueTooltip tooltip) {
      this.tooltip = tooltip;
   }

   public int getHeight() {
      return 25 + Math.min(8, this.tooltip.titles().size()) * 12;
   }

   public int getWidth(Font font) {
      int width = font.width("MP4 播放队列");

      for (String title : this.tooltip.titles()) {
         width = Math.max(width, font.width(displayTitle(title)) + 18);
      }

      return Math.max(118, width + 14);
   }

   public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
      int width = this.getWidth(font);
      int componentHeight = this.getHeight();
      graphics.fill(x, y, x + width, y + componentHeight, -267385067);
      graphics.renderOutline(x, y, width, componentHeight, -9614686);
      graphics.drawString(font, "MP4 播放队列", x + 7, y + 6, -4466433);
      graphics.drawString(font, this.tooltip.titles().size() + " 首", x + width - 7 - font.width(this.tooltip.titles().size() + " 首"), y + 6, -7629654);
      int rows = Math.min(8, this.tooltip.titles().size());
      int selected = Math.max(0, Math.min(Math.max(0, this.tooltip.titles().size() - 1), this.tooltip.selectedIndex()));
      int first = Math.max(0, Math.min(Math.max(0, this.tooltip.titles().size() - rows), selected - rows / 2));
      int listY = y + 6 + 15;

      for (int row = 0; row < rows; row++) {
         int index = first + row;
         int rowY = listY + row * 12;
         boolean active = index == selected;
         if (active) {
            graphics.fill(x + 4, rowY - 1, x + width - 4, rowY + 12 - 1, -14271649);
            graphics.renderOutline(x + 4, rowY - 1, width - 8, 12, -9123841);
         }

         graphics.drawString(font, active ? "▶" : "•", x + 7, rowY, active ? -9123841 : -10918788);
         graphics.drawString(font, displayTitle(this.tooltip.titles().get(index)), x + 7 + 13, rowY, active ? -1379585 : -3616544);
      }

      if (this.tooltip.titles().size() > rows) {
         int trackX = x + width - 6;
         int trackH = rows * 12 - 2;
         int maxFirst = Math.max(1, this.tooltip.titles().size() - rows);
         int thumbH = Math.max(9, trackH * rows / this.tooltip.titles().size());
         int thumbY = listY + (trackH - thumbH) * first / maxFirst;
         graphics.fill(trackX, listY, trackX + 2, listY + trackH, -13880507);
         graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, -9123841);
      }
   }

   private static String displayTitle(String title) {
      if (title != null && !title.isBlank()) {
         return title.length() > 18 ? title.substring(0, 17) + "…" : title;
      } else {
         return "未命名唱片";
      }
   }
}
