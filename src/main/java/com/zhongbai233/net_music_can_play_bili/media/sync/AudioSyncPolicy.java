package com.zhongbai233.net_music_can_play_bili.media.sync;

public record AudioSyncPolicy(long catchUpStartTicks, long catchUpFullTicks, long outputLagFlushTicks, long fedNearTargetTicks, long flushAheadTicks) {
   public AudioSyncPolicy(long catchUpStartTicks, long catchUpFullTicks, long outputLagFlushTicks, long fedNearTargetTicks, long flushAheadTicks) {
      catchUpStartTicks = Math.max(0L, catchUpStartTicks);
      long minimumFullTicks = catchUpStartTicks == Long.MAX_VALUE ? Long.MAX_VALUE : catchUpStartTicks + 1L;
      catchUpFullTicks = Math.max(minimumFullTicks, catchUpFullTicks);
      outputLagFlushTicks = Math.max(0L, outputLagFlushTicks);
      fedNearTargetTicks = Math.max(0L, fedNearTargetTicks);
      flushAheadTicks = Math.max(0L, flushAheadTicks);
      this.catchUpStartTicks = catchUpStartTicks;
      this.catchUpFullTicks = catchUpFullTicks;
      this.outputLagFlushTicks = outputLagFlushTicks;
      this.fedNearTargetTicks = fedNearTargetTicks;
      this.flushAheadTicks = flushAheadTicks;
   }

   public static AudioSyncPolicy fromSystemProperties() {
      return AudioSyncProperties.policy();
   }

   public boolean isAhead(long fedTicks, long audibleTicks, long targetTicks) {
      return isFiniteTarget(targetTicks) && (fedTicks >= 0L && fedTicks > targetTicks || audibleTicks >= 0L && audibleTicks > targetTicks);
   }

   public boolean shouldFlushAhead(long fedTicks, long audibleTicks, long targetTicks) {
      return isFiniteTarget(targetTicks)
         && (fedTicks >= 0L && fedTicks - targetTicks > this.flushAheadTicks || audibleTicks >= 0L && audibleTicks - targetTicks > this.flushAheadTicks);
   }

   public boolean shouldFlushOutputLag(long audibleTicks, long fedTicks, long targetTicks) {
      return isFiniteTarget(targetTicks) && this.outputLagFlushTicks > 0L && audibleTicks >= 0L && fedTicks >= 0L
         ? targetTicks - audibleTicks > this.outputLagFlushTicks && targetTicks - fedTicks <= this.fedNearTargetTicks
         : false;
   }

   public boolean shouldDropDecodedBacklog(long audibleTicks, long fedTicks, long targetTicks) {
      return isFiniteTarget(targetTicks) && this.outputLagFlushTicks > 0L && audibleTicks >= 0L && fedTicks >= 0L
         ? targetTicks - audibleTicks > this.outputLagFlushTicks
            && targetTicks - fedTicks > this.fedNearTargetTicks
            && fedTicks - audibleTicks > this.outputLagFlushTicks
         : false;
   }

   public int allowedUnits(double budget, int maxPerTick, long fedTicks, long targetTicks) {
      int max = Math.max(0, maxPerTick);
      int base = Math.min(Math.max(0, (int)budget), max);
      if (isFiniteTarget(targetTicks) && fedTicks >= 0L) {
         long behindTicks = targetTicks - fedTicks;
         if (behindTicks <= this.catchUpStartTicks) {
            return base;
         } else {
            double ratio = Math.min(1.0, (double)behindTicks / this.catchUpFullTicks);
            int extra = (int)Math.round((max - base) * ratio);
            return Math.max(base, Math.min(max, base + extra));
         }
      } else {
         return base;
      }
   }

   private static boolean isFiniteTarget(long targetTicks) {
      return targetTicks != Long.MAX_VALUE && targetTicks != Long.MIN_VALUE;
   }
}
