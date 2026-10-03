package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.PlaybackLatencyBench;
import com.zhongbai233.net_music_can_play_bili.client.audio.AudioOutputHandle;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3AtmosParser;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3JocDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.QmfFilterBank;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.sync.AudioSyncPolicy;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackPresentationEnvelope;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

public class DolbyAudioHandler implements AudioOutputHandle {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final float SPATIAL_RADIUS = 1.5F;
   private static final float SILENCE_GATE_PEAK = 1.0E-4F;
   private static final float SILENCE_GATE_RMS = 2.0E-5F;
   private static final float PCM_OUTPUT_GAIN = 1.0F;
   private static final float JOC_OBJECT_OUTPUT_GAIN = 0.2F;
   private static final boolean ENABLE_JOC_OBJECTS = true;
   private static final double EC3_FRAMES_PER_SECOND = 31.25;
   private static final int MAX_FRAMES_PER_TICK = 8;
   private static final AudioStreamProperties.Dolby PROPERTIES = AudioStreamProperties.dolby();
   private static final int PREBUFFER_FRAMES = PROPERTIES.prebufferFrames();
   private static final boolean MUTE_MAIN_WHEN_RELAYS_CONNECTED = AudioRelayProperties.muteMainWhenConnected();
   private static final AudioSyncPolicy SYNC_POLICY = AudioSyncPolicy.fromSystemProperties();
   private static final int RAW_QUEUE_CAPACITY = PROPERTIES.rawQueueCapacity();
   private static final int PROCESSED_QUEUE_CAPACITY = PROPERTIES.processedQueueCapacity();
   private final BlockingQueue<byte[]> rawQueue = new LinkedBlockingQueue<>(RAW_QUEUE_CAPACITY);
   private final BlockingQueue<DolbyAudioHandler.ProcessedFrame> processedQueue = new LinkedBlockingQueue<>(PROCESSED_QUEUE_CAPACITY);
   private final Eac3JocDecoder jocDecoder = new Eac3JocDecoder();
   private final float[] forwardToMachine = new float[3];
   private final SpatialFrontSmoother frontSmoother = new SpatialFrontSmoother();
   private final Thread worker;
   private final PlaybackPresentationEnvelope presentationEnvelope = new PlaybackPresentationEnvelope();
   private volatile boolean closed;
   private final AtomicBoolean cleanupStarted = new AtomicBoolean();
   private boolean playbackStarted;
   private volatile boolean paused;
   private long lastFrameFeedNanos;
   private double frameBudget;
   private volatile OpenALSpatialAudio spatialAudio;
   private volatile boolean initialized;
   private int numBedChannels;
   private int numObjects;
   private float[][] bedPositions;
   private float[][] objectPositions;
   private volatile int lastJocSequence = -1;
   private volatile int lastJocAudioObjects;
   private volatile float[] lastJocObjectRms = new float[0];
   private volatile float[] lastJocObjectPeak = new float[0];
   private volatile float userVolume = 1.0F;
   private volatile int channelMask;
   private volatile boolean forceStaticJoc;
   private volatile float lastAudioLevel;
   private volatile long lastAudioLevelNanos;
   private volatile boolean consoleRouteSuppressed;
   private final CopyOnWriteArrayList<SpeakerAudioRelay> relays = new CopyOnWriteArrayList<>();
   private int frameCount;
   private int nullPcmCount;
   private volatile int rawQueueHighWater;
   private volatile int processedQueueHighWater;
   private long totalFramesFedToOpenAL;
   private boolean didFirstDiag;
   private boolean didFirstDiagSpatial;
   private boolean didFirstPositionDiag;
   private boolean didJocDecodeFailDiag;
   private boolean didJocAudioFailDiag;

   public DolbyAudioHandler() {
      Eac3NativeDecoder.preload();
      this.worker = NetMusicThreadFactory.daemonThread("DolbyDecodeWorker", this::workerLoop);
      this.worker.start();
   }

