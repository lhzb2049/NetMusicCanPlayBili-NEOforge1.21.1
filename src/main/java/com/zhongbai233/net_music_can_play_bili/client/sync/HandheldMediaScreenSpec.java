package com.zhongbai233.net_music_can_play_bili.client.sync;

public record HandheldMediaScreenSpec(int portraitWidth, int portraitHeight, int offscreenScale) {
   public HandheldMediaScreenSpec(int portraitWidth, int portraitHeight, int offscreenScale) {
      portraitWidth = Math.max(1, portraitWidth);
      portraitHeight = Math.max(1, portraitHeight);
      offscreenScale = Math.max(1, offscreenScale);
      this.portraitWidth = portraitWidth;
      this.portraitHeight = portraitHeight;
      this.offscreenScale = offscreenScale;
   }

   public int landscapeWidth() {
      return this.portraitHeight;
   }

   public int landscapeHeight() {
      return this.portraitWidth;
   }

   public int targetWidth() {
      return this.portraitWidth * this.offscreenScale;
   }

   public int targetHeight() {
      return this.portraitHeight * this.offscreenScale;
   }
}
