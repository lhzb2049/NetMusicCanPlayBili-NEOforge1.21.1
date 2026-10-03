package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Optional;
import net.minecraft.client.Minecraft;

public final class MediaTimelineClock {
   private static final TimelineProperties.Clock PROPERTIES = TimelineProperties.clock();
   public static final long DEFAULT_HARD_SYNC_THRESHOLD_MILLIS = PROPERTIES.hardSyncThresholdMillis();
   public static final long DEFAULT_MAX_SMOOTH_CORRECTION_MILLIS = PROPERTIES.maxSmoothCorrectionMillis();
   public static final double DEFAULT_SMOOTH_CORRECTION_RATIO = PROPERTIES.smoothCorrectionRatio();
   private final Optional<PlaybackSessionId> playbackSessionId;
   private final long hardSyncThresholdMillis;
   private final long maxSmoothCorrectionMillis;
   private final double smoothCorrectionRatio;
   private long anchorNanos;
   private long anchorMillis;
   private long totalMillis;
   private long lastLocalMillis;
   private long pauseStartedNanos;
   private long lastObservedServerMillis;
   private long lastObservedGameTime = Long.MIN_VALUE;

   private MediaTimelineClock(
      Optional<PlaybackSessionId> playbackSessionId,
      long serverMillis,
      long totalMillis,
      long hardSyncThresholdMillis,
      long maxSmoothCorrectionMillis,
      double smoothCorrectionRatio
   ) {
      this.playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
      this.hardSyncThresholdMillis = Math.max(0L, hardSyncThresholdMillis);
      this.maxSmoothCorrectionMillis = Math.max(0L, maxSmoothCorrectionMillis);
      this.smoothCorrectionRatio = Math.max(0.0, Math.min(1.0, smoothCorrectionRatio));
      this.anchorNanos = System.nanoTime();
      this.totalMillis = Math.max(0L, totalMillis);
      this.anchorMillis = clamp(serverMillis, this.totalMillis);
      this.lastLocalMillis = this.anchorMillis;
      this.lastObservedServerMillis = this.anchorMillis;
   }

   public static MediaTimelineClock start(String sessionId, long serverMillis, long totalMillis) {
      return start(PlaybackSessionId.parse(sessionId), serverMillis, totalMillis);
   }

   static MediaTimelineClock start(Optional<PlaybackSessionId> playbackSessionId, long serverMillis, long totalMillis) {
      return new MediaTimelineClock(
         playbackSessionId,
         serverMillis,
         totalMillis,
         DEFAULT_HARD_SYNC_THRESHOLD_MILLIS,
         DEFAULT_MAX_SMOOTH_CORRECTION_MILLIS,
         DEFAULT_SMOOTH_CORRECTION_RATIO
      );
   }

   public static MediaTimelineClock start(
      String sessionId, long serverMillis, long totalMillis, long hardSyncThresholdMillis, long maxSmoothCorrectionMillis, double smoothCorrectionRatio
   ) {
      return new MediaTimelineClock(
         PlaybackSessionId.parse(sessionId), serverMillis, totalMillis, hardSyncThresholdMillis, maxSmoothCorrectionMillis, smoothCorrectionRatio
      );
   }

   public String sessionId() {
      return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
   }

   public Optional<PlaybackSessionId> playbackSessionId() {
      return this.playbackSessionId;
   }

   public synchronized boolean isForSession(String candidate) {
      return this.isForSession(PlaybackSessionId.parse(candidate));
   }

   synchronized boolean isForSession(Optional<PlaybackSessionId> candidate) {
      return this.playbackSessionId.equals(candidate != null ? candidate : Optional.empty());
   }

   public synchronized void observeServer(long serverMillis, long newTotalMillis) {
      this.totalMillis = Math.max(0L, newTotalMillis);
      long server = clamp(serverMillis, this.totalMillis);
      this.lastObservedServerMillis = server;
      if (!this.updatePausedState()) {
         long local = this.localMillisUnlocked();
         long drift = server - local;
         if (Math.abs(drift) >= this.hardSyncThresholdMillis) {
            this.anchorNanos = System.nanoTime();
            this.anchorMillis = server;
            this.lastLocalMillis = this.anchorMillis;
         } else {
            long correction = Math.round(drift * this.smoothCorrectionRatio);
            if (this.maxSmoothCorrectionMillis > 0L) {
               correction = Math.max(-this.maxSmoothCorrectionMillis, Math.min(this.maxSmoothCorrectionMillis, correction));
            }

            if (correction != 0L) {
               this.anchorMillis = clamp(this.anchorMillis + correction, this.totalMillis);
            }
         }
      }
   }

