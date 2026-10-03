package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

public final class VideoPerformanceFallbackPolicy {
   public static final VideoPerformanceFallbackPolicy.Config DEFAULT = new VideoPerformanceFallbackPolicy.Config(5000L, 0.8, 3, 250L);

   private VideoPerformanceFallbackPolicy() {
   }

   public static VideoPerformanceFallbackPolicy.Decision decide(
      VideoPerformanceFallbackPolicy.Snapshot snapshot, boolean av1Candidate, boolean h264CandidateAvailable, boolean fallbackAlreadyLocked
   ) {
      return decide(snapshot, av1Candidate, h264CandidateAvailable, fallbackAlreadyLocked, DEFAULT);
   }

   static VideoPerformanceFallbackPolicy.Decision decide(
      VideoPerformanceFallbackPolicy.Snapshot snapshot,
      boolean av1Candidate,
      boolean h264CandidateAvailable,
      boolean fallbackAlreadyLocked,
      VideoPerformanceFallbackPolicy.Config config
   ) {
      if (snapshot != null && config != null && av1Candidate && !fallbackAlreadyLocked) {
         if (snapshot.observationMillis() < config.warmupMillis()) {
            return VideoPerformanceFallbackPolicy.Decision.WARMING_UP;
         } else {
            double minimumFps = Math.max(1, snapshot.targetFps()) * config.minimumFpsRatio();
            boolean lowFps = snapshot.actualDecodeFps() + 1.0E-9 < minimumFps;
            long driftThreshold = Math.max(config.minimumDriftGrowthMillis(), Math.round(8000.0 / Math.max(1, snapshot.targetFps())));
            boolean growingDrift = snapshot.consecutiveDriftGrowthSamples() >= config.driftGrowthSamples()
               && snapshot.syncDriftGrowthMillis() >= driftThreshold;
            if (!lowFps && !growingDrift) {
               return VideoPerformanceFallbackPolicy.Decision.KEEP;
            } else if (!h264CandidateAvailable) {
               return VideoPerformanceFallbackPolicy.Decision.KEEP_NO_H264;
            } else if (lowFps) {
               return VideoPerformanceFallbackPolicy.Decision.FALLBACK_LOW_FPS;
            } else {
               return growingDrift ? VideoPerformanceFallbackPolicy.Decision.FALLBACK_GROWING_DRIFT : VideoPerformanceFallbackPolicy.Decision.KEEP;
            }
         }
      } else {
         return VideoPerformanceFallbackPolicy.Decision.KEEP;
      }
   }

   public record Config(long warmupMillis, double minimumFpsRatio, int driftGrowthSamples, long minimumDriftGrowthMillis) {
      public Config(long warmupMillis, double minimumFpsRatio, int driftGrowthSamples, long minimumDriftGrowthMillis) {
         warmupMillis = Math.max(1L, warmupMillis);
         minimumFpsRatio = Math.max(0.05, Math.min(1.0, minimumFpsRatio));
         driftGrowthSamples = Math.max(1, driftGrowthSamples);
         minimumDriftGrowthMillis = Math.max(1L, minimumDriftGrowthMillis);
         this.warmupMillis = warmupMillis;
         this.minimumFpsRatio = minimumFpsRatio;
         this.driftGrowthSamples = driftGrowthSamples;
         this.minimumDriftGrowthMillis = minimumDriftGrowthMillis;
      }
   }

   public static enum Decision {
      WARMING_UP(false, "warming-up"),
      KEEP(false, "within-budget"),
      KEEP_NO_H264(false, "no-h264-candidate"),
      FALLBACK_LOW_FPS(true, "performance-low-fps"),
      FALLBACK_GROWING_DRIFT(true, "performance-growing-av-drift");

      private final boolean fallback;
      private final String reason;

      private Decision(boolean fallback, String reason) {
         this.fallback = fallback;
         this.reason = reason;
      }

      public boolean shouldFallback() {
         return this.fallback;
      }

      public String reason() {
         return this.reason;
      }
   }

   public record Snapshot(
      long observationMillis,
      int targetFps,
      long decodedFrames,
      double actualDecodeFps,
      double averageDecodeMillis,
      double p95DecodeMillis,
      long starvationCount,
      long droppedFrames,
      double droppedFrameRatio,
      long latestSyncDriftMillis,
      long syncDriftGrowthMillis,
      int consecutiveDriftGrowthSamples,
      String backend,
      long nativeFrameBytesPeak,
      long nativeSurfacePeak
   ) {
      public Snapshot(
         long observationMillis,
         int targetFps,
         long decodedFrames,
         double actualDecodeFps,
         double averageDecodeMillis,
         double p95DecodeMillis,
         long starvationCount,
         long droppedFrames,
         double droppedFrameRatio,
         long latestSyncDriftMillis,
         long syncDriftGrowthMillis,
         int consecutiveDriftGrowthSamples,
         String backend,
         long nativeFrameBytesPeak,
         long nativeSurfacePeak
      ) {
         observationMillis = Math.max(0L, observationMillis);
         targetFps = Math.max(1, targetFps);
         decodedFrames = Math.max(0L, decodedFrames);
         actualDecodeFps = Math.max(0.0, actualDecodeFps);
         averageDecodeMillis = Math.max(0.0, averageDecodeMillis);
         p95DecodeMillis = Math.max(0.0, p95DecodeMillis);
         starvationCount = Math.max(0L, starvationCount);
         droppedFrames = Math.max(0L, droppedFrames);
         droppedFrameRatio = Math.max(0.0, Math.min(1.0, droppedFrameRatio));
         syncDriftGrowthMillis = Math.max(0L, syncDriftGrowthMillis);
         consecutiveDriftGrowthSamples = Math.max(0, consecutiveDriftGrowthSamples);
         backend = backend != null && !backend.isBlank() ? backend : "unknown";
         nativeFrameBytesPeak = Math.max(0L, nativeFrameBytesPeak);
         nativeSurfacePeak = Math.max(0L, nativeSurfacePeak);
         this.observationMillis = observationMillis;
         this.targetFps = targetFps;
         this.decodedFrames = decodedFrames;
         this.actualDecodeFps = actualDecodeFps;
         this.averageDecodeMillis = averageDecodeMillis;
         this.p95DecodeMillis = p95DecodeMillis;
         this.starvationCount = starvationCount;
         this.droppedFrames = droppedFrames;
         this.droppedFrameRatio = droppedFrameRatio;
         this.latestSyncDriftMillis = latestSyncDriftMillis;
         this.syncDriftGrowthMillis = syncDriftGrowthMillis;
         this.consecutiveDriftGrowthSamples = consecutiveDriftGrowthSamples;
         this.backend = backend;
         this.nativeFrameBytesPeak = nativeFrameBytesPeak;
         this.nativeSurfacePeak = nativeSurfacePeak;
      }
   }
}
