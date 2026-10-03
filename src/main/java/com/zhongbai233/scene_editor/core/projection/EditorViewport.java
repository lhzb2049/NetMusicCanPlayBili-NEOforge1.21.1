package com.zhongbai233.scene_editor.core.projection;

public record EditorViewport(int x, int y, int width, int height) {
   public EditorViewport(int x, int y, int width, int height) {
      if (width > 0 && height > 0) {
         this.x = x;
         this.y = y;
         this.width = width;
         this.height = height;
      } else {
         throw new IllegalArgumentException("viewport dimensions must be positive");
      }
   }

   public double aspectRatio() {
      return (double)this.width / this.height;
   }
}
