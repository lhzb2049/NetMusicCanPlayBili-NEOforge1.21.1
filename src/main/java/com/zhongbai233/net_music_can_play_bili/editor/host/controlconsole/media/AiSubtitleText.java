package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

public final class AiSubtitleText {
   private AiSubtitleText() {
   }

   public static AiSubtitleText.Lines resolve(
      AiSubtitleText.LineLookup aiPrimary,
      AiSubtitleText.LineLookup aiTranslation,
      String fallbackPrimary,
      String fallbackTranslation,
      int mediaTick,
      boolean showTranslation,
      String fixedFallback
   ) {
      String primary = mediaTick >= 0 && aiPrimary != null ? normalize(aiPrimary.lineAt(mediaTick)) : "";
      String translation = showTranslation && mediaTick >= 0 && aiTranslation != null ? normalize(aiTranslation.lineAt(mediaTick)) : "";
      boolean usedFallback = primary.isBlank() && translation.isBlank();
      if (usedFallback) {
         primary = normalize(fallbackPrimary);
         translation = showTranslation ? normalize(fallbackTranslation) : "";
      }

      if (primary.isBlank() && translation.isBlank()) {
         primary = normalize(fixedFallback);
      }

      return new AiSubtitleText.Lines(primary, translation, usedFallback);
   }

   private static String normalize(String value) {
      return value != null ? value : "";
   }

   @FunctionalInterface
   public interface LineLookup {
      String lineAt(int var1);
   }

   public record Lines(String primary, String translation, boolean usedFallback) {
   }
}
