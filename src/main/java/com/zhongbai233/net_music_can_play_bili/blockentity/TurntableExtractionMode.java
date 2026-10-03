package com.zhongbai233.net_music_can_play_bili.blockentity;

public enum TurntableExtractionMode {
   AFTER_PLAYBACK("after_playback", "播完提取"),
   ALWAYS("always", "自由提取");

   private final String serializedName;
   private final String displayName;

   private TurntableExtractionMode(String serializedName, String displayName) {
      this.serializedName = serializedName;
      this.displayName = displayName;
   }

   public String serializedName() {
      return this.serializedName;
   }

   public String displayName() {
      return this.displayName;
   }

   public TurntableExtractionMode next() {
      return this == AFTER_PLAYBACK ? ALWAYS : AFTER_PLAYBACK;
   }

   public static TurntableExtractionMode byName(String name) {
      for (TurntableExtractionMode mode : values()) {
         if (mode.serializedName.equals(name)) {
            return mode;
         }
      }

      return AFTER_PLAYBACK;
   }
}
