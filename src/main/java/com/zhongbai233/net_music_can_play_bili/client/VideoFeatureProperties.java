package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class VideoFeatureProperties {
   static final String ADVANCED_FEATURES = "ncpb.video.advanced_features";
   static final String BENCH_FEATURES = "ncpb.video.enable_bench_features";
   static final String NATIVE_HWACCEL = "ncpb.video.native.hwaccel";
   static final String REAL_BENCH = "ncpb.video.real_bench";
   static final String REAL_BENCH_MANAGED = "ncpb.video.real_bench.managed";
   static final String REAL_MEDIA_LIFECYCLE = "ncpb.video.real_media_lifecycle";
   static final String REAL_MEDIA_LIFECYCLE_ROUNDS = "ncpb.video.real_media_lifecycle.rounds";
   static final String REAL_MEDIA_LIFECYCLE_CYCLE_TIMEOUT_TICKS = "ncpb.video.real_media_lifecycle.cycle_timeout_ticks";
   static final String REAL_BENCH_VIDEO_ID = "ncpb.video.real_bench.bv";
   static final String REAL_MEDIA_LIFECYCLE_QUALITY = "ncpb.video.real_media_lifecycle.quality";
   static final String FFMPEG_DECODER = "ncpb.video.ffmpeg.decoder";

   private VideoFeatureProperties() {
   }

   static boolean advancedFeaturesEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.advanced_features", false);
   }

   static boolean benchFeaturesEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.enable_bench_features", false);
   }

   public static boolean realBenchEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.real_bench", false);
   }

   public static VideoFeatureProperties.RealMediaLifecycle realMediaLifecycle() {
      return new VideoFeatureProperties.RealMediaLifecycle(
         NcpbSystemProperties.booleanValue("ncpb.video.real_media_lifecycle", false),
         NcpbSystemProperties.intValue("ncpb.video.real_media_lifecycle.rounds", 100),
         NcpbSystemProperties.intValue("ncpb.video.real_media_lifecycle.cycle_timeout_ticks", 600),
         NcpbSystemProperties.stringValue("ncpb.video.real_bench.bv", "BV1GJ411x7h7"),
         NcpbSystemProperties.intValue("ncpb.video.real_media_lifecycle.quality", 16)
      );
   }

   static boolean realBenchManaged() {
      return NcpbSystemProperties.booleanValue("ncpb.video.real_bench.managed", false);
   }

   static boolean booleanValue(String key, boolean fallback) {
      return NcpbSystemProperties.booleanValue(key, fallback);
   }

   static int intValue(String key, int fallback) {
      return NcpbSystemProperties.intValue(key, fallback);
   }

   static long longValue(String key, long fallback) {
      return NcpbSystemProperties.longValue(key, fallback);
   }

   static String stringValue(String key, String fallback) {
      return NcpbSystemProperties.stringValue(key, fallback);
   }

   static String nativeHwaccel() {
      return NcpbSystemProperties.stringValue("ncpb.video.native.hwaccel", "auto");
   }

   static String ffmpegDecoder() {
      return NcpbSystemProperties.stringValue("ncpb.video.ffmpeg.decoder", "");
   }

   public record RealMediaLifecycle(boolean enabled, int rounds, int cycleTimeoutTicks, String videoId, int quality) {
      public RealMediaLifecycle(boolean enabled, int rounds, int cycleTimeoutTicks, String videoId, int quality) {
         rounds = Math.clamp((long)rounds, 1, 10000);
         cycleTimeoutTicks = Math.clamp((long)cycleTimeoutTicks, 100, 72000);
         videoId = videoId != null && !videoId.isBlank() ? videoId.trim() : "BV1GJ411x7h7";
         quality = Math.max(1, quality);
         this.enabled = enabled;
         this.rounds = rounds;
         this.cycleTimeoutTicks = cycleTimeoutTicks;
         this.videoId = videoId;
         this.quality = quality;
      }
   }
}
