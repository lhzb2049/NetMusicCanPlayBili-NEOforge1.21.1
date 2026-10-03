package com.zhongbai233.net_music_can_play_bili.util;

import java.util.function.Function;

public final class NcpbSystemProperties {
   private NcpbSystemProperties() {
   }

   public static boolean booleanValue(String key, boolean fallback) {
      return booleanValue(key, null, fallback);
   }

   public static boolean booleanValue(String key, String legacyKey, boolean fallback) {
      Boolean value = firstValid(NcpbSystemProperties::parseBoolean, key, legacyKey);
      return value != null ? value : fallback;
   }

   public static String stringValue(String key, String fallback) {
      return stringValue(key, null, fallback);
   }

   public static String stringValue(String key, String legacyKey, String fallback) {
      String value = firstValid(Function.identity(), key, legacyKey);
      return value != null ? value : fallback;
   }

   public static int intValue(String key, int fallback) {
      return intValue(key, null, fallback);
   }

   public static int intValue(String key, String legacyKey, int fallback) {
      Integer value = firstValid(Integer::valueOf, key, legacyKey);
      return value != null ? value : fallback;
   }

   public static long longValue(String key, long fallback) {
      return longValue(key, null, fallback);
   }

   public static long longValue(String key, String legacyKey, long fallback) {
      Long value = firstValid(Long::valueOf, key, legacyKey);
      return value != null ? value : fallback;
   }

   public static float floatValue(String key, float fallback) {
      return floatValue(key, null, fallback);
   }

   public static float floatValue(String key, String legacyKey, float fallback) {
      Float value = firstValid(NcpbSystemProperties::parseFiniteFloat, key, legacyKey);
      return value != null ? value : fallback;
   }

   public static double doubleValue(String key, double fallback) {
      return doubleValue(key, null, fallback);
   }

   public static double doubleValue(String key, String legacyKey, double fallback) {
      Double value = firstValid(NcpbSystemProperties::parseFiniteDouble, key, legacyKey);
      return value != null ? value : fallback;
   }

   private static Boolean parseBoolean(String raw) {
      if ("true".equalsIgnoreCase(raw)) {
         return true;
      } else if ("false".equalsIgnoreCase(raw)) {
         return false;
      } else {
         throw new IllegalArgumentException("not a boolean");
      }
   }

   private static Double parseFiniteDouble(String raw) {
      double value = Double.parseDouble(raw);
      if (!Double.isFinite(value)) {
         throw new NumberFormatException("non-finite double");
      } else {
         return value;
      }
   }

   private static Float parseFiniteFloat(String raw) {
      float value = Float.parseFloat(raw);
      if (!Float.isFinite(value)) {
         throw new NumberFormatException("non-finite float");
      } else {
         return value;
      }
   }

   private static <T> T firstValid(Function<String, T> parser, String... keys) {
      for (String key : keys) {
         if (key != null && !key.isBlank()) {
            String raw = System.getProperty(key);
            if (raw != null && !raw.isBlank()) {
               try {
                  return parser.apply(raw.trim());
               } catch (IllegalArgumentException var8) {
               }
            }
         }
      }

      return null;
   }
}
