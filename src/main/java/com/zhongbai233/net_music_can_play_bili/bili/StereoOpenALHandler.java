package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.audio.AudioOutputHandle;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

public class StereoOpenALHandler implements AudioOutputHandle {
   private static final boolean MUTE_MAIN_WHEN_RELAYS_CONNECTED = AudioRelayProperties.muteMainWhenConnected();
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SAMPLES_PER_BLOCK = 256;
   private static final int QUEUE_CAPACITY = 512;
   private static final int PREBUFFER_BLOCKS = 96;
   private static final int MAX_BLOCKS_PER_TICK = 64;
   private static final AudioSyncPolicy SYNC_POLICY = AudioSyncPolicy.fromSystemProperties();
   private static final AtomicLong INSTANCES_CREATED = new AtomicLong();
   private static final AtomicLong CLEANUPS_STARTED = new AtomicLong();
   private static final AtomicLong CLEANUPS_COMPLETED = new AtomicLong();
   private static final float[][] BED_POSITIONS = new float[][]{{-0.5F, 0.0F, 0.866F}, {0.5F, 0.0F, 0.866F}};
   private static final float[][] EMPTY_OBJECT_POSITIONS = new float[0][0];
   private final BlockingQueue<float[][]> pcmQueue = new LinkedBlockingQueue<>(512);
   private final float[] forwardToMachine = new float[3];
   private final SpatialFrontSmoother frontSmoother = new SpatialFrontSmoother();
   private final Thread worker;
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private volatile boolean closed;
   private final AtomicBoolean cleanupStarted = new AtomicBoolean();
   private volatile boolean started;
   private volatile boolean paused;
   private long totalSamplesFed;
   private volatile long totalInputSamples;
   private volatile long discardInputUntilSample;
   private volatile boolean resetPendingInput;
   private long lastFrameFeedNanos;
   private double frameBudget;
   private volatile OpenALSpatialAudio spatialAudio;
   private volatile boolean initialized;
   private int frameCount;
   private volatile int pcmQueueHighWater;
   private int sampleRate = 48000;
   private float[][] pendingBlock = new float[2][256];
   private int pendingSamples;
   private volatile float lastDistance = Float.NaN;
   private volatile float lastGain = 1.0F;
   private volatile float userVolume = 1.0F;
   private volatile float lastAudioLevel;
   private volatile long lastAudioLevelNanos;
   private volatile boolean consoleRouteSuppressed;
   private static final long FIRST_PCM_QUALITY_SAMPLES = 4096L;
   private final StereoOpenALHandler.PcmQualityWindow firstPcmWindow = new StereoOpenALHandler.PcmQualityWindow(4096L);
   private final StereoOpenALHandler.AudiblePcmQualityWindow firstAudiblePcmWindow = new StereoOpenALHandler.AudiblePcmQualityWindow(4096L, 0.001F);
   private volatile StereoOpenALHandler.PcmQuality firstPcmQuality = StereoOpenALHandler.PcmQuality.EMPTY;
   private volatile StereoOpenALHandler.PcmQuality firstAudiblePcmQuality = StereoOpenALHandler.PcmQuality.EMPTY;
   private final CopyOnWriteArrayList<SpeakerAudioRelay> relays = new CopyOnWriteArrayList<>();

   public StereoOpenALHandler() {
      INSTANCES_CREATED.incrementAndGet();
      this.worker = NetMusicThreadFactory.daemonThread("StereoOpenALWorker", this::workerLoop);
      this.worker.start();
   }

   public StereoOpenALHandler.PcmQuality observeFirstPcm(float[][] planar) {
      this.firstPcmQuality = this.firstPcmWindow.observe(planar);
      this.firstAudiblePcmQuality = this.firstAudiblePcmWindow.observe(planar);
      return this.firstPcmQuality;
   }