   public synchronized boolean observeServerOnce(long gameTime, long serverMillis, long newTotalMillis) {
      long total = Math.max(0L, newTotalMillis);
      long server = clamp(serverMillis, total);
      if (!isNewObservation(gameTime, server, total, this.lastObservedGameTime, this.lastObservedServerMillis, this.totalMillis)) {
         return false;
      } else {
         this.lastObservedGameTime = gameTime;
         this.observeServer(server, total);
         return true;
      }
   }

   static boolean isNewObservation(long gameTime, long serverMillis, long totalMillis, long lastGameTime, long lastServerMillis, long lastTotalMillis) {
      return gameTime != lastGameTime || serverMillis != lastServerMillis || totalMillis != lastTotalMillis;
   }

   public synchronized void reanchor(long mediaMillis, long newTotalMillis) {
      this.totalMillis = Math.max(0L, newTotalMillis);
      long media = clamp(mediaMillis, this.totalMillis);
      this.anchorNanos = System.nanoTime();
      this.anchorMillis = media;
      this.lastLocalMillis = media;
      this.pauseStartedNanos = 0L;
      this.lastObservedServerMillis = media;
      this.lastObservedGameTime = Long.MIN_VALUE;
   }

   public synchronized long mediaMillis() {
      return this.localMillis();
   }

   public synchronized long visualMillis() {
      return this.localMillis();
   }

   public synchronized long pacingMillis() {
      return this.localMillis();
   }

   public synchronized long serverMillis() {
      return this.lastObservedServerMillis;
   }

   public synchronized long totalMillis() {
      return this.totalMillis;
   }

   public synchronized MediaTimelineClock.TimelineSnapshot snapshot() {
      long local = this.localMillis();
      return new MediaTimelineClock.TimelineSnapshot(
         this.playbackSessionId, local, local, local, this.lastObservedServerMillis, this.totalMillis, local - this.lastObservedServerMillis
      );
   }

   public synchronized int mediaTick() {
      long millis = this.mediaMillis();
      return millis < 0L ? -1 : (int)Math.min(2147483647L, Math.max(0L, Math.round(millis / 50.0)));
   }

   public synchronized long relativeNanos(long absoluteStartMillis) {
      long millis = this.mediaMillis();
      return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1000000L;
   }

   private long localMillis() {
      if (this.updatePausedState()) {
         return this.lastLocalMillis;
      } else {
         long value = this.localMillisUnlocked();
         if (value < this.lastLocalMillis && this.lastLocalMillis - value < this.hardSyncThresholdMillis) {
            value = this.lastLocalMillis;
         }

         this.lastLocalMillis = value;
         return value;
      }
   }

   private long localMillisUnlocked() {
      long elapsedMillis = Math.max(0L, (System.nanoTime() - this.anchorNanos) / 1000000L);
      return clamp(this.anchorMillis + elapsedMillis, this.totalMillis);
   }

   private boolean updatePausedState() {
      Minecraft minecraft = Minecraft.getInstance();
      boolean paused = minecraft != null && minecraft.isPaused();
      long now = System.nanoTime();
      if (paused) {
         if (this.pauseStartedNanos == 0L) {
            this.pauseStartedNanos = now;
         }

         return true;
      } else {
         if (this.pauseStartedNanos != 0L) {
            this.anchorNanos = this.anchorNanos + Math.max(0L, now - this.pauseStartedNanos);
            this.pauseStartedNanos = 0L;
         }

         return false;
      }
   }

   public static long clamp(long millis, long totalMillis) {
      long value = Math.max(0L, millis);
      long total = Math.max(0L, totalMillis);
      return total > 0L ? Math.min(total, value) : value;
   }

   public record TimelineSnapshot(
      Optional<PlaybackSessionId> playbackSessionId,
      long mediaMillis,
      long visualMillis,
      long pacingMillis,
      long serverMillis,
      long totalMillis,
      long mediaDriftMillis
   ) {
      public static final MediaTimelineClock.TimelineSnapshot EMPTY = new MediaTimelineClock.TimelineSnapshot(Optional.empty(), -1L, -1L, -1L, -1L, 0L, 0L);

      public TimelineSnapshot(
         Optional<PlaybackSessionId> playbackSessionId,
         long mediaMillis,
         long visualMillis,
         long pacingMillis,
         long serverMillis,
         long totalMillis,
         long mediaDriftMillis
      ) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.playbackSessionId = playbackSessionId;
         this.mediaMillis = mediaMillis;
         this.visualMillis = visualMillis;
         this.pacingMillis = pacingMillis;
         this.serverMillis = serverMillis;
         this.totalMillis = totalMillis;
         this.mediaDriftMillis = mediaDriftMillis;
      }

      public TimelineSnapshot(
         String sessionId, long mediaMillis, long visualMillis, long pacingMillis, long serverMillis, long totalMillis, long mediaDriftMillis
      ) {
         this(PlaybackSessionId.parse(sessionId), mediaMillis, visualMillis, pacingMillis, serverMillis, totalMillis, mediaDriftMillis);
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }
   }
}
