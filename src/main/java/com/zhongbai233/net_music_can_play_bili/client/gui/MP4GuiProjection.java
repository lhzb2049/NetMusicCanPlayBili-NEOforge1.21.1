package com.zhongbai233.net_music_can_play_bili.client.gui;

public final class MP4GuiProjection {
   public static final int PORTRAIT_CENTER_X_OFFSET = 112;
   public static final int LANDSCAPE_CENTER_X_OFFSET = 101;
   public static final int CENTER_Y_OFFSET = 0;

   private MP4GuiProjection() {
   }

   public static int centerXOffset() {
      return MP4GuiLayout.isLandscape() ? 101 : 112;
   }

   public static int centerYOffset() {
      return 0;
   }
}
