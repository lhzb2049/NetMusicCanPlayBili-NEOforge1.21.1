package com.zhongbai233.net_music_can_play_bili.media.sync;

public record ResolveGeneration(long value) {
   public ResolveGeneration(long value) {
      if (value < 0L) {
         throw new IllegalArgumentException("resolve generation must not be negative: " + value);
      } else {
         this.value = value;
      }
   }

   public static ResolveGeneration initial() {
      return new ResolveGeneration(0L);
   }

   public static ResolveGeneration of(long value) {
      return new ResolveGeneration(value);
   }

   public ResolveGeneration next() {
      return new ResolveGeneration(this.value == Long.MAX_VALUE ? 1L : this.value + 1L);
   }

   @Override
   public String toString() {
      return Long.toString(this.value);
   }
}
