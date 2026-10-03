package com.zhongbai233.net_music_can_play_bili.bili;

import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

public class SpeakerAudioRelay {
   private static final int SAMPLES_PER_BLOCK = 256;
   private static final float[] MONO_POS = new float[]{0.0F, 0.0F, 1.0F};
   private static final int MIN_PUMP_PENDING = 4;
   private volatile OpenALSpatialAudio spatialAudio;
   private volatile boolean initialized;
   private volatile boolean started;
   private volatile int channelIndex = -1;
   private volatile boolean autoMixJoc;
   private volatile boolean takeOverMainOutput = true;
   private volatile float userVolume = 1.0F;
   private volatile float rangeGain = 1.0F;
   private volatile float areaGain = 1.0F;
   private volatile float maxDistance = 64.0F;
   private volatile boolean preparationDemand;
   private volatile float[] speakerPos;
   private volatile boolean handlerStarted;
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private volatile boolean paused;
   private int pendingFed = 0;
   private long totalSamplesFed = 0L;
   private long timelineBaselineSamples;
   private int sampleRate = 48000;
   private volatile boolean closed;
   private final AtomicBoolean cleanupStarted = new AtomicBoolean();

   public void setChannelIndex(int idx) {
      this.channelIndex = idx;
   }

   public int getChannelIndex() {
      return this.channelIndex;
   }

   public void setAutoMixJoc(boolean v) {
      this.autoMixJoc = v;
   }

   public boolean isAutoMixJoc() {
      return this.autoMixJoc;
   }

   public boolean hasOutputIntent() {
      return this.channelIndex >= 0 && this.channelIndex <= 11 && this.userVolume > 0.0F;
   }

   public void setTakeOverMainOutput(boolean takeOverMainOutput) {
      this.takeOverMainOutput = takeOverMainOutput;
   }

   public boolean takesOverMainOutput() {
      return this.takeOverMainOutput;
   }

   public void setUserVolume(float v) {
      this.userVolume = AudioPlaybackRange.clampVolume(v);
   }

   public void setRangeGain(float gain) {
      this.rangeGain = AudioUtils.clampGain(gain);
   }

   public void setAreaGain(float gain) {
      this.areaGain = AudioUtils.clampGain(gain);
   }

   public void setPreparationDemand(boolean preparationDemand) {
      this.preparationDemand = preparationDemand;
   }

   public void setMaxDistance(float distance) {
      this.maxDistance = AudioPlaybackRange.normalizeConfiguredDistance(distance);
   }

   public float getMaxDistance() {
      return this.maxDistance;
   }

   public void setSpeakerPos(float[] pos) {
      this.speakerPos = AudioUtils.copyPos3(pos);
   }

   public void setSampleRate(int sr) {
      if (!this.initialized && sr > 0) {
         this.sampleRate = sr;
      }
   }

   public void setHandlerStarted(boolean v) {
      this.handlerStarted = v;
   }

   public void feedChannel(float[] monoPcm) {
      this.feedMono(monoPcm);
   }

   public void feedMono(float[] monoPcm) {
      if (!this.closed && monoPcm != null && monoPcm.length != 0) {
         if (!this.initialized) {
            OpenALSpatialAudio old = this.spatialAudio;
            if (old != null) {
               old.cleanup();
            }

            OpenALSpatialAudio next = new OpenALSpatialAudio();
            if (!next.init(1, 0, this.sampleRate)) {
               next.cleanup();
               this.spatialAudio = null;
               this.initialized = false;
               return;
            }

            this.spatialAudio = next;
            next.setPaused(this.paused);
            this.pendingFed = 0;
            this.totalSamplesFed = this.timelineBaselineSamples;
            this.started = false;
            this.initialized = true;
            if (this.timelineBaselineSamples > 0L) {
               next.flushQueuedAudio(this.timelineBaselineSamples);
            }

            PlaybackLatencyBench.markAudioOpenAlInitialized(this, this.kind(), this.sampleRate);
         }

         OpenALSpatialAudio sa = this.spatialAudio;
         if (sa != null) {
            int numBlocks = monoPcm.length / 256;
            if (numBlocks == 0) {
               numBlocks = 1;
            }

            float[][] w = new float[1][];

            for (int blk = 0; blk < numBlocks; blk++) {
               w[0] = monoPcm;
               sa.updateBedFrameBlock(w, blk * 256);
            }

            this.pendingFed += numBlocks;
            this.totalSamplesFed += monoPcm.length;
            PlaybackLatencyBench.markAudioFed(this, this.kind(), numBlocks, this.totalSamplesFed, this.pendingFed, this.sampleRate);
         }
      }
   }

   public void tick(float[] listenerPos) {
      this.tick(listenerPos, false);
   }

   public void tick(float[] listenerPos, boolean muted) {
      if (!this.closed && this.speakerPos != null) {
         if (this.initialized) {
            OpenALSpatialAudio sa = this.spatialAudio;
            if (sa != null) {
               if (!this.started && this.handlerStarted) {
                  this.started = true;
                  PlaybackLatencyBench.markAudioStarted(this, this.kind(), this.pendingFed, this.totalSamplesFed, this.sampleRate);
               }

               sa.updatePositions(new float[][]{MONO_POS}, new float[0][0], listenerPos, forward(this.speakerPos, listenerPos));
               float geometricGain = muted ? 0.0F : this.rangeAt(listenerPos).gain() * this.rangeGain;
               float entryGain = this.presentationEnvelope.gain(geometricGain > 0.0F, System.nanoTime());
               float g = geometricGain * this.areaGain * entryGain * gameVol();
               sa.setBedGain(0, g);
               if (sa.isDeviceLost()) {
                  sa.cleanup();
                  this.spatialAudio = null;
                  this.initialized = false;
                  this.started = false;
                  this.pendingFed = 0;
               } else if (this.pendingFed >= 4) {
                  sa.pumpQueuedAudio();
               }

               PlaybackLatencyBench.markAudioConsumed(this, this.kind(), sa.getConsumedSamples(), this.sampleRate);
            }
         }
      }
   }

