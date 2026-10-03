package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

public final class LiveVideoSampleBus {
   public static final String BUS_URL_PREFIX = "ncpb-live-bus:";
   private static final int DEFAULT_CAPACITY = AudioStreamProperties.liveVideoBusCapacity();
   private static final ConcurrentHashMap<PlaybackSessionId, LiveVideoSampleBus> REGISTRY = new ConcurrentHashMap<>();
   private final PlaybackSessionId playbackSessionId;
   private final int capacity;
   private final ArrayDeque<LiveVideoSampleBus.VideoSample> samples = new ArrayDeque<>();
   private final Object lock = new Object();
   private volatile byte[] avcConfig;
   private boolean anchorSet;
   private long anchorFlvMillis;
   private long anchorFedMillis;
   private boolean consumerNeedsKeyframe = true;
   private volatile boolean closed;
   private long droppedSamples;

   private LiveVideoSampleBus(PlaybackSessionId playbackSessionId, int capacity) {
      this.playbackSessionId = playbackSessionId;
      this.capacity = Math.max(8, capacity);
   }

   public static String busUrl(String key) {
      return busUrl(PlaybackSessionId.of(key));
   }

   public static String busUrl(PlaybackSessionId sessionId) {
      return "ncpb-live-bus:" + sessionId.value();
   }

   public static boolean isBusUrl(String url) {
      return url != null && url.startsWith("ncpb-live-bus:");
   }

   public static String keyFromBusUrl(String url) {
      return isBusUrl(url) ? url.substring("ncpb-live-bus:".length()) : "";
   }

   public static LiveVideoSampleBus register(String key) {
      return register(PlaybackSessionId.of(key));
   }

   public static LiveVideoSampleBus register(PlaybackSessionId sessionId) {
      LiveVideoSampleBus bus = new LiveVideoSampleBus(sessionId, DEFAULT_CAPACITY);
      LiveVideoSampleBus previous = REGISTRY.put(sessionId, bus);
      if (previous != null) {
         previous.close();
      }

      return bus;
   }

   public static LiveVideoSampleBus find(String key) {
      return PlaybackSessionId.parse(key).map(REGISTRY::get).orElse(null);
   }

   public static LiveVideoSampleBus find(PlaybackSessionId sessionId) {
      return sessionId != null ? REGISTRY.get(sessionId) : null;
   }

   public String key() {
      return this.playbackSessionId.value();
   }

   public PlaybackSessionId playbackSessionId() {
      return this.playbackSessionId;
   }

   public boolean isClosed() {
      return this.closed;
   }

   public long droppedSamples() {
      synchronized (this.lock) {
         return this.droppedSamples;
      }
   }

   public void beginConnection() {
      synchronized (this.lock) {
         this.anchorSet = false;
         this.consumerNeedsKeyframe = true;
         this.samples.clear();
         this.lock.notifyAll();
      }
   }

   public void publishConfig(byte[] avcC) {
      if (avcC != null && avcC.length > 0) {
         this.avcConfig = (byte[])avcC.clone();
      }
   }

   public void setAudioAnchor(long flvTimestampMillis, long fedMillis) {
      synchronized (this.lock) {
         if (!this.anchorSet) {
            this.anchorSet = true;
            this.anchorFlvMillis = flvTimestampMillis;
            this.anchorFedMillis = Math.max(0L, fedMillis);
         }
      }
   }

   public boolean hasAudioAnchor() {
      synchronized (this.lock) {
         return this.anchorSet;
      }
   }

   public void pushSample(byte[] avccSample, long dtsMillis, int compositionTimeMillis, boolean keyframe) {
      byte[] config = this.avcConfig;
      if (avccSample != null && avccSample.length != 0 && config != null && !this.closed) {
         synchronized (this.lock) {
            if (!this.anchorSet) {
               this.droppedSamples++;
            } else {
               long ptsMillis = this.anchorFedMillis + (dtsMillis + compositionTimeMillis - this.anchorFlvMillis);
               if (ptsMillis < 0L) {
                  this.droppedSamples++;
               } else {
                  if (this.samples.size() >= this.capacity) {
                     this.dropToNextKeyframe();
                  }

                  this.samples.addLast(new LiveVideoSampleBus.VideoSample(avccSample, ptsMillis * 1000000L, keyframe, config));
                  this.lock.notifyAll();
               }
            }
         }
      }
   }

   public LiveVideoSampleBus.VideoSample poll(long timeoutMillis) throws InterruptedException {
      long deadline = System.nanoTime() + Math.max(0L, timeoutMillis) * 1000000L;
      synchronized (this.lock) {
         while (true) {
            if (this.consumerNeedsKeyframe) {
               while (!this.samples.isEmpty() && !this.samples.peekFirst().keyframe()) {
                  this.samples.pollFirst();
                  this.droppedSamples++;
               }

               if (!this.samples.isEmpty()) {
                  this.consumerNeedsKeyframe = false;
               }
            }

            if (!this.samples.isEmpty() && !this.consumerNeedsKeyframe) {
               return this.samples.pollFirst();
            }

            if (this.closed) {
               return null;
            }

            long waitNanos = deadline - System.nanoTime();
            if (waitNanos <= 0L) {
               return null;
            }

            this.lock.wait(Math.max(1L, waitNanos / 1000000L));
         }
      }
   }

   public void close() {
      this.closed = true;
      synchronized (this.lock) {
         this.samples.clear();
         this.lock.notifyAll();
      }

      REGISTRY.remove(this.playbackSessionId, this);
   }

   private void dropToNextKeyframe() {
      if (!this.samples.isEmpty()) {
         this.samples.pollFirst();
         this.droppedSamples++;
      }

      while (!this.samples.isEmpty() && !this.samples.peekFirst().keyframe()) {
         this.samples.pollFirst();
         this.droppedSamples++;
      }

      if (this.samples.isEmpty()) {
         this.consumerNeedsKeyframe = true;
      }
   }

   public record VideoSample(byte[] data, long ptsNanos, boolean keyframe, byte[] avcConfig) {
   }
}