   public boolean enqueuePcm(float[][] stereoBlock) {
      if (stereoBlock != null && !this.closed) {
         int samples = sampleCount(stereoBlock);
         if (samples <= 0) {
            return false;
         } else {
            if (this.resetPendingInput) {
               this.pendingBlock = new float[2][256];
               this.pendingSamples = 0;
               this.resetPendingInput = false;
            }

            boolean queuedAny = false;
            float crossfeed = BiliConfig.stereoCrossfeedAmount();
            float keep = 1.0F - crossfeed;

            for (int sample = 0; sample < samples && !this.closed; sample++) {
               if (this.resetPendingInput) {
                  this.pendingBlock = new float[2][256];
                  this.pendingSamples = 0;
                  this.resetPendingInput = false;
               }

               long inputSample = this.totalInputSamples++;
               if (inputSample >= this.discardInputUntilSample) {
                  float left = sampleAt(stereoBlock, 0, sample);
                  float right = sampleAt(stereoBlock, 1, sample);
                  if (crossfeed > 0.0F) {
                     this.pendingBlock[0][this.pendingSamples] = softClip(left * keep + right * crossfeed);
                     this.pendingBlock[1][this.pendingSamples] = softClip(right * keep + left * crossfeed);
                  } else {
                     this.pendingBlock[0][this.pendingSamples] = left;
                     this.pendingBlock[1][this.pendingSamples] = right;
                  }

                  this.pendingSamples++;
                  if (this.pendingSamples == 256) {
                     float[][] block = this.pendingBlock;
                     this.pendingBlock = new float[2][256];
                     this.pendingSamples = 0;
                     if (!this.enqueueBlock(block)) {
                        return queuedAny;
                     }

                     queuedAny = true;
                  }
               }
            }

            return queuedAny;
         }
      } else {
         return false;
      }
   }

   public boolean finishInput() {
      if (!this.closed && this.pendingSamples > 0) {
         float[][] block = this.pendingBlock;
         this.pendingBlock = new float[2][256];
         this.pendingSamples = 0;
         return this.enqueueBlock(block);
      } else {
         return false;
      }
   }

   private boolean enqueueBlock(float[][] block) {
      try {
         while (!this.closed) {
            if (this.pcmQueue.offer(block, 250L, TimeUnit.MILLISECONDS)) {
               this.frameCount++;
               this.pcmQueueHighWater = Math.max(this.pcmQueueHighWater, this.pcmQueue.size());
               return true;
            }
         }
      } catch (InterruptedException var3) {
         Thread.currentThread().interrupt();
      }

      return false;
   }

   private static float sampleAt(float[][] stereoBlock, int channel, int sample) {
      if (stereoBlock != null && stereoBlock.length != 0) {
         int sourceChannel = Math.min(channel, stereoBlock.length - 1);
         float[] channelData = stereoBlock[sourceChannel];
         return channelData != null && sample < channelData.length ? channelData[sample] : 0.0F;
      } else {
         return 0.0F;
      }
   }

   private static int sampleCount(float[][] stereoBlock) {
      int samples = 0;
      if (stereoBlock != null) {
         for (float[] channelData : stereoBlock) {
            if (channelData != null) {
               samples = Math.max(samples, channelData.length);
            }
         }
      }

      return samples;
   }

   private static float softClip(float sample) {
      return Math.max(-1.0F, Math.min(1.0F, sample));
   }

   public void tick(float[] machinePos, float[] listenerPos) {
      this.tick(machinePos, listenerPos, Long.MAX_VALUE, false);
   }