   public void setPaused(boolean paused) {
      this.paused = paused;
      OpenALSpatialAudio sa = this.spatialAudio;
      if (sa != null) {
         sa.setPaused(paused);
      }
   }

   public boolean isStarted() {
      return this.started;
   }

   public boolean isAudibleAt(float[] listenerPos) {
      return this.rangeGain > 0.0F && this.areaGain > 0.001F && this.hasOutputIntent() && this.rangeAt(listenerPos).audible();
   }

   public boolean hasGeometricDemand(float[] listenerPos) {
      return this.rangeGain > 0.0F && this.hasOutputIntent() && this.rangeAt(listenerPos).audible();
   }

   public boolean hasAnticipatedDemand(float[] listenerPos, float[] velocity) {
      float[] currentSpeakerPos = this.speakerPos;
      if (listenerPos == null || velocity == null || currentSpeakerPos == null || !this.hasOutputIntent()) {
         return false;
      } else if (this.preparationDemand) {
         return true;
      } else if (this.rangeGain <= 0.0F) {
         return false;
      } else {
         AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(this.maxDistance, this.userVolume, this.userVolume);
         return PlaybackApproachPredictor.willEnterSphere(
            listenerPos[0],
            listenerPos[1],
            listenerPos[2],
            velocity[0],
            velocity[1],
            velocity[2],
            currentSpeakerPos[0],
            currentSpeakerPos[1],
            currentSpeakerPos[2],
            profile.fadeEndDistance()
         );
      }
   }

   public long getPositionTicks() {
      long millis = this.getPositionMillis();
      return millis >= 0L ? millis * 20L / 1000L : -1L;
   }

   public long getPositionMillis() {
      if (!this.started) {
         return -1L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         if (sa == null) {
            return -1L;
         } else {
            long consumed = sa.getConsumedSamples();
            PlaybackLatencyBench.markAudioConsumed(this, this.kind(), consumed, this.sampleRate);
            return Math.round(consumed * 1000.0 / Math.max(1, this.sampleRate));
         }
      }
   }

   public long getOutputDelayMillis() {
      if (!this.started) {
         return 0L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
         return delaySamples > 0L ? Math.round(delaySamples * 1000.0 / Math.max(1, this.sampleRate)) : 0L;
      }
   }

   public void flushQueuedAudio() {
      OpenALSpatialAudio sa = this.spatialAudio;
      long baseline = sa != null ? sa.getConsumedSamples() : this.totalSamplesFed;
      this.flushQueuedAudio(baseline);
   }

   public void flushQueuedAudio(long mediaPositionSamples) {
      long baseline = Math.max(0L, mediaPositionSamples);
      this.timelineBaselineSamples = Math.max(this.timelineBaselineSamples, baseline);
      OpenALSpatialAudio sa = this.spatialAudio;
      this.totalSamplesFed = sa != null ? Math.max(baseline, sa.flushQueuedAudio(baseline)) : Math.max(this.totalSamplesFed, baseline);
      this.pendingFed = 0;
   }

   long timelineBaselineSamples() {
      return this.timelineBaselineSamples;
   }

   public void hardStopOutput() {
      this.started = false;
      this.handlerStarted = false;
      this.pendingFed = 0;
      this.totalSamplesFed = 0L;
      this.presentationEnvelope.reset();
      this.timelineBaselineSamples = 0L;
      OpenALSpatialAudio sa = this.spatialAudio;
      if (sa != null) {
         sa.hardStopOutput();
         sa.cleanup();
         this.spatialAudio = null;
      }

      this.initialized = false;
   }

   private String kind() {
      return "speaker-relay-ch" + this.channelIndex;
   }

   private static float[] forward(float[] sp, float[] lp) {
      float dx = sp[0] - lp[0];
      float dz = sp[2] - lp[2];
      float len = (float)Math.sqrt(dx * dx + dz * dz);
      return len < 0.001F ? new float[]{0.0F, 0.0F, 1.0F} : new float[]{dx / len, 0.0F, dz / len};
   }

   private static float distance(float[] a, float[] b) {
      float dx = a[0] - b[0];
      float dy = a[1] - b[1];
      float dz = a[2] - b[2];
      return (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   private AudioPlaybackRange.SphereResult rangeAt(float[] listenerPos) {
      float[] currentSpeakerPos = this.speakerPos;
      return listenerPos != null && currentSpeakerPos != null
         ? AudioPlaybackRange.evaluateSphere(distance(listenerPos, currentSpeakerPos), this.maxDistance, this.userVolume, this.userVolume, false)
         : AudioPlaybackRange.evaluateSphere(Float.POSITIVE_INFINITY, this.maxDistance, this.userVolume, this.userVolume, false);
   }

   private static float gameVol() {
      Minecraft mc = Minecraft.getInstance();
      return mc != null && mc.options != null
         ? mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.RECORDS)
         : 1.0F;
   }

   public void cleanup() {
      if (this.cleanupStarted.compareAndSet(false, true)) {
         this.closed = true;
         this.hardStopOutput();
         this.started = false;
         this.handlerStarted = false;
         this.pendingFed = 0;
         this.totalSamplesFed = 0L;
         this.initialized = false;
      }
   }
}