   public boolean enqueueFrame(byte[] ec3Frame) {
      if (ec3Frame != null && ec3Frame.length != 0 && !this.closed) {
         try {
            while (!this.closed) {
               if (this.rawQueue.offer(ec3Frame, 250L, TimeUnit.MILLISECONDS)) {
                  this.rawQueueHighWater = Math.max(this.rawQueueHighWater, this.rawQueue.size());
                  PlaybackLatencyBench.markAudioQueued(this, "dolby", this.rawQueue.size(), this.totalFramesFedToOpenAL * 1536L, 48000);
                  return true;
               }
            }

            return false;
         } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
            return false;
         }
      } else {
         return false;
      }
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
      if (!this.closed) {
         if (!this.paused) {
            if (targetRelativeTicks != Long.MIN_VALUE) {
               if (!this.playbackStarted) {
                  if (this.processedQueue.size() < PREBUFFER_FRAMES) {
                     return;
                  }

                  this.playbackStarted = true;
                  this.lastFrameFeedNanos = System.nanoTime();
                  this.frameBudget = 8.0;
                  PlaybackLatencyBench.markAudioStarted(this, "dolby", this.processedQueue.size(), this.totalFramesFedToOpenAL * 1536L, 48000);

                  for (SpeakerAudioRelay relay : this.relays) {
                     relay.setHandlerStarted(true);
                  }

                  LOGGER.debug("Dolby OpenAL 预缓冲完成: {} 帧", this.processedQueue.size());
               }

               this.updateFrameBudget();
               if (this.hardFlushIfAhead(targetRelativeTicks)) {
                  this.frameBudget = Math.min(this.frameBudget, 1.0);
               }

               if (this.hardFlushIfOutputLagging(targetRelativeTicks)) {
                  this.frameBudget = Math.min(this.frameBudget, 1.0);
               }

               int processed = 0;
               boolean audioAhead = this.isAheadOfTarget(targetRelativeTicks);
               DolbyAudioHandler.ProcessedFrame pf;
               if (!audioAhead) {
                  for (int allowedFrames = this.allowedFramesForTarget(targetRelativeTicks);
                     processed < allowedFrames && (pf = this.processedQueue.peek()) != null;
                     processed++
                  ) {
                     DolbyAudioHandler.FeedResult result = this.feedOpenAL(pf);
                     if (result == DolbyAudioHandler.FeedResult.RETRY) {
                        break;
                     }

                     this.processedQueue.poll();
                  }
               }

               this.frameBudget = Math.max(0.0, this.frameBudget - processed);
               if (this.initialized && this.spatialAudio != null) {
                  if (this.spatialAudio.isDeviceLost()) {
                     LOGGER.warn("Dolby OpenAL device lost, reinitializing...");
                     this.spatialAudio.cleanup();
                     this.spatialAudio = null;
                     this.initialized = false;
                     this.numBedChannels = 0;
                     this.numObjects = 0;
                     return;
                  }

                  this.spatialAudio.pumpQueuedAudio();
                  this.updateSpatialState(machinePos, listenerPos, followLocalPlayerFront);
               }

               for (SpeakerAudioRelay relay : this.relays) {
                  relay.tick(listenerPos, muteWorldRelays);
               }
            }
         }
      }
   }

   public int queuedFrames() {
      return this.rawQueue.size();
   }

   private boolean isAheadOfTarget(long targetRelativeTicks) {
      return !this.playbackStarted ? false : SYNC_POLICY.isAhead(this.getFedPositionTicks(), this.getPositionTicks(), targetRelativeTicks);
   }

   private boolean hardFlushIfAhead(long targetRelativeTicks) {
      if (targetRelativeTicks != Long.MAX_VALUE && this.playbackStarted && this.spatialAudio != null) {
         long fedTicks = this.getFedPositionTicks();
         long audibleTicks = this.getPositionTicks();
         if (!SYNC_POLICY.shouldFlushAhead(fedTicks, audibleTicks, targetRelativeTicks)) {
            return false;
         } else {
            long consumedSamples = this.spatialAudio.flushQueuedAudio();
            this.totalFramesFedToOpenAL = Math.max(0L, consumedSamples / 1536L);

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
      if (targetRelativeTicks != Long.MAX_VALUE && this.playbackStarted && this.spatialAudio != null) {
         long audibleTicks = this.getPositionTicks();
         long fedTicks = this.getFedPositionTicks();
         long audibleLag = targetRelativeTicks - audibleTicks;
         if (!SYNC_POLICY.shouldFlushOutputLag(audibleTicks, fedTicks, targetRelativeTicks)) {
            return false;
         } else {
            long targetFrames = ticksToEc3Frames(Math.max(0L, targetRelativeTicks));
            this.spatialAudio.flushQueuedAudio(targetFrames * 1536L);
            this.totalFramesFedToOpenAL = targetFrames;

            for (SpeakerAudioRelay relay : this.relays) {
               relay.flushQueuedAudio(targetFrames * 1536L);
            }

            LOGGER.warn(
               "Dolby OpenAL 输出队列落后过多，已丢弃待播放缓冲以追赶: audible={}ticks target={}ticks fed={}ticks lag={}ticks",
               new Object[]{audibleTicks, targetRelativeTicks, fedTicks, audibleLag}
            );
            return true;
         }
      } else {
         return false;
      }
   }

   private int allowedFramesForTarget(long targetRelativeTicks) {
      return SYNC_POLICY.allowedUnits(this.frameBudget, 8, this.getFedPositionTicks(), targetRelativeTicks);
   }

   public List<String> describeSources(float[] machinePos, float[] listenerPos) {
      List<String> lines = new ArrayList<>();
      int pendingBlocks = this.spatialAudio != null ? this.spatialAudio.pendingMediaBlocks() : 0;
      lines.add(
         String.format(
            "Dolby: initialized=%s beds=%d objects=%d JOC音频=%s rawQ=%d/%d peak=%d procQ=%d/%d peak=%d openalPending=%d frames=%d",
            this.initialized,
            this.numBedChannels,
            this.numObjects,
            BiliConfig.dolbyJocEnabled ? "on" : "off",
            this.rawQueue.size(),
            RAW_QUEUE_CAPACITY,
            this.rawQueueHighWater,
            this.processedQueue.size(),
            PROCESSED_QUEUE_CAPACITY,
            this.processedQueueHighWater,
            pendingBlocks,
            this.frameCount
         )
      );
      if (machinePos != null && listenerPos != null) {
         float yaw = (float)Math.atan2(machinePos[0] - listenerPos[0], machinePos[2] - listenerPos[2]);
         float distance = distance(listenerPos, machinePos);
         float gain = spatialGainForDistance(distance, this.userVolume);
         lines.add(String.format("音乐机=%s 玩家耳位=%s 距离=%.2f gain=%.3f yaw=%.1f°", fmtPos(machinePos), fmtPos(listenerPos), distance, gain, Math.toDegrees(yaw)));
      } else {
         lines.add("音乐机/玩家位置尚未同步");
      }

      if (this.bedPositions != null) {
         String[] names = DolbySpatialLayout.bedChannelNames(this.numBedChannels);

         for (int i = 0; i < Math.min(this.bedPositions.length, 8); i++) {
            String name = names != null && i < names.length ? names[i] : "bed" + i;
            lines.add(String.format("床声道 %s local=%s", name, fmtPos(this.bedPositions[i])));
         }
      } else {
         lines.add("床声道位置尚未初始化");
      }

      if (this.objectPositions != null && this.objectPositions.length > 0) {
         lines.add(
            String.format(
               "最近JOC音频: seq=%d objs=%d active=%d peakMax=%.5f",
               this.lastJocSequence,
               this.lastJocAudioObjects,
               DolbySpatialLayout.countActiveObjects(this.lastJocObjectRms),
               DolbySpatialLayout.max(this.lastJocObjectPeak)
            )
         );
         int limit = Math.min(this.objectPositions.length, 8);

         for (int i = 0; i < limit; i++) {
            float rms = i < this.lastJocObjectRms.length ? this.lastJocObjectRms[i] : 0.0F;
            float peak = i < this.lastJocObjectPeak.length ? this.lastJocObjectPeak[i] : 0.0F;
            lines.add(String.format("JOC对象 #%02d local=%s rms=%.5f peak=%.5f", i, fmtPos(this.objectPositions[i]), rms, peak));
         }

         if (this.objectPositions.length > limit) {
            lines.add("其余 JOC 对象省略：" + (this.objectPositions.length - limit));
         }
      } else {
         lines.add("JOC对象声源未初始化");
      }

      return lines;
   }

   @Override
   public void setUserVolume(float volume) {
      this.userVolume = clampGain(volume);
   }

   public float userVolume() {
      return this.userVolume;
   }

   public void setChannelMask(int mask) {
      this.channelMask = mask;
   }

   public void setForceStaticJoc(boolean v) {
      this.forceStaticJoc = v;
   }

   @Override
   public void addRelay(SpeakerAudioRelay relay) {
      if (relay != null && !this.relays.contains(relay)) {
         relay.setSampleRate(48000);
         relay.setPaused(this.paused);
         if (this.playbackStarted) {
            long baseline = this.spatialAudio != null ? this.spatialAudio.getConsumedSamples() : this.totalFramesFedToOpenAL * 1536L;
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

   @Override
   public long getPositionTicks() {
      long millis = this.getPositionMillis();
      return millis >= 0L ? millis * 20L / 1000L : -1L;
   }

   @Override
   public long getPositionMillis() {
      if (!this.playbackStarted) {
         return -1L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         if (sa != null) {
            long consumed = sa.getConsumedSamples();
            if (consumed > 0L) {
               PlaybackLatencyBench.markAudioConsumed(this, "dolby", consumed, 48000);
               return Math.round(consumed * 1000.0 / 48000.0);
            }
         }

         PlaybackLatencyBench.markAudioConsumed(this, "dolby", this.totalFramesFedToOpenAL * 1536L, 48000);
         return Math.round(this.totalFramesFedToOpenAL * 1536L * 1000.0 / 48000.0);
      }
   }

   public long getFedPositionTicks() {
      long millis = this.getFedPositionMillis();
      return millis >= 0L ? millis * 20L / 1000L : -1L;
   }

   @Override
   public long getFedPositionMillis() {
      return !this.playbackStarted ? -1L : Math.round(this.totalFramesFedToOpenAL * 1536L * 1000.0 / 48000.0);
   }

   @Override
   public long getOutputDelayMillis() {
      if (!this.playbackStarted) {
         return 0L;
      } else {
         OpenALSpatialAudio sa = this.spatialAudio;
         long delaySamples = sa != null ? sa.getOutputDelaySamples() : 0L;
         return delaySamples > 0L ? Math.round(delaySamples * 1000.0 / 48000.0) : 0L;
      }
   }

   @Override
   public void cleanup() {
      if (this.cleanupStarted.compareAndSet(false, true)) {
         this.hardStopOutput();
         this.closed = true;
         this.rawQueue.clear();
         this.processedQueue.clear();
         this.playbackStarted = false;
         this.lastFrameFeedNanos = 0L;
         this.frameBudget = 0.0;
         LifecycleClose.interruptAndJoin(this.worker);
         if (this.spatialAudio != null) {
            this.spatialAudio.cleanup();
            this.spatialAudio = null;
         }

         this.relays.clear();
         this.initialized = false;
         LOGGER.debug("DolbyAudioHandler closed ({} frames)", this.frameCount);
      }
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
   public void hardStopOutput() {
      this.playbackStarted = false;
      this.presentationEnvelope.reset();
      this.lastFrameFeedNanos = 0L;
      this.frameBudget = 0.0;
      this.totalFramesFedToOpenAL = 0L;
      this.rawQueue.clear();
      this.processedQueue.clear();
      OpenALSpatialAudio sa = this.spatialAudio;
      if (sa != null) {
         sa.hardStopOutput();
      }

      for (SpeakerAudioRelay relay : this.relays) {
         relay.hardStopOutput();
      }
   }

   private void workerLoop() {
      Eac3NativeDecoder decoder = new Eac3NativeDecoder();
      LOGGER.debug("DolbyDecodeWorker 启动");

      while (!this.closed) {
         try {
            byte[] raw = this.rawQueue.poll(500L, TimeUnit.MILLISECONDS);
            if (raw != null) {
               DolbyAudioHandler.ProcessedFrame pf;
               try {
                  pf = this.decodeOneFrame(decoder, raw);
               } catch (Exception var5) {
                  LOGGER.warn("DolbyDecodeWorker 解码异常", var5);
                  continue;
               }

               if (pf != null) {
                  this.processedQueue.put(pf);
                  this.processedQueueHighWater = Math.max(this.processedQueueHighWater, this.processedQueue.size());
               }
            }
         } catch (InterruptedException var6) {
            Thread.currentThread().interrupt();
            break;
         } catch (Throwable var7) {
            LOGGER.error("DolbyDecodeWorker 未预期的异常 (线程继续运行)", var7);
         }
      }

      decoder.close();
      LOGGER.debug("DolbyDecodeWorker 退出 (解码 {} 帧, nullPcm={})", this.frameCount, this.nullPcmCount);
   }

   private DolbyAudioHandler.ProcessedFrame decodeOneFrame(Eac3NativeDecoder decoder, byte[] raw) {
      this.frameCount++;
      float[][] pcm = decoder.decodeFrame(raw);
      if (pcm == null) {
         this.nullPcmCount++;
         if (!this.didFirstDiag) {
            this.didFirstDiag = true;
            LOGGER.warn("Dolby decode fail ({}bytes)", raw.length);
         }

         return null;
      } else {
         DolbyAudioHandler.PcmStats stats = conditionPcm(pcm);
         this.lastAudioLevel = clampGain(Math.max(stats.peak() * 0.7F, stats.rms() * 2.2F));
         this.lastAudioLevelNanos = System.nanoTime();
         int samples = pcm.length;
         int channels = pcm[0].length;
         if (!this.didFirstDiag) {
            this.didFirstDiag = true;
            LOGGER.debug("Dolby PCM: {}s×{}ch range=[{},{}] rms={} gated={}", new Object[]{samples, channels, stats.min, stats.max, stats.rms, stats.gated});
         }

         float[][] pcmCh = new float[channels][samples];

         for (int i = 0; i < samples; i++) {
            for (int ch = 0; ch < channels; ch++) {
               pcmCh[ch][i] = pcm[i][ch];
            }
         }

         List<byte[]> emdfBlocks = Eac3AtmosParser.findEmdfBlocks(raw);
         Eac3AtmosParser.OamdConfig oamd = null;
         Eac3JocDecoder.JocResult jocResult = null;

         for (byte[] emdf : emdfBlocks) {
            if (oamd == null) {
               byte[] op = Eac3AtmosParser.extractOamdPayload(emdf);
               if (op != null) {
                  oamd = Eac3AtmosParser.parseOamd(op);
               }
            }

            if (BiliConfig.dolbyJocEnabled && jocResult == null) {
               byte[] jp = Eac3AtmosParser.extractJocPayload(emdf);
               if (jp != null) {
                  try {
                     jocResult = this.jocDecoder.decode(jp, 4);
                  } catch (Exception var25) {
                     if (!this.didJocDecodeFailDiag) {
                        this.didJocDecodeFailDiag = true;
                        LOGGER.warn("Dolby JOC 元数据解码失败，本帧降级为 bed 音频", var25);
                     }
                  }
               }
            }
         }

         int rawObjCount = 0;
         if (jocResult != null) {
            int audioObjects = Math.max(0, jocResult.config.objectCount);
            int metadataObjects = oamd != null && oamd.objectElement != null ? oamd.objectElement.dynamicObjects : audioObjects;
            rawObjCount = Math.min(Math.max(0, metadataObjects), audioObjects);
         }

         int objCount = BiliConfig.dolbyJocEnabled ? Math.max(0, Math.min(rawObjCount, BiliConfig.dolbyMaxObjectSources())) : 0;
         float[][] objPcmCh = null;
         if (BiliConfig.dolbyJocEnabled && jocResult != null && objCount > 0) {
            try {
               objPcmCh = new float[objCount][samples];
               float gain = jocResult.config.gain;
               int jocChannels = jocResult.config.channelCount;

               for (int blk = 0; blk < 6; blk++) {
                  int off = blk * 256;
                  float[][] blockPcm = DolbySpatialLayout.buildJocDownmixBlock(pcmCh, channels, jocChannels, off);
                  float[][][][] bedQmf = QmfFilterBank.forwardMulti(blockPcm);
                  float[][][][] objQmf = QmfFilterBank.applyMixingMatrix(bedQmf, jocResult.interpolatedMatrices, jocChannels, objCount);
                  float[][] objBlockPcm = QmfFilterBank.inverseMulti(objQmf);

                  for (int obj = 0; obj < objCount; obj++) {
                     for (int i = 0; i < 256; i++) {
                        objPcmCh[obj][off + i] = softClip(objBlockPcm[obj][i] * gain * 0.2F);
                     }
                  }
               }

               this.lastJocSequence = jocResult.config.sequence;
               this.lastJocAudioObjects = objCount;
               this.lastJocObjectRms = DolbySpatialLayout.rmsByObject(objPcmCh, objCount);
               this.lastJocObjectPeak = DolbySpatialLayout.peakByObject(objPcmCh, objCount);
            } catch (RuntimeException var24) {
               objCount = 0;
               objPcmCh = null;
               if (!this.didJocAudioFailDiag) {
                  this.didJocAudioFailDiag = true;
                  LOGGER.warn("Dolby JOC 对象音频重建失败，本帧降级为 5.1 bed", var24);
               }
            }
         }

         if (!this.didFirstDiagSpatial) {
            this.didFirstDiagSpatial = true;
            boolean ho = oamd != null && oamd.objectElement != null;
            boolean hj = jocResult != null;
            if (!ho && !hj) {
               LOGGER.debug("Dolby spatial: none (5.1 bed only)");
            } else {
               StringBuilder sb = new StringBuilder("Dolby spatial: ");
               if (oamd != null && oamd.objectElement != null) {
                  sb.append(String.format("OAMD objs=%d updates=%d", oamd.objectElement.dynamicObjects, oamd.objectElement.validPositionUpdates));
               }

               if (jocResult != null) {
                  if (ho) {
                     sb.append(", ");
                  }

                  sb.append(
                     String.format(
                        "JOC objs=%d/%d ch=%d gain=%.2f audio=%s",
                        Math.min(jocResult.config.objectCount, BiliConfig.dolbyMaxObjectSources()),
                        jocResult.config.objectCount,
                        jocResult.config.channelCount,
                        jocResult.config.gain,
                        BiliConfig.dolbyJocEnabled ? "on" : "off"
                     )
                  );
               }

               LOGGER.debug(sb.toString());
            }
         }

         return new DolbyAudioHandler.ProcessedFrame(pcmCh, objPcmCh, oamd, channels, objCount);
      }
   }

   private DolbyAudioHandler.FeedResult feedOpenAL(DolbyAudioHandler.ProcessedFrame pf) {
      if (this.closed) {
         return DolbyAudioHandler.FeedResult.DROP;
      } else {
         int chs = pf.channels;
         int objs = Math.max(0, Math.min(pf.objCount, BiliConfig.dolbyMaxObjectSources()));
         int targetObjects = BiliConfig.dolbyJocEnabled ? objs : 0;
         if (!this.initialized || this.numBedChannels != chs || targetObjects > this.numObjects) {
            float[][] oldObjectPositions = this.objectPositions;
            int newObjectCapacity = this.initialized && this.numBedChannels == chs ? Math.max(this.numObjects, targetObjects) : targetObjects;
            OpenALSpatialAudio oldSA = this.spatialAudio;
            if (oldSA != null) {
               oldSA.cleanup();
            }

            OpenALSpatialAudio next = new OpenALSpatialAudio();
            if (!next.init(chs, newObjectCapacity)) {
               next.cleanup();
               this.spatialAudio = null;
               this.initialized = false;
               return DolbyAudioHandler.FeedResult.DROP;
            }

            next.setPaused(this.paused);
            this.spatialAudio = next;
            PlaybackLatencyBench.markAudioOpenAlInitialized(this, "dolby", 48000);
            this.numBedChannels = chs;
            this.numObjects = newObjectCapacity;
            this.bedPositions = DolbySpatialLayout.computeBedPositions(chs, 1.5F);
            this.objectPositions = new float[newObjectCapacity][3];
            if (oldObjectPositions != null) {
               for (int i = 0; i < Math.min(oldObjectPositions.length, this.objectPositions.length); i++) {
                  System.arraycopy(oldObjectPositions[i], 0, this.objectPositions[i], 0, 3);
               }
            }

            this.initialized = true;
            LOGGER.debug("Dolby OpenAL 配置: beds={}, objectCapacity={} (frameObjects={})", new Object[]{chs, newObjectCapacity, objs});
         }

         OpenALSpatialAudio sa = this.spatialAudio;
         if (sa == null) {
            return DolbyAudioHandler.FeedResult.DROP;
         } else {
            if (pf.oamd != null) {
               this.updateObjectOffsets(pf.oamd);
            }

            float[][] pc = pf.pcmCh;
            if (!sa.updateFrame(pc, pf.objPcmCh, 6)) {
               return DolbyAudioHandler.FeedResult.RETRY;
            } else {
               this.totalFramesFedToOpenAL++;
               PlaybackLatencyBench.markAudioFed(this, "dolby", 1, this.totalFramesFedToOpenAL * 1536L, this.processedQueue.size(), 48000);
               this.feedRelays(pf);
               return DolbyAudioHandler.FeedResult.FED;
            }
         }
      }
   }

   private void feedRelays(DolbyAudioHandler.ProcessedFrame pf) {
      if (!this.relays.isEmpty() && pf != null && pf.pcmCh != null && pf.pcmCh.length != 0) {
         List<SpeakerAudioRelay> mixTargets = this.autoMixTargets(pf.pcmCh.length);
         int mixTargetCount = mixTargets.size();

         for (SpeakerAudioRelay relay : this.relays) {
            float[] mixed = SpeakerChannelMixer.baseMix(pf.pcmCh, relay.getChannelIndex());
            if (mixed != null) {
               if (relay.isAutoMixJoc() && mixTargetCount != 0) {
                  this.mixUnassignedBedChannels(mixed, pf.pcmCh, relay, mixTargets, mixTargetCount);
                  this.mixJocObjects(mixed, pf.objPcmCh, relay, mixTargets, mixTargetCount);
                  relay.feedMono(mixed);
               } else {
                  relay.feedChannel(mixed);
               }
            }
         }
      }
   }

   private List<SpeakerAudioRelay> autoMixTargets(int channelCount) {
      List<SpeakerAudioRelay> targets = new ArrayList<>();

      for (SpeakerAudioRelay relay : this.relays) {
         int ch = relay.getChannelIndex();
         if (relay.isAutoMixJoc() && SpeakerChannelMixer.primarySourceChannel(ch, channelCount) >= 0) {
            targets.add(relay);
         }
      }

      return targets;
   }

   private void mixUnassignedBedChannels(float[] mixed, float[][] pcmCh, SpeakerAudioRelay relay, List<SpeakerAudioRelay> targets, int targetCount) {
      int relayChannel = relay.getChannelIndex();

      for (int ch = 0; ch < pcmCh.length; ch++) {
         if (!SpeakerChannelMixer.isSourceClaimed(ch, pcmCh.length, this.relays)) {
            int targetIndex = this.nearestRelayIndexForBedChannel(ch, targets);
            if (targetIndex >= 0 && targets.get(targetIndex) == relay) {
               SpeakerChannelMixer.mixInto(mixed, pcmCh[ch], this.bedMixGain(ch, relayChannel, targetCount));
            }
         }
      }
   }

   private void mixJocObjects(float[] mixed, float[][] objPcmCh, SpeakerAudioRelay relay, List<SpeakerAudioRelay> targets, int targetCount) {
      if (objPcmCh != null && objPcmCh.length != 0) {
         for (int obj = 0; obj < objPcmCh.length; obj++) {
            int targetIndex = this.nearestRelayIndexForObject(obj, targets);
            if (targetIndex >= 0 && targets.get(targetIndex) == relay) {
               SpeakerChannelMixer.mixInto(mixed, objPcmCh[obj], this.objectMixGain(targetCount));
            }
         }
      }
   }

   private int nearestRelayIndexForBedChannel(int bedChannel, List<SpeakerAudioRelay> targets) {
      float[] source = this.channelPosition(bedChannel);
      int best = -1;
      float bestScore = Float.MAX_VALUE;

      for (int i = 0; i < targets.size(); i++) {
         int logicalChannel = targets.get(i).getChannelIndex();
         float score = positionDistanceSquared(source, this.channelPosition(logicalChannel));
         if (score < bestScore) {
            bestScore = score;
            best = i;
         }
      }

      return best;
   }

   private int nearestRelayIndexForObject(int obj, List<SpeakerAudioRelay> targets) {
      float[] source = this.objectPositions != null && obj >= 0 && obj < this.objectPositions.length ? this.objectPositions[obj] : null;
      if (source == null) {
         return targets.isEmpty() ? -1 : obj % targets.size();
      } else {
         int best = -1;
         float bestScore = Float.MAX_VALUE;

         for (int i = 0; i < targets.size(); i++) {
            int logicalChannel = targets.get(i).getChannelIndex();
            float score = positionDistanceSquared(source, this.channelPosition(logicalChannel));
            if (score < bestScore) {
               bestScore = score;
               best = i;
            }
         }

         return best;
      }
   }

   private float[] channelPosition(int channelIndex) {
      return this.bedPositions != null && channelIndex >= 0 && channelIndex < this.bedPositions.length
         ? this.bedPositions[channelIndex]
         : approximateChannelPosition(channelIndex);
   }

   private float bedMixGain(int sourceChannel, int targetChannel, int targetCount) {
      if (sourceChannel == 3) {
         return 0.45F / Math.max(1, targetCount);
      } else {
         return targetChannel == 3 ? 0.35F : 0.65F;
      }
   }

   private float objectMixGain(int targetCount) {
      return 0.85F;
   }

   private static float positionDistanceSquared(float[] a, float[] b) {
      if (a != null && b != null) {
         float dx = a[0] - b[0];
         float dy = a[1] - b[1];
         float dz = a[2] - b[2];
         return dx * dx + dy * dy + dz * dz;
      } else {
         return Float.MAX_VALUE;
      }
   }

   private static float[] approximateChannelPosition(int channelIndex) {
      float[][] positions = new float[][]{
         {-0.5F, 0.0F, 0.86F},
         {0.5F, 0.0F, 0.86F},
         {0.0F, 0.0F, 1.0F},
         {0.0F, 0.0F, 0.0F},
         {-1.0F, 0.0F, 0.0F},
         {1.0F, 0.0F, 0.0F},
         {-0.5F, 0.0F, -0.86F},
         {0.5F, 0.0F, -0.86F},
         {-0.5F, 1.0F, 0.86F},
         {0.5F, 1.0F, 0.86F},
         {-0.5F, 1.0F, -0.86F},
         {0.5F, 1.0F, -0.86F}
      };
      return channelIndex >= 0 && channelIndex < positions.length ? positions[channelIndex] : positions[2];
   }

   private void updateFrameBudget() {
      long now = System.nanoTime();
      if (this.lastFrameFeedNanos == 0L) {
         this.lastFrameFeedNanos = now;
      } else {
         double elapsedSeconds = Math.max(0.0, (now - this.lastFrameFeedNanos) / 1.0E9);
         this.lastFrameFeedNanos = now;
         this.frameBudget = Math.min(8.0, this.frameBudget + elapsedSeconds * 31.25);
      }
   }

   private static long ticksToEc3Frames(long ticks) {
      long samples = Math.max(0L, ticks) * 2400L;
      return Math.max(0L, samples / 1536L);
   }

   private static DolbyAudioHandler.PcmStats conditionPcm(float[][] pcm) {
      float min = 0.0F;
      float max = 0.0F;
      float peak = 0.0F;
      double sq = 0.0;
      int n = 0;

      for (float[] sf : pcm) {
         for (float s : sf) {
            if (s > max) {
               max = s;
            }

            if (s < min) {
               min = s;
            }

            peak = Math.max(peak, Math.abs(s));
            sq += s * s;
            n++;
         }
      }

      float rms = n > 0 ? (float)Math.sqrt(sq / n) : 0.0F;
      boolean g = peak < 1.0E-4F && rms < 2.0E-5F;

      for (float[] sf : pcm) {
         for (int ch = 0; ch < sf.length; ch++) {
            float s = g ? 0.0F : sf[ch] * 1.0F;
            sf[ch] = Math.max(-1.0F, Math.min(1.0F, s));
         }
      }

      return new DolbyAudioHandler.PcmStats(min, max, peak, rms, g);
   }

   private void updateSpatialState(float[] mp, float[] lp, boolean followLocalPlayerFront) {
      if (!this.closed && this.initialized) {
         OpenALSpatialAudio sa = this.spatialAudio;
         if (sa != null && this.bedPositions != null) {
            float yaw = (float)Math.atan2(mp[0] - lp[0], mp[2] - lp[2]);
            float[] forward = this.frontSmoother.update(this.forwardToMachine(mp, lp), followLocalPlayerFront);
            if (!this.didFirstPositionDiag) {
               this.didFirstPositionDiag = true;
               int ci = DolbySpatialLayout.centerChannelIndex(this.numBedChannels);
               float[] cp = ci < this.bedPositions.length ? this.bedPositions[ci] : new float[]{0.0F, 0.0F, 0.0F};
               LOGGER.debug(
                  "Dolby spatial map active: world-follow front->machine yaw={}deg centerLocal=({}, {}, {}) listener=({}, {}, {}) objects={}",
                  new Object[]{
                     String.format("%.1f", Math.toDegrees(yaw)),
                     String.format("%.2f", cp[0]),
                     String.format("%.2f", cp[1]),
                     String.format("%.2f", cp[2]),
                     String.format("%.2f", lp[0]),
                     String.format("%.2f", lp[1]),
                     String.format("%.2f", lp[2]),
                     this.numObjects
                  }
               );
            }

            sa.updatePositions(this.bedPositions, this.objectPositions, lp, forward);
            float d = distance(lp, mp);
            float g = spatialGainForDistance(d, this.userVolume);
            boolean routeMuted = SpeakerRelayMutePolicy.shouldMuteMain(
               MUTE_MAIN_WHEN_RELAYS_CONNECTED, this.relays, followLocalPlayerFront, this.consoleRouteSuppressed
            );
            float entryGain = this.presentationEnvelope.gain(!routeMuted && g > 0.0F, System.nanoTime());
            float gv = routeMuted ? 0.0F : g * entryGain * gameVolume();

            for (int ch = 0; ch < this.numBedChannels; ch++) {
               sa.setBedGain(ch, this.channelGain(ch, gv));
            }

            for (int o = 0; o < this.numObjects; o++) {
               sa.setObjectGain(o, this.channelGain(o + this.numBedChannels, gv));
            }
         }
      }
   }

   private float channelGain(int channelIndex, float baseGain) {
      if (this.channelMask == 0) {
         return baseGain;
      } else {
         int bit = channelBitForIndex(channelIndex);
         if (bit < 0) {
            return baseGain;
         } else {
            return (this.channelMask & bit) != 0 ? baseGain : 0.0F;
         }
      }
   }

   private static int channelBitForIndex(int index) {
      return switch (index) {
         case 0 -> 1;
         case 1 -> 2;
         case 2 -> 4;
         case 3 -> 8;
         case 4 -> 16;
         case 5 -> 32;
         case 6 -> 64;
         case 7 -> 128;
         case 8 -> 256;
         case 9 -> 512;
         case 10 -> 1024;
         case 11 -> 2048;
         default -> -1;
      };
   }

   private static float gameVolume() {
      Minecraft mc = Minecraft.getInstance();
      return mc != null && mc.options != null
         ? mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.RECORDS)
         : 1.0F;
   }

   private void updateObjectOffsets(Eac3AtmosParser.OamdConfig oamd) {
      if (this.objectPositions != null && this.numObjects != 0) {
         if (this.forceStaticJoc) {
            for (int o = 0; o < this.numObjects; o++) {
               this.objectPositions[o][0] = 0.0F;
               this.objectPositions[o][1] = 0.0F;
               this.objectPositions[o][2] = 0.0F;
            }
         } else if (oamd != null && oamd.objectElement != null && oamd.objectElement.firstPositions != null) {
            for (Eac3AtmosParser.ObjectPosition op : oamd.objectElement.firstPositions) {
               if (op.objectIndex >= 0 && op.objectIndex < this.numObjects) {
                  this.objectPositions[op.objectIndex][0] = op.x * 1.5F;
                  this.objectPositions[op.objectIndex][1] = op.z * 1.5F;
                  this.objectPositions[op.objectIndex][2] = op.y * 1.5F;
               }
            }
         } else {
            for (int o = 0; o < this.numObjects; o++) {
               this.objectPositions[o][0] = 0.0F;
               this.objectPositions[o][1] = 0.0F;
               this.objectPositions[o][2] = 0.0F;
            }
         }
      }
   }

   private static float spatialGainForDistance(float d, float volume) {
      return AudioUtils.spatialGainForDistance(d, volume);
   }

   private static float clampGain(float g) {
      return AudioUtils.clampGain(g);
   }

   private static float distance(float[] a, float[] b) {
      return AudioUtils.distance(a, b);
   }

   private float[] forwardToMachine(float[] mp, float[] lp) {
      this.forwardToMachine[0] = mp[0] - lp[0];
      this.forwardToMachine[1] = 0.0F;
      this.forwardToMachine[2] = mp[2] - lp[2];
      return this.forwardToMachine;
   }

   private static String fmtPos(float[] p) {
      return AudioUtils.fmtPos(p);
   }

   private static float softClip(float s) {
      return s / (1.0F + Math.abs(s));
   }

   private static enum FeedResult {
      FED,
      RETRY,
      DROP;
   }

   private record PcmStats(float min, float max, float peak, float rms, boolean gated) {
   }

   private static class ProcessedFrame {
      final float[][] pcmCh;
      final float[][] objPcmCh;
      final Eac3AtmosParser.OamdConfig oamd;
      final int channels;
      final int objCount;

      ProcessedFrame(float[][] pc, float[][] oc, Eac3AtmosParser.OamdConfig oa, int ch, int ob) {
         this.pcmCh = pc;
         this.objPcmCh = oc;
         this.oamd = oa;
         this.channels = ch;
         this.objCount = ob;
      }
   }
}
