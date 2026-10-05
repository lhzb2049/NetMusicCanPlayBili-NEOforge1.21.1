package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class VideoPipelineProperties {
   static final String NETWORK_ERROR_PLACEHOLDER = "ncpb.video.pipeline.network_error_placeholder";
   static final String PIXEL_MODE = "ncpb.video.pixel.mode";
   static final String FAST_NATIVE_UPLOAD = "ncpb.video.fast_native_upload";
   static final String NV12_PBO = "ncpb.video.nv12.pbo";
   static final String NV12_UV_RG8 = "ncpb.video.nv12.uv_rg8";
   static final String YUV_MATRIX = "ncpb.video.yuv.matrix";
   static final String YUV_SHADER_DEBUG = "ncpb.video.yuv.shader_debug";
   static final String YUV_NO_DEPTH_WRITE = "ncpb.video.yuv.no_depth_write";
   static final String AUDIO_LATENCY_COMPENSATION_MILLIS = "ncpb.video.pipeline.audio_latency_compensation_ms";
   static final String LEGACY_AUDIO_LATENCY_COMPENSATION_MILLIS = "bili.video.pipeline.audio_latency_compensation_ms";
   static final String CHASE_WINDOW_MILLIS = "ncpb.video.pipeline.chase_window_ms";
   static final String SLOWDOWN_WINDOW_MILLIS = "ncpb.video.pipeline.slowdown_window_ms";
   static final String RUNTIME_LAG_RESTART_MILLIS = "ncpb.video.pipeline.runtime_lag_restart_ms";
   static final String RUNTIME_LAG_CONFIRM_MILLIS = "ncpb.video.pipeline.runtime_lag_confirm_ms";
   static final String RUNTIME_LAG_RESTART_COOLDOWN_MILLIS = "ncpb.video.pipeline.runtime_lag_restart_cooldown_ms";
   static final String DECODER_STABILIZATION_MILLIS = "ncpb.video.pipeline.decoder_stabilization_ms";
   static final String DECODER_RESTART_CLOSE_TIMEOUT_MILLIS = "ncpb.video.pipeline.decoder_restart_close_timeout_ms";
   static final String FIRST_FRAME_TIMEOUT_MILLIS = "ncpb.video.pipeline.first_frame_timeout_ms";
   static final String FIRST_FRAME_RECOVERY_ATTEMPTS = "ncpb.video.pipeline.first_frame_recovery_attempts";
   static final String OFFSCREEN_PAUSE_DECODE = "ncpb.video.offscreen.pause_decode";
   static final String OFFSCREEN_GRACE_MILLIS = "ncpb.video.offscreen.grace_ms";
   static final String OFFSCREEN_RESUME_RESTART_LAG_MILLIS = "ncpb.video.offscreen.resume_restart_lag_ms";
   static final String LEGACY_OFFSCREEN_RESUME_RESTART_LAG_MILLIS = "bili.video.offscreen.resume_restart_lag_ms";
   static final String OFFSCREEN_PREWARM_DOT_THRESHOLD = "ncpb.video.offscreen.prewarm_dot_threshold";
   static final String MAX_SOURCE_WIDTH = "ncpb.video.pipeline.max_source_width";
   static final String MAX_SOURCE_HEIGHT = "ncpb.video.pipeline.max_source_height";
   static final String IRIS_WARNING_VIEW_DEPTH_OFFSET = "ncpb.video.pipeline.iris_warning_placeholder_view_depth_offset";
   static final String IRIS_WARNING_LOCAL_DEPTH_OFFSET = "ncpb.video.pipeline.iris_warning_placeholder_local_depth_offset";
   static final String QUEUE_CAPACITY = "ncpb.video.pipeline.queue_capacity";
   static final String STARTUP_DROP_LAG_MILLIS = "ncpb.video.pipeline.startup_drop_lag_ms";
   static final String MAX_DECODE_LEAD_MILLIS = "ncpb.video.pipeline.max_decode_lead_ms";
   static final String UPLOAD_PUMP_WARN_MILLIS = "ncpb.video.pipeline.upload_pump_warn_ms";
   static final String EARLY_TOLERANCE_MILLIS = "ncpb.video.pipeline.early_tolerance_ms";
   static final String MAX_VISIBLE_LAG_MILLIS = "ncpb.video.pipeline.max_visible_lag_ms";
   static final String STARTUP_PREBUFFER_FRAMES = "ncpb.video.pipeline.startup_prebuffer_frames";
   static final String STARTUP_PREBUFFER_MAX_WAIT_MILLIS = "ncpb.video.pipeline.startup_prebuffer_max_wait_ms";
   static final String LOADING_PLACEHOLDER = "ncpb.video.pipeline.loading_placeholder";
   static final String WORLD_ANCHORED = "ncpb.video.world_anchor";
   static final String WORLD_ANCHOR_DISTANCE = "ncpb.video.world_anchor.distance";
   static final String AUDIO_SYNC_RANGE = "ncpb.video.turntable.sync_range";
   static final String RENDER_BACKEND = "ncpb.video.render.backend";
   static final String YUV_UPLOAD_PLANES = "ncpb.video.yuv.upload_planes";
   static final String PROJECTOR_TELEPORT_RESET_DISTANCE = "ncpb.video.projector.teleport_reset_distance";
   static final String YUV_IMMEDIATE_STAGE = "ncpb.video.yuv.immediate_stage";
   static final String YUV_IMMEDIATE_COORDS = "ncpb.video.yuv.immediate_coords";
   static final String YUV_IMMEDIATE_POSE = "ncpb.video.yuv.immediate_pose";
   static final String YUV_DEBUG_LOG = "ncpb.video.yuv.debug_log";
   static final String VIEW_DOT_THRESHOLD = "ncpb.video.render.view_dot_threshold";
   static final String VIEW_OCCLUSION_CHECK = "ncpb.video.render.occlusion_check";
   static final String VIEW_OCCLUSION_CACHE_MILLIS = "ncpb.video.render.occlusion_cache_ms";
   static final String LEGACY_VIEW_OCCLUSION_CACHE_MILLIS = "bili.video.render.occlusion_cache_ms";
   static final String VIEW_SAMPLE_EDGE_SCALE = "ncpb.video.render.visibility_sample_edge_scale";
   static final String MAX_RENDER_DISTANCE = "ncpb.video.max_render_distance";

   private VideoPipelineProperties() {
   }

   static boolean networkErrorPlaceholderEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.pipeline.network_error_placeholder", true);
   }

   static VideoPipelineProperties.Upload upload() {
      return new VideoPipelineProperties.Upload(
         NcpbSystemProperties.stringValue("ncpb.video.pixel.mode", "normal"),
         NcpbSystemProperties.booleanValue("ncpb.video.fast_native_upload", true),
         NcpbSystemProperties.booleanValue("ncpb.video.nv12.pbo", true),
         nv12UvRg8Enabled()
      );
   }

   public static boolean nv12UvRg8Enabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.nv12.uv_rg8", true);
   }

   static VideoPipelineProperties.Yuv yuv() {
      return new VideoPipelineProperties.Yuv(
         NcpbSystemProperties.stringValue("ncpb.video.yuv.matrix", "bt709_limited").toLowerCase(Locale.ROOT),
         NcpbSystemProperties.stringValue("ncpb.video.yuv.shader_debug", "").toUpperCase(Locale.ROOT),
         NcpbSystemProperties.booleanValue("ncpb.video.yuv.no_depth_write", false)
      );
   }

   static VideoPipelineProperties.Timing timing() {
      return new VideoPipelineProperties.Timing(
         NcpbSystemProperties.longValue("ncpb.video.pipeline.audio_latency_compensation_ms", "bili.video.pipeline.audio_latency_compensation_ms", 0L),
         chaseWindowMillis(),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.slowdown_window_ms", 2500L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.runtime_lag_restart_ms", 1500L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.runtime_lag_confirm_ms", 1500L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.runtime_lag_restart_cooldown_ms", 5000L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.decoder_stabilization_ms", 8000L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.decoder_restart_close_timeout_ms", 3000L),
         NcpbSystemProperties.longValue("ncpb.video.pipeline.first_frame_timeout_ms", 20000L),
         NcpbSystemProperties.intValue("ncpb.video.pipeline.first_frame_recovery_attempts", 2)
      );
   }

   static VideoPipelineProperties.Offscreen offscreen() {
      return new VideoPipelineProperties.Offscreen(
         NcpbSystemProperties.booleanValue("ncpb.video.offscreen.pause_decode", true),
         NcpbSystemProperties.longValue("ncpb.video.offscreen.grace_ms", 500L),
         NcpbSystemProperties.longValue("ncpb.video.offscreen.resume_restart_lag_ms", "bili.video.offscreen.resume_restart_lag_ms", 1500L),
         NcpbSystemProperties.doubleValue("ncpb.video.offscreen.prewarm_dot_threshold", -0.2)
      );
   }

   static VideoPipelineProperties.Presentation presentation() {
      return new VideoPipelineProperties.Presentation(
         NcpbSystemProperties.intValue("ncpb.video.pipeline.max_source_width", 4096),
         NcpbSystemProperties.intValue("ncpb.video.pipeline.max_source_height", 2304),
         NcpbSystemProperties.doubleValue("ncpb.video.pipeline.iris_warning_placeholder_view_depth_offset", 0.0),
         NcpbSystemProperties.floatValue("ncpb.video.pipeline.iris_warning_placeholder_local_depth_offset", -0.01F),
         NcpbSystemProperties.intValue("ncpb.video.pipeline.queue_capacity", 3)
      );
   }

   static long startupDropLagMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.startup_drop_lag_ms", 750L);
   }

   static long maxDecodeLeadMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.max_decode_lead_ms", 250L);
   }

   static long uploadPumpWarnMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.upload_pump_warn_ms", 1000L);
   }

   static long earlyToleranceMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.early_tolerance_ms", 12L);
   }

   static long maxVisibleLagMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.max_visible_lag_ms", 250L);
   }

   static int startupPrebufferFrames() {
      return NcpbSystemProperties.intValue("ncpb.video.pipeline.startup_prebuffer_frames", 2);
   }

   static long startupPrebufferMaxWaitMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.startup_prebuffer_max_wait_ms", 250L);
   }

   static boolean loadingPlaceholderEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.pipeline.loading_placeholder", true);
   }

   static long chaseWindowMillis() {
      return NcpbSystemProperties.longValue("ncpb.video.pipeline.chase_window_ms", 10000L);
   }

   static VideoPipelineProperties.Billboard billboard() {
      return new VideoPipelineProperties.Billboard(
         NcpbSystemProperties.booleanValue("ncpb.video.world_anchor", true),
         NcpbSystemProperties.doubleValue("ncpb.video.world_anchor.distance", 6.0),
         NcpbSystemProperties.doubleValue("ncpb.video.turntable.sync_range", 96.0),
         NcpbSystemProperties.stringValue("ncpb.video.render.backend", "nv12").toLowerCase(Locale.ROOT),
         NcpbSystemProperties.booleanValue("ncpb.video.yuv.upload_planes", true),
         NcpbSystemProperties.doubleValue("ncpb.video.projector.teleport_reset_distance", 16.0)
      );
   }

   static VideoPipelineProperties.YuvImmediate yuvImmediate() {
      return new VideoPipelineProperties.YuvImmediate(
         NcpbSystemProperties.stringValue("ncpb.video.yuv.immediate_stage", "after_level").toLowerCase(Locale.ROOT),
         NcpbSystemProperties.stringValue("ncpb.video.yuv.immediate_coords", "camera_relative").toLowerCase(Locale.ROOT),
         NcpbSystemProperties.stringValue("ncpb.video.yuv.immediate_pose", "identity").toLowerCase(Locale.ROOT),
         NcpbSystemProperties.booleanValue("ncpb.video.yuv.debug_log", false)
      );
   }

   static VideoPipelineProperties.Visibility visibility() {
      long cacheMillis = Math.max(0L, NcpbSystemProperties.longValue("ncpb.video.render.occlusion_cache_ms", "bili.video.render.occlusion_cache_ms", 150L));
      return new VideoPipelineProperties.Visibility(
         NcpbSystemProperties.doubleValue("ncpb.video.render.view_dot_threshold", 0.12),
         NcpbSystemProperties.booleanValue("ncpb.video.render.occlusion_check", true),
         TimeUnit.MILLISECONDS.toNanos(cacheMillis),
         NcpbSystemProperties.doubleValue("ncpb.video.render.visibility_sample_edge_scale", 0.86),
         NcpbSystemProperties.doubleValue("ncpb.video.max_render_distance", 64.0)
      );
   }

   private static double squaredDistance(double value) {
      double maxRoot = Math.sqrt(Double.MAX_VALUE);
      return value >= maxRoot ? Double.MAX_VALUE : value * value;
   }

   record Billboard(
      boolean worldAnchored,
      double worldAnchorDistance,
      double audioSyncRange,
      String renderBackend,
      boolean yuvUploadPlanes,
      double projectorTeleportResetDistance
   ) {
      Billboard(
         boolean worldAnchored,
         double worldAnchorDistance,
         double audioSyncRange,
         String renderBackend,
         boolean yuvUploadPlanes,
         double projectorTeleportResetDistance
      ) {
         worldAnchorDistance = Math.max(0.0, worldAnchorDistance);
         audioSyncRange = Math.max(0.0, audioSyncRange);
         projectorTeleportResetDistance = Math.max(0.0, projectorTeleportResetDistance);
         this.worldAnchored = worldAnchored;
         this.worldAnchorDistance = worldAnchorDistance;
         this.audioSyncRange = audioSyncRange;
         this.renderBackend = renderBackend;
         this.yuvUploadPlanes = yuvUploadPlanes;
         this.projectorTeleportResetDistance = projectorTeleportResetDistance;
      }

      double audioSyncRangeSqr() {
         return VideoPipelineProperties.squaredDistance(this.audioSyncRange);
      }

      double projectorTeleportResetDistanceSqr() {
         return VideoPipelineProperties.squaredDistance(this.projectorTeleportResetDistance);
      }
   }

   record Offscreen(boolean pauseDecode, long graceMillis, long resumeRestartLagMillis, double prewarmDotThreshold) {
   }

   record Presentation(int maxSourceWidth, int maxSourceHeight, double irisWarningViewDepthOffset, float irisWarningLocalDepthOffset, int queueCapacity) {
   }

   record Timing(
      long audioLatencyCompensationMillis,
      long chaseWindowMillis,
      long slowdownWindowMillis,
      long runtimeLagRestartMillis,
      long runtimeLagConfirmMillis,
      long runtimeLagRestartCooldownMillis,
      long decoderStabilizationMillis,
      long decoderRestartCloseTimeoutMillis,
      long firstFrameTimeoutMillis,
      int firstFrameRecoveryAttempts
   ) {
   }

   record Upload(String pixelMode, boolean fastNativeUploadEnabled, boolean nv12PboEnabled, boolean nv12UvRg8Enabled) {
   }

   record Visibility(double viewDotThreshold, boolean occlusionCheck, long occlusionCacheNanos, double sampleEdgeScale, double maxRenderDistance) {
      Visibility(double viewDotThreshold, boolean occlusionCheck, long occlusionCacheNanos, double sampleEdgeScale, double maxRenderDistance) {
         viewDotThreshold = Math.max(-1.0, Math.min(1.0, viewDotThreshold));
         occlusionCacheNanos = Math.max(0L, occlusionCacheNanos);
         sampleEdgeScale = Math.max(0.0, Math.min(1.0, sampleEdgeScale));
         maxRenderDistance = Math.max(0.0, maxRenderDistance);
         this.viewDotThreshold = viewDotThreshold;
         this.occlusionCheck = occlusionCheck;
         this.occlusionCacheNanos = occlusionCacheNanos;
         this.sampleEdgeScale = sampleEdgeScale;
         this.maxRenderDistance = maxRenderDistance;
      }

      double maxRenderDistanceSqr() {
         return VideoPipelineProperties.squaredDistance(this.maxRenderDistance);
      }
   }

   record Yuv(String matrix, String shaderDebug, boolean depthWriteDisabled) {
   }

   record YuvImmediate(String stage, String coordinates, String pose, boolean debugLog) {
   }
}
