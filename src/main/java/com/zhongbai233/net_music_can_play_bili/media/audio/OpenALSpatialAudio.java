package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import net.neoforged.fml.ModList;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

public class OpenALSpatialAudio {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int NUM_BUFFERS = 48;
   private static final int MAX_PENDING_BLOCKS = 2048;
   private static final int SAMPLES_PER_BUFFER = 256;
   private static final int SAMPLE_RATE = 48000;
   private static final float[] ZERO_LOCAL_POSITION = new float[]{0.0F, 0.0F, 0.0F};
   private int actualSampleRate = 48000;
   private static final OpenAlHrtfProperties.Settings HRTF_PROPERTIES = OpenAlHrtfProperties.settings();
   private static volatile boolean hrtfAttempted;
   private int[] bedSources;
   private int[] objectSources;
   private int[][] bedBuffers;
   private int[][] objBuffers;
   private ArrayDeque<float[]>[] bedPending;
   private ArrayDeque<float[]>[] objPending;
   private float[] bedGains;
   private float[] objectGains;
   private ByteBuffer uploadScratch;
   private final float[] lastFrontToMachine = new float[]{0.0F, 0.0F, 1.0F};
   private boolean useFloat32;
   private int monoFormat = 4353;
   private int bytesPerSample = 2;
   private int numBeds;
   private int numObjects;
   private boolean initialized;
   private volatile boolean deviceLost;
   private long mediaConsumedBuffers;
   private ArrayDeque<Boolean> primaryQueuedMediaFlags;
   private boolean paused;

   public boolean init(int numBedChannels, int numDynamicObjects) {
      return this.init(numBedChannels, numDynamicObjects, 48000);
   }

