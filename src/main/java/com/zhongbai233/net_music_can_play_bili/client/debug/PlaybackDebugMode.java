package com.zhongbai233.net_music_can_play_bili.client.debug;

public enum PlaybackDebugMode {
   OFF(false, false),
   UI(true, false),
   RANGE(false, true),
   BOTH(true, true);

   private final boolean hudEnabled;
   private final boolean rangeEnabled;

   private PlaybackDebugMode(boolean hudEnabled, boolean rangeEnabled) {
      this.hudEnabled = hudEnabled;
      this.rangeEnabled = rangeEnabled;
   }

   public boolean hudEnabled() {
      return this.hudEnabled;
   }

   public boolean rangeEnabled() {
      return this.rangeEnabled;
   }

   public boolean enabled() {
      return this.hudEnabled || this.rangeEnabled;
   }
}
