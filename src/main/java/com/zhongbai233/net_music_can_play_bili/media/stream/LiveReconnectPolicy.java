package com.zhongbai233.net_music_can_play_bili.media.stream;

public final class LiveReconnectPolicy {
   public static final long GIVE_UP = -1L;
   private final int maxConsecutiveFailures;
   private final long baseDelayMillis;
   private final long maxDelayMillis;
   private final long healthyStreamMillis;
   private int consecutiveFailures;

   public LiveReconnectPolicy() {
      this(5, 1000L, 15000L, 20000L);
   }

   public LiveReconnectPolicy(int maxConsecutiveFailures, long baseDelayMillis, long maxDelayMillis, long healthyStreamMillis) {
      this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
      this.baseDelayMillis = Math.max(0L, baseDelayMillis);
      this.maxDelayMillis = Math.max(this.baseDelayMillis, maxDelayMillis);
      this.healthyStreamMillis = Math.max(0L, healthyStreamMillis);
   }

   public long onStreamEnded(long streamDurationMillis) {
      if (streamDurationMillis >= this.healthyStreamMillis) {
         this.consecutiveFailures = 0;
         return this.baseDelayMillis;
      } else {
         this.consecutiveFailures++;
         if (this.consecutiveFailures > this.maxConsecutiveFailures) {
            return -1L;
         } else {
            long shift = Math.min(this.consecutiveFailures - 1, 30);
            long delay = this.baseDelayMillis << (int)shift;
            return delay > 0L && delay <= this.maxDelayMillis ? delay : this.maxDelayMillis;
         }
      }
   }

   public int consecutiveFailures() {
      return this.consecutiveFailures;
   }

   public void reset() {
      this.consecutiveFailures = 0;
   }
}
