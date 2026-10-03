package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class Fmp4NativeVideoProperties {
   static final String MAX_PENDING_FRAMES = "ncpb.video.native.max_pending_frames";
   static final String INIT_PROBE_BYTES = "ncpb.video.native.seek.init_probe_bytes";
   static final String MOOF_SCAN_BYTES = "ncpb.video.native.seek.moof_scan_bytes";
   static final String SEEK_MAX_ATTEMPTS = "ncpb.video.native.seek.max_attempts";
   static final String SEEK_PREROLL_BYTES = "ncpb.video.native.seek.preroll_bytes";
   static final String CLOSE_FRAGMENT_SECONDS = "ncpb.video.native.seek.close_fragment_seconds";
   static final String TARGET_EPSILON_SECONDS = "ncpb.video.native.seek.target_epsilon_seconds";
   static final String SEEK_LEAD_SECONDS = "ncpb.video.native.seek.lead_seconds";
   static final String RANGE_SEEK_ENABLED = "ncpb.video.native.seek.enabled";
   static final String RANGE_SEEK_AUTO_OFFSET_MILLIS = "ncpb.video.native.seek.auto_offset_ms";
   static final String LEGACY_RANGE_SEEK_AUTO_OFFSET_MILLIS = "bili.video.native.seek.auto_offset_ms";
   static final String FALLBACK_MAX_RESIDUAL_SECONDS = "ncpb.video.native.seek.fallback_max_residual_seconds";
   static final String NO_COPY_DROP_GUARD_MILLIS = "ncpb.video.native.seek.no_copy_drop_guard_ms";
   static final String LEGACY_NO_COPY_DROP_GUARD_MILLIS = "bili.video.native.seek.no_copy_drop_guard_ms";
   static final String STREAM_RECOVERY_ATTEMPTS = "ncpb.video.native.stream_recovery_attempts";
   static final String LEGACY_STREAM_RECOVERY_ATTEMPTS = "bili.video.native.stream_recovery_attempts";
   static final String REUSE_OUTPUT_BUFFERS = "ncpb.video.native.reuse_output_buffers";
   static final String DIRECT_NV12_BUFFERS = "ncpb.video.native.direct_nv12_buffers";
   static final String AV1_FIRST_FRAME_PROBE_TIMEOUT_MILLIS = "ncpb.video.native.av1_first_frame_probe_timeout_ms";
   static final String AV1_FIRST_FRAME_PROBE_MAX_PACKETS = "ncpb.video.native.av1_first_frame_probe_max_packets";
   static final String SEGMENT_BASE_CACHE_MAX_ENTRIES = "ncpb.video.segment_base_cache.max_entries";
   static final String LEGACY_SEGMENT_BASE_CACHE_MAX_ENTRIES = "bili.video.segment_base_cache.max_entries";

   private Fmp4NativeVideoProperties() {
   }

   static Fmp4NativeVideoProperties.Decoder decoder() {
      return new Fmp4NativeVideoProperties.Decoder(
         Math.max(1, NcpbSystemProperties.intValue("ncpb.video.native.max_pending_frames", 8)),
         Math.max(0, NcpbSystemProperties.intValue("ncpb.video.native.stream_recovery_attempts", "bili.video.native.stream_recovery_attempts", 3)),
         NcpbSystemProperties.booleanValue("ncpb.video.native.reuse_output_buffers", true),
         NcpbSystemProperties.booleanValue("ncpb.video.native.direct_nv12_buffers", true),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.video.segment_base_cache.max_entries", "bili.video.segment_base_cache.max_entries", 512))
      );
   }

   static Fmp4NativeVideoProperties.Seek seek() {
      return new Fmp4NativeVideoProperties.Seek(
         Math.max(1, NcpbSystemProperties.intValue("ncpb.video.native.seek.init_probe_bytes", 4194304)),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.video.native.seek.moof_scan_bytes", 8388608)),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.video.native.seek.max_attempts", 12)),
         Math.max(0L, NcpbSystemProperties.longValue("ncpb.video.native.seek.preroll_bytes", 1048576L)),
         Math.max(0.0, NcpbSystemProperties.doubleValue("ncpb.video.native.seek.close_fragment_seconds", 3.0)),
         Math.max(0.0, NcpbSystemProperties.doubleValue("ncpb.video.native.seek.target_epsilon_seconds", 0.25)),
         NcpbSystemProperties.doubleValue("ncpb.video.native.seek.lead_seconds", 0.0),
         NcpbSystemProperties.booleanValue("ncpb.video.native.seek.enabled", false),
         NcpbSystemProperties.longValue("ncpb.video.native.seek.auto_offset_ms", "bili.video.native.seek.auto_offset_ms", 5000L),
         NcpbSystemProperties.doubleValue("ncpb.video.native.seek.fallback_max_residual_seconds", -1.0),
         Math.max(0L, NcpbSystemProperties.longValue("ncpb.video.native.seek.no_copy_drop_guard_ms", "bili.video.native.seek.no_copy_drop_guard_ms", 1000L))
      );
   }

   static Fmp4NativeVideoProperties.FirstFrameProbe firstFrameProbe() {
      return new Fmp4NativeVideoProperties.FirstFrameProbe(
         clamp(NcpbSystemProperties.longValue("ncpb.video.native.av1_first_frame_probe_timeout_ms", 2000L), 250L, 10000L),
         clamp(NcpbSystemProperties.intValue("ncpb.video.native.av1_first_frame_probe_max_packets", 256), 1, 4096)
      );
   }

   private static long clamp(long value, long minimum, long maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   record Decoder(int maxPendingFrames, int streamRecoveryAttempts, boolean reuseOutputBuffers, boolean directNv12Buffers, int segmentBaseCacheMaxEntries) {
   }

   record FirstFrameProbe(long timeoutMillis, int maxPackets) {
   }

   record Seek(
      int initProbeBytes,
      int moofScanBytes,
      int maxAttempts,
      long prerollBytes,
      double closeFragmentSeconds,
      double targetEpsilonSeconds,
      double leadSeconds,
      boolean rangeEnabled,
      long autoOffsetMillis,
      double fallbackMaxResidualSeconds,
      long noCopyDropGuardMillis
   ) {
   }
}
