package com.zhongbai233.net_music_can_play_bili.client;

import java.util.Locale;

public final class VideoFeatureFlags {
   public static final boolean ADVANCED_FEATURES = VideoFeatureProperties.advancedFeaturesEnabled();

   private VideoFeatureFlags() {
   }

   public static boolean benchFeaturesEnabled() {
      return ADVANCED_FEATURES && VideoFeatureProperties.benchFeaturesEnabled();
   }

   public static boolean advancedBoolean(String key, boolean defaultValue) {
      return ADVANCED_FEATURES ? VideoFeatureProperties.booleanValue(key, defaultValue) : defaultValue;
   }

   public static int advancedInt(String key, int defaultValue) {
      return ADVANCED_FEATURES ? VideoFeatureProperties.intValue(key, defaultValue) : defaultValue;
   }

   public static long advancedLong(String key, long defaultValue) {
      return ADVANCED_FEATURES ? VideoFeatureProperties.longValue(key, defaultValue) : defaultValue;
   }

   public static String advancedString(String key, String defaultValue) {
      return ADVANCED_FEATURES ? VideoFeatureProperties.stringValue(key, defaultValue) : defaultValue;
   }

   public static String[] autoHwaccelCandidates() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("win")) {
         return new String[]{"d3d11va", "dxva2", "cuda", "qsv", "none"};
      } else {
         return !os.contains("mac") && !os.contains("darwin") ? new String[]{"vaapi", "cuda", "qsv", "none"} : new String[]{"videotoolbox", "none"};
      }
   }

   public static String[] requestedHwaccelCandidates() {
      if (!ADVANCED_FEATURES) {
         return autoHwaccelCandidates();
      } else {
         String raw = VideoFeatureProperties.nativeHwaccel();
         return !raw.isBlank() && !"auto".equalsIgnoreCase(raw) ? new String[]{raw, "none"} : autoHwaccelCandidates();
      }
   }
}
