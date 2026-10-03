package com.zhongbai233.net_music_can_play_bili.client.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;

public final class MP4GuiLayout {
   public static final int PORTRAIT_W = 96;
   public static final int PORTRAIT_H = 176;
   public static final int LANDSCAPE_W = 176;
   public static final int LANDSCAPE_H = 96;

   private MP4GuiLayout() {
   }

   public static int logicalWidth() {
      return MP4FocusState.landscape() ? 176 : 96;
   }

   public static int logicalHeight() {
      return MP4FocusState.landscape() ? 96 : 176;
   }

   public static boolean isLandscape() {
      return MP4FocusState.landscape();
   }

   public static MP4GuiLayout.GuiRect panelBackground() {
      return new MP4GuiLayout.GuiRect(0, 0, logicalWidth(), logicalHeight());
   }

   public static MP4GuiLayout.GuiRect mediaArea() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(3, 3, 170, 90) : new MP4GuiLayout.GuiRect(11, 23, 74, 72);
   }

   public static MP4GuiLayout.GuiRect progressBar() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(14, 76, 148, 4) : new MP4GuiLayout.GuiRect(10, 116, 75, 5);
   }

   public static MP4GuiLayout.GuiRect playButton() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(12, 78, 18, 14) : new MP4GuiLayout.GuiRect(38, 127, 20, 21);
   }

   public static MP4GuiLayout.GuiRect prevButton() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(43, 79, 15, 13) : new MP4GuiLayout.GuiRect(13, 130, 16, 16);
   }

   public static MP4GuiLayout.GuiRect nextButton() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(70, 79, 15, 13) : new MP4GuiLayout.GuiRect(68, 130, 16, 16);
   }

   public static MP4GuiLayout.GuiRect qualityButton() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(110, 78, 31, 14) : new MP4GuiLayout.GuiRect(75, 4, 13, 9);
   }

   public static MP4GuiLayout.GuiRect orientationButton() {
      return isLandscape() ? new MP4GuiLayout.GuiRect(145, 78, 19, 14) : new MP4GuiLayout.GuiRect(86, 5, 8, 8);
   }

   public static MP4GuiLayout.GuiRect topBar() {
      return new MP4GuiLayout.GuiRect(8, 8, 80, 16);
   }

   public static MP4GuiLayout.GuiRect songTitleLine() {
      return new MP4GuiLayout.GuiRect(12, 93, 72, 7);
   }

   public static MP4GuiLayout.GuiRect artistLine() {
      return new MP4GuiLayout.GuiRect(12, 100, 72, 6);
   }

   public static MP4GuiLayout.GuiRect volumeSlider() {
      return new MP4GuiLayout.GuiRect(24, 154, 58, 5);
   }

   public static MP4GuiLayout.GuiRect volumeKnob() {
      float vol = MP4FocusState.volume();
      int knobX = 24 + Math.round(vol * 58.0F);
      return new MP4GuiLayout.GuiRect(knobX - 2, 151, 5, 10);
   }

   public static MP4GuiLayout.GuiRect shuffleButton() {
      return new MP4GuiLayout.GuiRect(8, 162, 18, 6);
   }

   public static MP4GuiLayout.GuiRect repeatButton() {
      return new MP4GuiLayout.GuiRect(29, 162, 18, 6);
   }

   public static MP4GuiLayout.GuiRect playlistButton() {
      return new MP4GuiLayout.GuiRect(51, 162, 22, 6);
   }

   public static MP4GuiLayout.GuiRect playlistPanel() {
      return new MP4GuiLayout.GuiRect(8, 24, 80, 130);
   }

   public static MP4GuiLayout.GuiRect controlBar() {
      return new MP4GuiLayout.GuiRect(0, 70, 176, 26);
   }

   public static MP4GuiLayout.GuiRect timeDisplay() {
      return new MP4GuiLayout.GuiRect(70, 78, 60, 14);
   }

   public static MP4GuiLayout.GuiRect volumeSliderLandscape() {
      return new MP4GuiLayout.GuiRect(122, 80, 24, 8);
   }

   public static MP4GuiLayout.GuiRect of(int x, int y, int w, int h) {
      return new MP4GuiLayout.GuiRect(x, y, w, h);
   }

   public record GuiRect(int x, int y, int w, int h) {
      public int right() {
         return this.x + this.w;
      }

      public int bottom() {
         return this.y + this.h;
      }

      public int centerX() {
         return this.x + this.w / 2;
      }

      public int centerY() {
         return this.y + this.h / 2;
      }
   }
}