   public synchronized boolean init(int numBedChannels, int numDynamicObjects, int sampleRate) {
      if (!MinecraftOpenAlContext.ensure("init")) {
         return false;
      } else {
         OpenALNativeDeleteQueue.drainNow();
         this.cleanup();

         try {
            this.detectAudioFormat();
            ensureHrtfEnabled();
            this.actualSampleRate = sampleRate;
            this.numBeds = Math.max(0, numBedChannels);
            this.numObjects = Math.max(0, numDynamicObjects);
            this.deviceLost = false;
            this.mediaConsumedBuffers = 0L;
            this.primaryQueuedMediaFlags = new ArrayDeque<>(96);
            this.uploadScratch = MemoryUtil.memAlloc(256 * this.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
            MemoryResourceTracker.allocated(MemoryResourceTracker.Category.AUDIO_STAGING, this.uploadScratch.capacity());
            this.bedSources = new int[this.numBeds];
            this.bedBuffers = new int[this.numBeds][48];
            this.bedPending = newPendingQueues(this.numBeds);
            this.bedGains = filledGains(this.numBeds);
            if (!this.initSourceGroup("bed", this.numBeds, this.bedSources, this.bedBuffers)) {
               return false;
            } else {
               this.objectSources = new int[this.numObjects];
               this.objBuffers = new int[this.numObjects][48];
               this.objPending = newPendingQueues(this.numObjects);
               this.objectGains = filledGains(this.numObjects);
               if (!this.initSourceGroup("object", this.numObjects, this.objectSources, this.objBuffers)) {
                  return false;
               } else if (this.primaryQueuedMediaFlags == null) {
                  LOGGER.warn("OpenAL 空间声初始化期间媒体队列标记被清理，放弃本次初始化: beds={} objects={}", this.numBeds, this.numObjects);
                  this.cleanup();
                  return false;
               } else {
                  for (int b = 0; b < 48; b++) {
                     this.primaryQueuedMediaFlags.offerLast(Boolean.FALSE);
                  }

                  for (int ch = 0; ch < this.numBeds; ch++) {
                     AL10.alSourcePlay(this.bedSources[ch]);
                  }

                  for (int obj = 0; obj < this.numObjects; obj++) {
                     AL10.alSourcePlay(this.objectSources[obj]);
                  }

                  this.initialized = true;
                  this.setPaused(this.paused);
                  LOGGER.debug(
                     "OpenAL 空间声初始化摘要: beds={} objects={} format={} sampleRate={}Hz sourceMode=world-follow spatialize=force hrtf={} preloadBuffers={} preload={}ms",
                     new Object[]{
                        this.numBeds,
                        this.numObjects,
                        this.useFloat32 ? "float32" : "int16",
                        this.actualSampleRate,
                        HRTF_PROPERTIES.forceHrtf() ? "force" : "vanilla",
                        48,
                        Math.round(1.2288E7 / this.actualSampleRate)
                     }
                  );
                  return true;
               }
            }
         } catch (Throwable var5) {
            LOGGER.warn(
               "OpenAL 空间声初始化失败，已跳过本次输出管线: beds={} objects={} sampleRate={} reason={}",
               new Object[]{numBedChannels, numDynamicObjects, sampleRate, var5.toString()}
            );
            this.cleanup();
            return false;
         }
      }
   }

   public synchronized boolean updateBedBlock(float[][] pcmBlock) {
      if (this.initialized && pcmBlock != null) {
         int channels = Math.min(this.numBeds, pcmBlock.length);
         if (channels > 0 && hasPendingCapacity(this.bedPending, channels)) {
            for (int ch = 0; ch < channels; ch++) {
               this.enqueuePending(this.bedPending[ch], pcmBlock[ch]);
            }

            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public synchronized void updateBedFrameBlock(float[][] pcmByChannel, int offset) {
      if (this.initialized) {
         for (int ch = 0; ch < Math.min(this.numBeds, pcmByChannel.length); ch++) {
            this.enqueuePending(this.bedPending[ch], pcmByChannel[ch], offset);
         }
      }
   }

   public synchronized void updateObjectBlock(float[][] objBlock) {
      if (this.initialized) {
         for (int obj = 0; obj < this.numObjects; obj++) {
            float[] pcm = objBlock != null && obj < objBlock.length ? objBlock[obj] : null;
            this.enqueuePending(this.objPending[obj], pcm);
         }
      }
   }

   public synchronized void updateObjectFrameBlock(float[][] objByChannel, int offset) {
      if (this.initialized) {
         for (int obj = 0; obj < this.numObjects; obj++) {
            float[] pcm = objByChannel != null && obj < objByChannel.length ? objByChannel[obj] : null;
            this.enqueuePending(this.objPending[obj], pcm, offset);
         }
      }
   }

   public synchronized boolean updateFrame(float[][] pcmByChannel, float[][] objByChannel, int blockCount) {
      if (this.initialized && pcmByChannel != null && blockCount > 0) {
         int bedChannels = Math.min(this.numBeds, pcmByChannel.length);
         if (bedChannels > 0
            && hasPendingCapacity(this.bedPending, bedChannels, blockCount)
            && (this.numObjects <= 0 || hasPendingCapacity(this.objPending, this.numObjects, blockCount))) {
            for (int block = 0; block < blockCount; block++) {
               int offset = block * 256;

               for (int ch = 0; ch < bedChannels; ch++) {
                  this.enqueuePending(this.bedPending[ch], pcmByChannel[ch], offset);
               }

               for (int obj = 0; obj < this.numObjects; obj++) {
                  float[] pcm = objByChannel != null && obj < objByChannel.length ? objByChannel[obj] : null;
                  this.enqueuePending(this.objPending[obj], pcm, offset);
               }
            }

            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public synchronized void pumpQueuedAudio() {
      if (this.initialized && !this.deviceLost) {
         if (MinecraftOpenAlContext.ensure("pumpQueuedAudio")) {
            for (int ch = 0; ch < this.numBeds; ch++) {
               this.pumpSource(this.bedSources[ch], this.bedPending[ch], 1.0F);
            }

            for (int obj = 0; obj < this.numObjects; obj++) {
               this.pumpSource(this.objectSources[obj], this.objPending[obj], 1.0F);
            }
         }
      }
   }

   public synchronized void setPaused(boolean paused) {
      this.paused = paused;
      if (this.initialized && !this.deviceLost && MinecraftOpenAlContext.ensure(paused ? "pause" : "resume")) {
         this.applyPausedState(this.bedSources, paused);
         this.applyPausedState(this.objectSources, paused);
      }
   }

   public synchronized boolean isPaused() {
      return this.paused;
   }

   public synchronized void updatePositions(float[][] bedPositions, float[][] objectPositions, float[] listenerPos, float[] listenerForward) {
      if (this.initialized && listenerPos != null && listenerPos.length >= 3) {
         if (MinecraftOpenAlContext.ensure("updatePositions")) {
            float[] front = this.frontToMachine(listenerForward);

            for (int ch = 0; ch < this.numBeds; ch++) {
               float[] pos = bedPositions != null && ch < bedPositions.length ? bedPositions[ch] : ZERO_LOCAL_POSITION;
               updateWorldSourcePosition(this.bedSources[ch], listenerPos, front, pos);
            }

            for (int obj = 0; obj < this.numObjects; obj++) {
               float[] pos = objectPositions != null && obj < objectPositions.length ? objectPositions[obj] : ZERO_LOCAL_POSITION;
               updateWorldSourcePosition(this.objectSources[obj], listenerPos, front, pos);
            }
         }
      }
   }

   public synchronized void setBedGain(int channel, float gain) {
      if (this.initialized && !this.deviceLost && channel >= 0 && this.bedSources != null && channel < this.bedSources.length) {
         if (!MinecraftOpenAlContext.ensure("setBedGain")) {
            return;
         }

         float clamped = clampGain(gain);
         if (this.bedGains != null && channel < this.bedGains.length) {
            this.bedGains[channel] = clamped;
         }

         AL10.alSourcef(this.bedSources[channel], 4106, clamped);
      }
   }

   public synchronized void setObjectGain(int obj, float gain) {
      if (this.initialized && !this.deviceLost && obj >= 0 && this.objectSources != null && obj < this.objectSources.length) {
         if (!MinecraftOpenAlContext.ensure("setObjectGain")) {
            return;
         }

         float clamped = clampGain(gain);
         if (this.objectGains != null && obj < this.objectGains.length) {
            this.objectGains[obj] = clamped;
         }

         AL10.alSourcef(this.objectSources[obj], 4106, clamped);
      }
   }

   public int getNumBeds() {
      return this.numBeds;
   }

   public int getNumObjects() {
      return this.numObjects;
   }

   public synchronized int pendingMediaBlocks() {
      return Math.max(maxPendingSize(this.bedPending), maxPendingSize(this.objPending));
   }

   private static int maxPendingSize(ArrayDeque<float[]>[] queues) {
      int max = 0;
      if (queues != null) {
         for (ArrayDeque<float[]> queue : queues) {
            if (queue != null) {
               max = Math.max(max, queue.size());
            }
         }
      }

      return max;
   }

   public synchronized long getConsumedSamples() {
      long baseSamples = this.mediaConsumedBuffers * 256L;
      if (this.initialized && !this.deviceLost && MinecraftOpenAlContext.ensure("getConsumedSamples")) {
         int source = this.primarySource();
         if (source == 0) {
            return baseSamples;
         } else {
            int byteOffset;
            try {
               byteOffset = AL10.alGetSourcei(source, 4134);
            } catch (Throwable var7) {
               return baseSamples;
            }

            if (this.checkDeviceLost("getConsumedSamples:alGetSourcei")) {
               return baseSamples;
            } else if (this.primaryQueuedMediaFlags != null && Boolean.TRUE.equals(this.primaryQueuedMediaFlags.peekFirst())) {
               long sampleOffset = Math.max(0L, (long)(byteOffset / Math.max(1, this.bytesPerSample)));
               return baseSamples + Math.min(255L, sampleOffset);
            } else {
               return baseSamples;
            }
         }
      } else {
         return baseSamples;
      }
   }

   public synchronized long getOutputDelaySamples() {
      if (this.initialized && !this.deviceLost && MinecraftOpenAlContext.ensure("getOutputDelaySamples")) {
         int source = this.primarySource();
         if (source != 0
            && this.primaryQueuedMediaFlags != null
            && !this.primaryQueuedMediaFlags.isEmpty()
            && !Boolean.TRUE.equals(this.primaryQueuedMediaFlags.peekFirst())) {
            int byteOffset;
            try {
               byteOffset = AL10.alGetSourcei(source, 4134);
            } catch (Throwable var9) {
               return 0L;
            }

            if (this.checkDeviceLost("getOutputDelaySamples:alGetSourcei")) {
               return 0L;
            } else {
               long sampleOffset = Math.max(0L, (long)(byteOffset / Math.max(1, this.bytesPerSample)));
               long delayBuffers = 0L;

               for (Boolean media : this.primaryQueuedMediaFlags) {
                  if (Boolean.TRUE.equals(media)) {
                     break;
                  }

                  delayBuffers++;
               }

               if (delayBuffers <= 0L) {
                  return 0L;
               } else {
                  long delaySamples = delayBuffers * 256L;
                  return Math.max(0L, delaySamples - Math.min(255L, sampleOffset));
               }
            }
         } else {
            return 0L;
         }
      } else {
         return 0L;
      }
   }

   public synchronized long flushQueuedAudio() {
      long consumedSamples = this.getConsumedSamples();
      if (this.initialized && !this.deviceLost && MinecraftOpenAlContext.ensure("flushQueuedAudio")) {
         clearPendingQueues(this.bedPending);
         clearPendingQueues(this.objPending);
         this.flushSourceGroup(this.bedSources, this.bedBuffers);
         this.flushSourceGroup(this.objectSources, this.objBuffers);
         if (this.primaryQueuedMediaFlags != null) {
            this.primaryQueuedMediaFlags.clear();

            for (int b = 0; b < 48; b++) {
               this.primaryQueuedMediaFlags.offerLast(Boolean.FALSE);
            }
         }

         return consumedSamples;
      } else {
         return consumedSamples;
      }
   }

   public synchronized long flushQueuedAudio(long mediaPositionSamples) {
      long consumedSamples = this.flushQueuedAudio();
      long baselineSamples = Math.max(consumedSamples, Math.max(0L, mediaPositionSamples));
      this.mediaConsumedBuffers = baselineSamples / 256L;
      return this.mediaConsumedBuffers * 256L;
   }

   public synchronized void hardStopOutput() {
      if (this.initialized && !this.deviceLost && MinecraftOpenAlContext.ensure("hardStopOutput")) {
         clearPendingQueues(this.bedPending);
         clearPendingQueues(this.objPending);
         this.stopAndClearSourceGroup(this.bedSources);
         this.stopAndClearSourceGroup(this.objectSources);
         if (this.primaryQueuedMediaFlags != null) {
            this.primaryQueuedMediaFlags.clear();
         }

         this.mediaConsumedBuffers = 0L;
      }
   }

   public synchronized void cleanup() {
      this.initialized = false;
      int[] bedSourcesToDelete = this.bedSources;
      int[] objectSourcesToDelete = this.objectSources;
      int[][] bedBuffersToDelete = this.bedBuffers;
      int[][] objBuffersToDelete = this.objBuffers;
      ByteBuffer uploadScratchToFree = this.uploadScratch;
      this.clearLocalReferences();
      if (uploadScratchToFree != null) {
         MemoryResourceTracker.freed(MemoryResourceTracker.Category.AUDIO_STAGING, uploadScratchToFree.capacity());
         MemoryUtil.memFree(uploadScratchToFree);
      }

      OpenALNativeDeleteQueue.enqueue(bedSourcesToDelete, objectSourcesToDelete, bedBuffersToDelete, objBuffersToDelete);
   }

   public static int pendingNativeDeleteBatches() {
      return OpenALNativeDeleteQueue.pendingBatches();
   }

   public static void tickNativeDeletes(long nowNanos) {
      OpenALNativeDeleteQueue.tick(nowNanos);
   }

   private void detectAudioFormat() {
      boolean hasFloat = AL10.alIsExtensionPresent("AL_EXT_FLOAT32");
      this.useFloat32 = hasFloat;
      this.monoFormat = hasFloat ? 65552 : 4353;
      this.bytesPerSample = hasFloat ? 4 : 2;
   }

   private void clearLocalReferences() {
      this.numBeds = 0;
      this.numObjects = 0;
      this.bedSources = null;
      this.objectSources = null;
      this.bedBuffers = null;
      this.objBuffers = null;
      this.bedPending = null;
      this.objPending = null;
      this.bedGains = null;
      this.objectGains = null;
      this.uploadScratch = null;
      this.deviceLost = false;
      this.primaryQueuedMediaFlags = null;
      this.mediaConsumedBuffers = 0L;
   }

   private int primarySource() {
      if (this.bedSources != null && this.bedSources.length > 0) {
         return this.bedSources[0];
      } else {
         return this.objectSources != null && this.objectSources.length > 0 ? this.objectSources[0] : 0;
      }
   }

   private static ArrayDeque<float[]>[] newPendingQueues(int count) {
      ArrayDeque<float[]>[] queues = new ArrayDeque[count];

      for (int i = 0; i < count; i++) {
         queues[i] = new ArrayDeque<>();
      }

      return queues;
   }

   private static float[] filledGains(int count) {
      float[] gains = new float[count];

      for (int i = 0; i < count; i++) {
         gains[i] = 1.0F;
      }

      return gains;
   }

   private boolean initSourceGroup(String label, int count, int[] sources, int[][] buffers) {
      for (int i = 0; i < count; i++) {
         sources[i] = genSource(label, i);
         if (sources[i] == 0) {
            this.cleanup();
            return false;
         }

         for (int b = 0; b < 48; b++) {
            buffers[i][b] = genBuffer(label, i, b);
            if (buffers[i][b] == 0) {
               this.cleanup();
               return false;
            }
         }

         ByteBuffer silence = MemoryUtil.memCalloc(256 * this.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
         MemoryResourceTracker.allocated(MemoryResourceTracker.Category.AUDIO_STAGING, silence.capacity());

         try {
            for (int bx = 0; bx < 48; bx++) {
               silence.clear();
               AL10.alBufferData(buffers[i][bx], this.monoFormat, silence, this.actualSampleRate);
               AL10.alSourceQueueBuffers(sources[i], buffers[i][bx]);
            }
         } finally {
            MemoryResourceTracker.freed(MemoryResourceTracker.Category.AUDIO_STAGING, silence.capacity());
            MemoryUtil.memFree(silence);
         }

         AL10.alSourcei(sources[i], 514, 0);
         forceSourceSpatialize(sources[i]);
         AL10.alSourcef(sources[i], 4128, 3.0F);
         AL10.alSourcef(sources[i], 4131, 48.0F);
         AL10.alSourcef(sources[i], 4129, 0.0F);
      }

      return true;
   }

   private void enqueuePending(ArrayDeque<float[]> queue, float[] pcm) {
      if (queue != null) {
         if (queue.size() < 2048) {
            float[] copy = new float[256];
            if (pcm != null) {
               System.arraycopy(pcm, 0, copy, 0, Math.min(256, pcm.length));
            }

            queue.offerLast(copy);
         }
      }
   }

   private static boolean hasPendingCapacity(ArrayDeque<float[]>[] queues, int count) {
      return hasPendingCapacity(queues, count, 1);
   }

   private static boolean hasPendingCapacity(ArrayDeque<float[]>[] queues, int count, int requiredBlocks) {
      if (queues != null && count > 0 && count <= queues.length) {
         for (int i = 0; i < count; i++) {
            if (queues[i] == null || queues[i].size() > 2048 - requiredBlocks) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private void enqueuePending(ArrayDeque<float[]> queue, float[] pcm, int offset) {
      if (queue != null) {
         if (queue.size() < 2048) {
            float[] copy = new float[256];
            if (pcm != null && offset < pcm.length) {
               System.arraycopy(pcm, offset, copy, 0, Math.min(256, pcm.length - offset));
            }

            queue.offerLast(copy);
         }
      }
   }

   private static void clearPendingQueues(ArrayDeque<float[]>[] queues) {
      if (queues != null) {
         for (ArrayDeque<float[]> queue : queues) {
            if (queue != null) {
               queue.clear();
            }
         }
      }
   }

   private void flushSourceGroup(int[] sources, int[][] buffers) {
      if (sources != null && buffers != null) {
         for (int i = 0; i < Math.min(sources.length, buffers.length); i++) {
            int source = sources[i];
            if (source != 0) {
               try {
                  AL10.alSourceStop(source);
                  int queued = AL10.alGetSourcei(source, 4117);

                  while (queued-- > 0) {
                     AL10.alSourceUnqueueBuffers(source);
                     if (this.checkDeviceLost("flushQueuedAudio:alSourceUnqueueBuffers")) {
                        return;
                     }
                  }

                  for (int buffer : buffers[i]) {
                     this.fillBuffer(buffer, null, 1.0F);
                     AL10.alSourceQueueBuffers(source, buffer);
                     if (this.checkDeviceLost("flushQueuedAudio:alSourceQueueBuffers")) {
                        return;
                     }
                  }

                  AL10.alSourcePlay(source);
                  if (this.paused) {
                     AL10.alSourcePause(source);
                  }
               } catch (Throwable var10) {
                  if (this.checkDeviceLost("flushQueuedAudio")) {
                     return;
                  }

                  LOGGER.debug("OpenAL source flush failed: {}", var10.toString());
               }
            }
         }
      }
   }

   private void stopAndClearSourceGroup(int[] sources) {
      if (sources != null) {
         for (int source : sources) {
            if (source != 0) {
               try {
                  AL10.alSourcef(source, 4106, 0.0F);
                  AL10.alSourceStop(source);
                  int queued = AL10.alGetSourcei(source, 4117);

                  while (queued-- > 0) {
                     AL10.alSourceUnqueueBuffers(source);
                     if (this.checkDeviceLost("hardStopOutput:alSourceUnqueueBuffers")) {
                        return;
                     }
                  }
               } catch (Throwable var7) {
                  if (this.checkDeviceLost("hardStopOutput")) {
                     return;
                  }

                  LOGGER.debug("OpenAL source hard-stop failed: {}", var7.toString());
               }
            }
         }
      }
   }

   private void pumpSource(int sourceId, ArrayDeque<float[]> pending, float gain) {
      int processed = AL10.alGetSourcei(sourceId, 4118);
      if (!this.checkDeviceLost("pumpSource:alGetSourcei")) {
         while (processed-- > 0) {
            int buf = AL10.alSourceUnqueueBuffers(sourceId);
            if (buf == 0 && this.checkDeviceLost("pumpSource:alSourceUnqueueBuffers")) {
               return;
            }

            float[] pcm = pending != null ? pending.pollFirst() : null;
            if (sourceId == this.primarySource()) {
               boolean consumedMedia = this.primaryQueuedMediaFlags != null && Boolean.TRUE.equals(this.primaryQueuedMediaFlags.pollFirst());
               if (consumedMedia) {
                  this.mediaConsumedBuffers++;
               }
            }

            this.fillBuffer(buf, pcm, gain);
            AL10.alSourceQueueBuffers(sourceId, buf);
            if (this.checkDeviceLost("pumpSource:alSourceQueueBuffers")) {
               return;
            }

            if (sourceId == this.primarySource() && this.primaryQueuedMediaFlags != null) {
               this.primaryQueuedMediaFlags.offerLast(pcm != null);
            }
         }

         if (!this.paused && AL10.alGetSourcei(sourceId, 4112) != 4114 && AL10.alGetSourcei(sourceId, 4117) > 0) {
            AL10.alSourcePlay(sourceId);
         }
      }
   }

   private void applyPausedState(int[] sources, boolean pause) {
      if (sources != null) {
         for (int source : sources) {
            if (source != 0) {
               try {
                  int state = AL10.alGetSourcei(source, 4112);
                  int queuedBuffers = !pause && state == 4116 ? AL10.alGetSourcei(source, 4117) : 0;
                  OpenAlPauseStatePolicy.Action action = OpenAlPauseStatePolicy.action(pause, pauseSourceState(state), queuedBuffers);
                  if (action == OpenAlPauseStatePolicy.Action.PAUSE) {
                     AL10.alSourcePause(source);
                  } else if (action == OpenAlPauseStatePolicy.Action.PLAY) {
                     AL10.alSourcePlay(source);
                  }

                  if (this.checkDeviceLost(pause ? "pauseSources" : "resumeSources")) {
                     return;
                  }
               } catch (Throwable var10) {
                  if (this.checkDeviceLost(pause ? "pauseSources" : "resumeSources")) {
                     return;
                  }

                  LOGGER.debug("OpenAL source {} failed: {}", pause ? "pause" : "resume", var10.toString());
               }
            }
         }
      }
   }

   private static OpenAlPauseStatePolicy.SourceState pauseSourceState(int state) {
      if (state == 4114) {
         return OpenAlPauseStatePolicy.SourceState.PLAYING;
      } else if (state == 4115) {
         return OpenAlPauseStatePolicy.SourceState.PAUSED;
      } else {
         return state == 4116 ? OpenAlPauseStatePolicy.SourceState.STOPPED : OpenAlPauseStatePolicy.SourceState.OTHER;
      }
   }

   private boolean checkDeviceLost(String context) {
      int err = AL10.alGetError();
      if (err == 0) {
         return false;
      } else if (err == 40961) {
         if (!this.deviceLost) {
            this.deviceLost = true;
            MinecraftOpenAlContext.invalidate();
            LOGGER.warn(
               "OpenAL device lost detected ({}): source/buffer handles invalidated. This can happen when another mod resets the OpenAL device (e.g. Sound Physics). Spatial audio will be reinitialized on next tick.",
               context
            );
         }

         return true;
      } else {
         LOGGER.debug("OpenAL error in {}: 0x{}", context, Integer.toHexString(err).toUpperCase());
         return false;
      }
   }

   public boolean isDeviceLost() {
      return this.deviceLost;
   }

   private void fillBuffer(int bufferId, float[] pcm, float gain) {
      int len = 256;
      ByteBuffer buf = this.uploadScratch;
      if (buf == null || buf.capacity() < len * this.bytesPerSample) {
         if (buf != null) {
            MemoryResourceTracker.freed(MemoryResourceTracker.Category.AUDIO_STAGING, buf.capacity());
            MemoryUtil.memFree(buf);
         }

         buf = MemoryUtil.memAlloc(len * this.bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
         MemoryResourceTracker.allocated(MemoryResourceTracker.Category.AUDIO_STAGING, buf.capacity());
         this.uploadScratch = buf;
      }

      buf.clear();
      if (this.useFloat32) {
         for (int i = 0; i < len; i++) {
            float sample = pcm != null && i < pcm.length ? pcm[i] * gain : 0.0F;
            buf.putFloat(Math.max(-1.0F, Math.min(1.0F, sample)));
         }
      } else {
         for (int i = 0; i < len; i++) {
            float sample = pcm != null && i < pcm.length ? pcm[i] * gain : 0.0F;
            int intSample = Math.round(sample * 32767.0F);
            intSample = Math.max(-32768, Math.min(32767, intSample));
            buf.putShort((short)intSample);
         }
      }

      buf.flip();
      AL10.alBufferData(bufferId, this.monoFormat, buf, this.actualSampleRate);
   }

   private float[] frontToMachine(float[] listenerForward) {
      if (listenerForward != null && listenerForward.length >= 3) {
         float fx = listenerForward[0];
         float fz = listenerForward[2];
         float len = (float)Math.sqrt(fx * fx + fz * fz);
         if (len > 0.15F) {
            this.lastFrontToMachine[0] = fx / len;
            this.lastFrontToMachine[1] = 0.0F;
            this.lastFrontToMachine[2] = fz / len;
         }
      }

      return this.lastFrontToMachine;
   }

   private static float clampGain(float gain) {
      return Math.max(0.0F, Math.min(1.0F, gain));
   }

   private static void updateWorldSourcePosition(int sourceId, float[] listenerPos, float[] front, float[] local) {
      float lx = local != null && local.length > 0 ? local[0] : 0.0F;
      float ly = local != null && local.length > 1 ? local[1] : 0.0F;
      float lz = local != null && local.length > 2 ? local[2] : 0.0F;
      float rightX = -front[2];
      float rightZ = front[0];
      float worldX = listenerPos[0] + rightX * lx + front[0] * lz;
      float worldY = listenerPos[1] + ly;
      float worldZ = listenerPos[2] + rightZ * lx + front[2] * lz;
      AL10.alSource3f(sourceId, 4100, worldX, worldY, worldZ);
   }

   private static void forceSourceSpatialize(int sourceId) {
      if (AL10.alIsExtensionPresent("AL_SOFT_source_spatialize")) {
         AL10.alSourcei(sourceId, 4628, 1);
      }
   }

   private static synchronized void ensureHrtfEnabled() {
      if (!hrtfAttempted && HRTF_PROPERTIES.forceHrtf()) {
         if (isChannelLoaded() && !HRTF_PROPERTIES.forceHrtfWithChannel()) {
            LOGGER.warn(
               "OpenAL HRTF: Channel mod detected; skip alcResetDeviceSOFT to avoid disrupting voice EFX sources. Set -Dncpb.dolby.force_hrtf_with_channel=true to override."
            );
            hrtfAttempted = true;
         } else {
            hrtfAttempted = true;
            long context = ALC10.alcGetCurrentContext();
            if (context == 0L) {
               LOGGER.warn("OpenAL HRTF: 当前没有 OpenAL context，跳过强制启用");
            } else {
               long device = ALC10.alcGetContextsDevice(context);
               if (device == 0L) {
                  LOGGER.warn("OpenAL HRTF: 无法获取 OpenAL device，跳过强制启用");
               } else if (!ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF")) {
                  LOGGER.warn("OpenAL HRTF: 当前设备不支持 ALC_SOFT_HRTF");
               } else {
                  IntBuffer attrs = BufferUtils.createIntBuffer(3);
                  attrs.put(6546).put(1).put(0).flip();

                  boolean ok;
                  try {
                     ok = SOFTHRTF.alcResetDeviceSOFT(device, attrs);
                  } catch (Throwable var7) {
                     LOGGER.warn("OpenAL HRTF: alcResetDeviceSOFT 调用失败", var7);
                     return;
                  }

                  int status = ALC10.alcGetInteger(device, 6547);
                  LOGGER.debug("OpenAL HRTF: reset={}, status={}", ok, hrtfStatusName(status));
               }
            }
         }
      }
   }

   private static String hrtfStatusName(int status) {
      return switch (status) {
         case 0 -> "disabled";
         case 1 -> "enabled";
         case 2 -> "denied";
         case 3 -> "required";
         case 4 -> "headphones-detected";
         case 5 -> "unsupported-format";
         default -> "unknown(" + status + ")";
      };
   }

   private static boolean isChannelLoaded() {
      try {
         return ModList.get().isLoaded("channel");
      } catch (Throwable var1) {
         return false;
      }
   }

   static void stopAndDelete(int sourceId) {
      AL10.alSourceStop(sourceId);
      int queued = AL10.alGetSourcei(sourceId, 4117);

      for (int i = 0; i < queued; i++) {
         AL10.alSourceUnqueueBuffers(sourceId);
      }

      AL10.alDeleteSources(sourceId);
   }

   private static int genSource(String type, int index) {
      clearAlErrors();
      int source = AL10.alGenSources();
      int err = AL10.alGetError();
      if (err != 0) {
         if (source != 0) {
            AL10.alDeleteSources(source);
         }

         LOGGER.error("OpenAL genSource({}[{}]) 失败: AL error 0x{}", new Object[]{type, index, Integer.toHexString(err).toUpperCase()});
         return 0;
      } else if (source == 0) {
         LOGGER.error("OpenAL genSource({}[{}]) 返回 0: 设备 source 已耗尽或未初始化", type, index);
         return 0;
      } else {
         return source;
      }
   }

   private static int genBuffer(String type, int channelOrObj, int bufferIdx) {
      clearAlErrors();
      int buffer = AL10.alGenBuffers();
      int err = AL10.alGetError();
      if (err != 0) {
         if (buffer != 0) {
            AL10.alDeleteBuffers(buffer);
         }

         LOGGER.error("OpenAL genBuffer({}[{}][{}]) 失败: AL error 0x{}", new Object[]{type, channelOrObj, bufferIdx, Integer.toHexString(err).toUpperCase()});
         return 0;
      } else if (buffer == 0) {
         LOGGER.error("OpenAL genBuffer({}[{}][{}]) 返回 0: 设备 buffer 资源已耗尽或未初始化", new Object[]{type, channelOrObj, bufferIdx});
         return 0;
      } else {
         return buffer;
      }
   }

   static void clearAlErrors() {
      int i = 0;

      while (i < 8 && AL10.alGetError() != 0) {
         i++;
      }
   }
}