   public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks) {
      this.tick(machinePos, listenerPos, targetRelativeTicks, false);
   }

   @Override
   public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks, boolean followLocalPlayerFront) {
      this.tick(machinePos, listenerPos, targetRelativeTicks, followLocalPlayerFront, followLocalPlayerFront);
   }

   @Override
   public void tick(float[] machinePos, float[] listenerPos, long targetRelativeTicks, boolean followLocalPlayerFront, boolean muteWorldRelays) {
      if (!this.closed && this.initialized) {
         if (!this.paused) {
            if (!this.started) {
               if (this.pcmQueue.size() < 96) {
                  return;
               }

               this.started = true;
               this.lastFrameFeedNanos = System.nanoTime();
               this.frameBudget = Math.min(64.0, this.sampleRate / 20.0 / 256.0);

               for (SpeakerAudioRelay relay : this.relays) {
                  relay.setHandlerStarted(true);
               }

               LOGGER.debug("Stereo OpenAL 预缓冲完成: {} blocks, 开始播放", this.pcmQueue.size());
            }

            this.updateFrameBudget();
            if (this.hardFlushIfAhead(targetRelativeTicks)) {
               this.frameBudget = Math.min(this.frameBudget, 1.0);
            }

            if (this.hardDropDecodedBacklog(targetRelativeTicks)) {
               this.frameBudget = Math.min(this.frameBudget, 1.0);
            }

            if (this.hardFlushIfOutputLagging(targetRelativeTicks)) {
               this.frameBudget = Math.min(this.frameBudget, 1.0);
            }

            int allowed = this.isAheadOfTarget(targetRelativeTicks) ? 0 : this.allowedBlocksForTarget(targetRelativeTicks);
            int fed = 0;
            OpenALSpatialAudio output = this.spatialAudio;
            if (output != null) {
               float[][] block;
               while (fed < allowed && (block = this.pcmQueue.peek()) != null && output.updateBedBlock(block)) {
                  this.pcmQueue.poll();
                  this.updateAudioLevel(block);
                  fed++;
                  this.feedRelays(block);
               }

               this.totalSamplesFed += fed * 256L;
               this.frameBudget = Math.max(0.0, this.frameBudget - fed);
               if (this.initialized && this.spatialAudio != null) {
                  if (this.spatialAudio.isDeviceLost()) {
                     LOGGER.warn("Stereo OpenAL device lost, reinitializing...");
                     this.spatialAudio.cleanup();
                     this.spatialAudio = null;
                     this.initialized = false;
                     this.started = false;
                     return;
                  }

                  this.spatialAudio.pumpQueuedAudio();
                  if (listenerPos != null && machinePos != null) {
                     this.forwardToMachine[0] = machinePos[0] - listenerPos[0];
                     this.forwardToMachine[1] = 0.0F;
                     this.forwardToMachine[2] = machinePos[2] - listenerPos[2];
                     this.spatialAudio
                        .updatePositions(
                           BED_POSITIONS, EMPTY_OBJECT_POSITIONS, listenerPos, this.frontSmoother.update(this.forwardToMachine, followLocalPlayerFront)
                        );
                     float distance = distance(listenerPos, machinePos);
                     float gain = spatialGainForDistance(distance, this.userVolume);
                     this.lastDistance = distance;
                     this.lastGain = gain;
                     boolean routeMuted = SpeakerRelayMutePolicy.shouldMuteMain(
                        MUTE_MAIN_WHEN_RELAYS_CONNECTED, this.relays, followLocalPlayerFront, this.consoleRouteSuppressed
                     );
                     float entryGain = this.presentationEnvelope.gain(!routeMuted && gain > 0.0F, System.nanoTime());
                     float gv = routeMuted ? 0.0F : gain * entryGain * gameVolume();
                     this.spatialAudio.setBedGain(0, gv);
                     this.spatialAudio.setBedGain(1, gv);
                  }
               }

               for (SpeakerAudioRelay relay : this.relays) {
                  relay.tick(listenerPos, muteWorldRelays);
               }
            }
         }
      }
   }

   private boolean isAheadOfTarget(long targetRelativeTicks) {
      return !this.started ? false : SYNC_POLICY.isAhead(this.getFedPositionTicks(), this.getPositionTicks(), targetRelativeTicks);
   }

   private boolean hardFlushIfAhead(long targetRelativeTicks) {
      if (targetRelativeTicks != Long.MAX_VALUE && this.started && this.spatialAudio != null) {
         long fedTicks = this.getFedPositionTicks();
         long audibleTicks = this.getPositionTicks();
         if (!SYNC_POLICY.shouldFlushAhead(fedTicks, audibleTicks, targetRelativeTicks)) {
            return false;
         } else {
            long consumedSamples = this.spatialAudio.flushQueuedAudio();
            this.totalSamplesFed = Math.max(0L, consumedSamples);

            for (SpeakerAudioRelay relay : this.relays) {
               relay.flushQueuedAudio(consumedSamples);
            }

            return true;
         }
      } else {
         return false;
      }
   }

   private boolean hardFlushIfOutputLagging(long targetRelativeTicks) {
      if (targetRelativeTicks != Long.MAX_VALUE && this.started && this.spatialAudio != null) {
         long audibleTicks = this.getPositionTicks();
         long fedTicks = this.getFedPositionTicks();
         long audibleLag = targetRelativeTicks - audibleTicks;
         if (!SYNC_POLICY.shouldFlushOutputLag(audibleTicks, fedTicks, targetRelativeTicks)) {
            return false;
         } else {
            long targetSamples = Math.max(0L, targetRelativeTicks) * this.samplesPerTick();
            this.spatialAudio.flushQueuedAudio(targetSamples);
            this.totalSamplesFed = targetSamples;

            for (SpeakerAudioRelay relay : this.relays) {
               relay.flushQueuedAudio(targetSamples);
            }

            LOGGER.warn(
               "Stereo OpenAL 输出队列落后过多，已丢弃待播放缓冲以追赶: audible={}ticks target={}ticks fed={}ticks lag={}ticks",
               new Object[]{audibleTicks, targetRelativeTicks, fedTicks, audibleLag}
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private boolean hardDropDecodedBacklog(long targetRelativeTicks) {
      if (targetRelativeTicks != Long.MAX_VALUE && this.started && this.spatialAudio != null) {
         long audibleTicks = this.getPositionTicks();
         long fedTicks = this.getFedPositionTicks();
         if (!SYNC_POLICY.shouldDropDecodedBacklog(audibleTicks, fedTicks, targetRelativeTicks)) {
            return false;
         } else {
            long targetSamples = Math.max(0L, targetRelativeTicks) * this.samplesPerTick();
            long inputSamples = this.totalInputSamples;
            long baselineSamples = Math.max(targetSamples, inputSamples);
            this.discardInputUntilSample = Math.max(this.discardInputUntilSample, baselineSamples);
            this.resetPendingInput = true;
            this.pcmQueue.clear();
            this.spatialAudio.flushQueuedAudio(baselineSamples);
            this.totalSamplesFed = baselineSamples;

            for (SpeakerAudioRelay relay : this.relays) {
               relay.flushQueuedAudio(baselineSamples);
            }

            LOGGER.warn(
               "Stereo OpenAL 解码与输出同时落后，已丢弃陈旧 PCM 追赶: audible={}ticks target={}ticks fed={}ticks input={}samples baseline={}samples",
               new Object[]{audibleTicks, targetRelativeTicks, fedTicks, inputSamples, baselineSamples}
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private void feedRelays(float[][] block) {
      if (!this.relays.isEmpty() && block != null && block.length != 0) {
         for (SpeakerAudioRelay relay : this.relays) {
            float[] mixed = SpeakerChannelMixer.baseMix(block, relay.getChannelIndex());
            if (mixed != null) {
               if (!relay.isAutoMixJoc()) {
                  relay.feedChannel(mixed);
               } else {
                  for (int sourceChannel = 0; sourceChannel < block.length; sourceChannel++) {
                     if (!SpeakerChannelMixer.isSourceClaimed(sourceChannel, block.length, this.relays)) {
                        SpeakerChannelMixer.mixInto(mixed, block[sourceChannel], stereoMixGain(block.length));
                     }
                  }

                  relay.feedMono(mixed);
               }
            }
         }
      }
   }

   private static float stereoMixGain(int channelCount) {
      return channelCount <= 2 ? 0.65F : 0.55F;
   }

   private int allowedBlocksForTarget(long targetRelativeTicks) {
      return SYNC_POLICY.allowedUnits(this.frameBudget, 64, this.getFedPositionTicks(), targetRelativeTicks);
   }

   private static float gameVolume() {
      Minecraft mc = Minecraft.getInstance();
      return mc != null && mc.options != null
         ? mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.RECORDS)
         : 1.0F;
   }

   @Override
   public void setUserVolume(float volume) {
      this.userVolume = clampGain(volume);
   }

   public float userVolume() {
      return this.userVolume;
   }

   @Override
   public void addRelay(SpeakerAudioRelay relay) {
      if (relay != null && !this.relays.contains(relay)) {
         relay.setSampleRate(this.sampleRate);
         relay.setPaused(this.paused);
         if (this.started) {
            long baseline = this.spatialAudio != null ? this.spatialAudio.getConsumedSamples() : this.totalSamplesFed;
            relay.flushQueuedAudio(baseline);
            relay.setHandlerStarted(true);
         }

         this.relays.add(relay);
      }
   }

   @Override
   public void removeRelay(SpeakerAudioRelay relay) {
      if (relay != null) {
         this.relays.remove(relay);
      }
   }

   @Override
   public void setConsoleRouteSuppressed(boolean suppressed) {
      this.consoleRouteSuppressed = suppressed;
   }

   @Override
   public float audioLevel() {
      long ageNanos = System.nanoTime() - this.lastAudioLevelNanos;
      if (ageNanos <= 0L) {
         return this.lastAudioLevel;
      } else {
         float ageSeconds = (float)ageNanos / 1.0E9F;
         float decay = Math.max(0.0F, 1.0F - ageSeconds * 2.5F);
         return clampGain(this.lastAudioLevel * decay);
      }
   }

   private void updateAudioLevel(float[][] block) {
      if (block != null && block.length != 0) {
         float peak = 0.0F;
         double sum = 0.0;
         int count = 0;

         for (float[] channel : block) {
            if (channel != null) {
               for (float sample : channel) {
                  float abs = Math.abs(sample);
                  peak = Math.max(peak, abs);
                  sum += sample * sample;
                  count++;
               }
            }
         }

         float rms = count > 0 ? (float)Math.sqrt(sum / count) : 0.0F;
         this.lastAudioLevel = clampGain(Math.max(peak * 0.7F, rms * 2.2F));
         this.lastAudioLevelNanos = System.nanoTime();
      }
   }

   private void updateFrameBudget() {
      long now = System.nanoTime();
      if (this.lastFrameFeedNanos == 0L) {
         this.lastFrameFeedNanos = now;
      } else {
         double elapsed = Math.max(0.0, (now - this.lastFrameFeedNanos) / 1.0E9);
         this.lastFrameFeedNanos = now;
         double blocksPerSecond = this.sampleRate / 256.0;
         this.frameBudget = Math.min(64.0, this.frameBudget + elapsed * blocksPerSecond);
      }
   }

   private long samplesPerTick() {
      return Math.max(1, this.sampleRate) / 20L;
   }

   public void setSampleRate(int sr) {
      this.sampleRate = sr > 0 ? sr : '뮀';
   }

   @Override
   public void setPaused(boolean paused) {
      this.paused = paused;
      OpenALSpatialAudio sa = this.spatialAudio;
      if (sa != null) {
         sa.setPaused(paused);
      }

      for (SpeakerAudioRelay relay : this.relays) {
         relay.setPaused(paused);
      }
   }

   @Override
   public void cleanup() {
      if (this.cleanupStarted.compareAndSet(false, true)) {
         CLEANUPS_STARTED.incrementAndGet();

         try {
            this.hardStopOutput();
            this.closed = true;
            this.pcmQueue.clear();
            this.pendingBlock = new float[2][256];
            this.pendingSamples = 0;
            LifecycleClose.interruptAndJoin(this.worker);
            if (this.spatialAudio != null) {
               this.spatialAudio.cleanup();
               this.spatialAudio = null;
            }

            this.relays.clear();
            this.initialized = false;
            LOGGER.debug("StereoOpenALHandler closed ({} blocks)", this.frameCount);
         } finally {
            CLEANUPS_COMPLETED.incrementAndGet();
         }
      }
   }

   @Override
   public void hardStopOutput() {
      this.started = false;
      this.frameBudget = 0.0;
      this.presentationEnvelope.reset();
      this.lastFrameFeedNanos = 0L;
      this.totalSamplesFed = 0L;
      this.pcmQueue.clear();
      this.pendingBlock = new float[2][256];
      this.pendingSamples = 0;
      this.totalInputSamples = 0L;
      this.discardInputUntilSample = 0L;
      this.resetPendingInput = false;
      OpenALSpatialAudio sa = this.spatialAudio;
      if (sa != null) {
         sa.hardStopOutput();
      }

      for (SpeakerAudioRelay relay : this.relays) {
         relay.hardStopOutput();
      }
   }

   public boolean hasStarted() {
      return this.started;
   }

   @Override
   public long getPositionTicks() {
      long millis = this.getPositionMillis();
      return millis >= 0L ? millis * 20L / 1000L : -1L;
   }

   @Override
   public long getPositionMillis() {
      if (!this.started) {
         return -1L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         long consumed = sa != null ? sa.getConsumedSamples() : this.totalSamplesFed;
         return Math.round(consumed * 1000.0 / Math.max(1, this.sampleRate));
      }
   }

   public long getFedPositionTicks() {
      long millis = this.getFedPositionMillis();
      return millis >= 0L ? millis * 20L / 1000L : -1L;
   }

   @Override
   public long getFedPositionMillis() {
      return !this.started ? -1L : Math.round(Math.max(0L, this.totalSamplesFed) * 1000.0 / Math.max(1, this.sampleRate));
   }

   @Override
   public long getOutputDelayMillis() {
      if (!this.started) {
         return 0L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
         return delaySamples > 0L ? Math.round(delaySamples * 1000.0 / Math.max(1, this.sampleRate)) : 0L;
      }
   }

   public List<String> describeState() {
      return List.of(
         String.format(
            "Stereo OpenAL: initialized=%s started=%s sampleRate=%d queue=%d/%d peak=%d openalPending=%d pendingSamples=%d blocks=%d distance=%.2f gain=%.3f volume=%.2f level=%.3f",
            this.initialized,
            this.started,
            this.sampleRate,
            this.pcmQueue.size(),
            512,
            this.pcmQueueHighWater,
            this.spatialAudio != null ? this.spatialAudio.pendingMediaBlocks() : 0,
            this.pendingSamples,
            this.frameCount,
            this.lastDistance,
            this.lastGain,
            this.userVolume,
            this.audioLevel()
         )
      );
   }

   public StereoOpenALHandler.DiagnosticSnapshot snapshot() {
      return new StereoOpenALHandler.DiagnosticSnapshot(
         this.initialized,
         this.started,
         this.closed,
         this.cleanupStarted.get(),
         this.sampleRate,
         this.pcmQueue.size(),
         this.pcmQueueHighWater,
         this.pendingSamples,
         this.frameCount,
         this.totalInputSamples,
         this.getPositionMillis(),
         this.getFedPositionMillis(),
         this.getOutputDelayMillis(),
         this.paused,
         this.firstPcmQuality,
         this.firstAudiblePcmQuality
      );
   }

   public static StereoOpenALHandler.LifecycleSnapshot lifecycleSnapshot() {
      long created = INSTANCES_CREATED.get();
      long completed = CLEANUPS_COMPLETED.get();
      return new StereoOpenALHandler.LifecycleSnapshot(created, CLEANUPS_STARTED.get(), completed, Math.max(0L, created - completed));
   }

   private static float spatialGainForDistance(float d, float volume) {
      return AudioUtils.spatialGainForDistance(d, volume);
   }

   private static float clampGain(float gain) {
      return AudioUtils.clampGain(gain);
   }

   private static float distance(float[] a, float[] b) {
      return AudioUtils.distance(a, b);
   }

   private void workerLoop() {
      while (!this.closed) {
         try {
            Thread.sleep(100L);
         } catch (InterruptedException var2) {
            Thread.currentThread().interrupt();
            break;
         }

         if (!this.initialized && !this.pcmQueue.isEmpty()) {
            OpenALSpatialAudio next = new OpenALSpatialAudio();
            if (!next.init(2, 0, this.sampleRate)) {
               next.cleanup();
               this.spatialAudio = null;
            } else {
               next.setPaused(this.paused);
               this.spatialAudio = next;
               this.initialized = true;
               LOGGER.debug("Stereo OpenAL 初始化: 2 声道立体声");
            }
         }
      }
   }

   static final class AudiblePcmQualityWindow {
      private final StereoOpenALHandler.PcmQualityWindow delegate;
      private final float startPeak;
      private boolean started;

      AudiblePcmQualityWindow(long maximumSamples, float startPeak) {
         this.delegate = new StereoOpenALHandler.PcmQualityWindow(maximumSamples);
         this.startPeak = Math.max(0.0F, startPeak);
      }

      synchronized StereoOpenALHandler.PcmQuality observe(float[][] planar) {
         if (!this.started) {
            StereoOpenALHandler.PcmQuality candidate = StereoOpenALHandler.PcmQuality.measure(planar);
            if (candidate.peak() < this.startPeak) {
               return StereoOpenALHandler.PcmQuality.EMPTY;
            }

            this.started = true;
         }

         return this.delegate.observe(planar);
      }
   }

   public record DiagnosticSnapshot(
      boolean initialized,
      boolean started,
      boolean closed,
      boolean cleanupStarted,
      int sampleRate,
      int queuedBlocks,
      int queueHighWater,
      int pendingSamples,
      int decodedBlocks,
      long inputSamples,
      long positionMillis,
      long fedPositionMillis,
      long outputDelayMillis,
      boolean paused,
      StereoOpenALHandler.PcmQuality firstPcm,
      StereoOpenALHandler.PcmQuality firstAudiblePcm
   ) {
      public DiagnosticSnapshot(
         boolean initialized,
         boolean started,
         boolean closed,
         boolean cleanupStarted,
         int sampleRate,
         int queuedBlocks,
         int queueHighWater,
         int pendingSamples,
         int decodedBlocks,
         long inputSamples,
         long positionMillis,
         long fedPositionMillis,
         long outputDelayMillis,
         boolean paused,
         StereoOpenALHandler.PcmQuality firstPcm,
         StereoOpenALHandler.PcmQuality firstAudiblePcm
      ) {
         firstPcm = firstPcm != null ? firstPcm : StereoOpenALHandler.PcmQuality.EMPTY;
         firstAudiblePcm = firstAudiblePcm != null ? firstAudiblePcm : StereoOpenALHandler.PcmQuality.EMPTY;
         this.initialized = initialized;
         this.started = started;
         this.closed = closed;
         this.cleanupStarted = cleanupStarted;
         this.sampleRate = sampleRate;
         this.queuedBlocks = queuedBlocks;
         this.queueHighWater = queueHighWater;
         this.pendingSamples = pendingSamples;
         this.decodedBlocks = decodedBlocks;
         this.inputSamples = inputSamples;
         this.positionMillis = positionMillis;
         this.fedPositionMillis = fedPositionMillis;
         this.outputDelayMillis = outputDelayMillis;
         this.paused = paused;
         this.firstPcm = firstPcm;
         this.firstAudiblePcm = firstAudiblePcm;
      }
   }

   public record LifecycleSnapshot(long instancesCreated, long cleanupsStarted, long cleanupsCompleted, long activeInstances) {
   }

   public record PcmQuality(long samples, float peak, double rms, double clippedRatio) {
      private static final StereoOpenALHandler.PcmQuality EMPTY = new StereoOpenALHandler.PcmQuality(0L, 0.0F, 0.0, 0.0);

      public static StereoOpenALHandler.PcmQuality measure(float[][] planar) {
         double sumSquares = 0.0;
         float peak = 0.0F;
         long samples = 0L;
         long clipped = 0L;
         if (planar != null) {
            for (float[] channel : planar) {
               if (channel != null) {
                  for (float sample : channel) {
                     if (Float.isFinite(sample)) {
                        float absolute = Math.abs(sample);
                        peak = Math.max(peak, absolute);
                        sumSquares += sample * sample;
                        samples++;
                        if (absolute >= 0.999F) {
                           clipped++;
                        }
                     }
                  }
               }
            }
         }

         double rms = samples > 0L ? Math.sqrt(sumSquares / samples) : 0.0;
         double clippedRatio = samples > 0L ? (double)clipped / samples : 0.0;
         return new StereoOpenALHandler.PcmQuality(samples, peak, rms, clippedRatio);
      }
   }

   static final class PcmQualityWindow {
      private final long maximumSamples;
      private long samples;
      private float peak;
      private double sumSquares;
      private long clipped;

      PcmQualityWindow(long maximumSamples) {
         this.maximumSamples = Math.max(1L, maximumSamples);
      }

      synchronized StereoOpenALHandler.PcmQuality observe(float[][] planar) {
         if (planar != null && this.samples < this.maximumSamples) {
            label47:
            for (float[] channel : planar) {
               if (channel != null) {
                  for (float sample : channel) {
                     if (Float.isFinite(sample)) {
                        float absolute = Math.abs(sample);
                        this.peak = Math.max(this.peak, absolute);
                        this.sumSquares += sample * sample;
                        this.samples++;
                        if (absolute >= 0.999F) {
                           this.clipped++;
                        }

                        if (this.samples >= this.maximumSamples) {
                           break label47;
                        }
                     }
                  }
               }
            }
         }

         double rms = this.samples > 0L ? Math.sqrt(this.sumSquares / this.samples) : 0.0;
         double clippedRatio = this.samples > 0L ? (double)this.clipped / this.samples : 0.0;
         return new StereoOpenALHandler.PcmQuality(this.samples, this.peak, rms, clippedRatio);
      }
   }
}
