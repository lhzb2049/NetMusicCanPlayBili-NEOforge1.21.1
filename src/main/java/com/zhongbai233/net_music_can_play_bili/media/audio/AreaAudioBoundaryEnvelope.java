package com.zhongbai233.net_music_can_play_bili.media.audio;

public final class AreaAudioBoundaryEnvelope {
   public static final long DEFAULT_FADE_IN_NANOS = 650000000L;
   public static final long DEFAULT_FADE_OUT_NANOS = 800000000L;
   private final long fadeInNanos;
   private final long fadeOutNanos;
   private boolean initialized;
   private boolean targetAllowed;
   private float startGain;
   private float targetGain;
   private long transitionStartedNanos;

   public AreaAudioBoundaryEnvelope() {
      this(650000000L, 800000000L);
   }

   public AreaAudioBoundaryEnvelope(long fadeInNanos, long fadeOutNanos) {
      this.fadeInNanos = Math.max(1L, fadeInNanos);
      this.fadeOutNanos = Math.max(1L, fadeOutNanos);
   }

   public synchronized float gain(boolean allowed, long nowNanos) {
      if (!this.initialized) {
         this.initialized = true;
         this.targetAllowed = allowed;
         this.startGain = this.targetGain = allowed ? 1.0F : 0.0F;
         this.transitionStartedNanos = nowNanos;
         return this.targetGain;
      } else {
         float current = this.interpolated(nowNanos);
         if (allowed != this.targetAllowed) {
            this.targetAllowed = allowed;
            this.startGain = current;
            this.targetGain = allowed ? 1.0F : 0.0F;
            this.transitionStartedNanos = nowNanos;
            return current;
         } else {
            return current;
         }
      }
   }

   private float interpolated(long nowNanos) {
      if (this.startGain == this.targetGain) {
         return this.targetGain;
      } else {
         long duration = this.targetGain > this.startGain ? this.fadeInNanos : this.fadeOutNanos;
         float progress = Math.clamp((float)Math.max(0L, nowNanos - this.transitionStartedNanos) / (float)duration, 0.0F, 1.0F);
         float smooth = progress * progress * (3.0F - 2.0F * progress);
         return this.startGain + (this.targetGain - this.startGain) * smooth;
      }
   }
}
