package com.zhongbai233.net_music_can_play_bili.media.sync;

public final class PlaybackPresentationEnvelope {
   public static final long DURATION_NANOS = 300000000L;
   private boolean presenting;
   private long enteredNanos;

   public synchronized float gain(boolean presentable, long nowNanos) {
      if (!presentable) {
         this.presenting = false;
         this.enteredNanos = 0L;
         return 0.0F;
      } else if (!this.presenting) {
         this.presenting = true;
         this.enteredNanos = nowNanos;
         return 0.0F;
      } else {
         long elapsed = nowNanos - this.enteredNanos;
         if (elapsed <= 0L) {
            return 0.0F;
         } else if (elapsed >= 300000000L) {
            return 1.0F;
         } else {
            float t = (float)elapsed / 3.0E8F;
            return t * t * (3.0F - 2.0F * t);
         }
      }
   }

   public synchronized void reset() {
      this.presenting = false;
      this.enteredNanos = 0L;
   }
}
