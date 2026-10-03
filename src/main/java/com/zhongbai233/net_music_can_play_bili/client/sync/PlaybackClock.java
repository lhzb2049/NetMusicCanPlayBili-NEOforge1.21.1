package com.zhongbai233.net_music_can_play_bili.client.sync;

import net.minecraft.core.BlockPos;

public final class PlaybackClock {
   private PlaybackClock() {
   }

   public static ModernTurntableTimeline.TimelineSnapshot snapshot(BlockPos sourcePos) {
      return ModernTurntableTimeline.snapshot(sourcePos);
   }

   public static long mediaMillis(BlockPos sourcePos) {
      return snapshot(sourcePos).mediaMillis();
   }

   public static long visualMillis(BlockPos sourcePos) {
      return snapshot(sourcePos).visualMillis();
   }

   public static long pacingMillis(BlockPos sourcePos) {
      return snapshot(sourcePos).pacingMillis();
   }

   public static long serverMillis(BlockPos sourcePos) {
      return snapshot(sourcePos).serverMillis();
   }

   public static int mediaTick(BlockPos sourcePos) {
      long millis = mediaMillis(sourcePos);
      return millis < 0L ? -1 : (int)Math.min(2147483647L, Math.max(0L, Math.round(millis / 50.0)));
   }

   public static long relativeNanos(BlockPos sourcePos, long absoluteStartMillis) {
      long millis = mediaMillis(sourcePos);
      return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1000000L;
   }
}
