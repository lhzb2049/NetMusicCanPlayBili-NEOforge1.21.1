package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import java.util.Objects;

public final class SubtitleLayout {
   public static final float WORLD_TEXT_SCALE = 0.025F;

   private SubtitleLayout() {
   }

   public static float scrollLineScale(float distance) {
      if (!Float.isFinite(distance)) {
         return 0.56F;
      } else {
         float t = Math.clamp(Math.abs(distance) / 2.0F, 0.0F, 1.0F);
         float eased = t * t * (3.0F - 2.0F * t);
         return 1.0F - eased * 0.44F;
      }
   }

   public static boolean isScrollingMode(String contentMode) {
      return "SCROLL_MAIN".equals(contentMode) || "SCROLL_TRANSLATION".equals(contentMode);
   }

   public static String nextDisplayMode(String contentMode) {
      return switch (contentMode) {
         case "LYRICS" -> "SCROLL_MAIN";
         case "SCROLL_MAIN", "SCROLL_TRANSLATION" -> "FIXED";
         case "FIXED" -> "AI_SUBTITLE";
         case "AI_SUBTITLE" -> "LIVE_TITLE";
         case "LIVE_TITLE" -> "LIVE_ROOM";
         case "LIVE_ROOM" -> "LIVE_STATUS";
         default -> "LYRICS";
      };
   }

   public static String toggleScrollingTrack(String contentMode) {
      return switch (contentMode) {
         case "SCROLL_MAIN" -> "SCROLL_TRANSLATION";
         case "SCROLL_TRANSLATION" -> "SCROLL_MAIN";
         default -> contentMode;
      };
   }

   public static float x(ControlConsoleElement.Alignment alignment, int lineWidth) {
      return switch ((ControlConsoleElement.Alignment)Objects.requireNonNull(alignment, "alignment")) {
         case LEFT -> 0.0F;
         case CENTER -> -lineWidth * 0.5F;
         case RIGHT -> -lineWidth;
      };
   }

   public static int splitWidth(float maxWidth, boolean wrap) {
      return wrap && Float.isFinite(maxWidth) && maxWidth > 0.0F ? Math.max(1, (int)Math.floor(maxWidth)) : Integer.MAX_VALUE;
   }

   public static int multiplyAlpha(int color, float opacity) {
      float normalized = Float.isFinite(opacity) ? Math.clamp(opacity, 0.0F, 1.0F) : 0.0F;
      int alpha = Math.round((color >>> 24) * normalized);
      return color & 16777215 | alpha << 24;
   }
}
