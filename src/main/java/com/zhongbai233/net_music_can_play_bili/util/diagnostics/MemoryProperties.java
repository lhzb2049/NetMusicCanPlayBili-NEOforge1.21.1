package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.concurrent.TimeUnit;

public final class MemoryProperties {
   static final String DIAGNOSTICS_ENABLED = "ncpb.memory.diagnostics";
   static final String PROTECTION_ENABLED = "ncpb.memory.protection";
   static final String REPORT_INTERVAL_MILLIS = "ncpb.memory.report_interval_ms";
   static final String SAMPLE_INTERVAL_MILLIS = "ncpb.memory.protection.sample_interval_ms";
   static final String OWNED_NATIVE_LIMIT_MIB = "ncpb.memory.protection.owned_native_mib";
   static final String GPU_PBO_LIMIT_MIB = "ncpb.memory.protection.gpu_pbo_mib";
   static final String FFMPEG_LIMIT_MIB = "ncpb.memory.protection.ffmpeg_mib";
   static final String D3D11_LOGICAL_LIMIT_MIB = "ncpb.memory.protection.d3d11_logical_mib";
   static final String D3D11_SURFACE_LIMIT = "ncpb.memory.protection.d3d11_surfaces";
   static final String CONSECUTIVE_SAMPLES = "ncpb.memory.protection.consecutive_samples";
   static final String COOLDOWN_MILLIS = "ncpb.memory.protection.cooldown_ms";
   static final String RECOVERY_RATIO = "ncpb.memory.protection.recovery_ratio";
   private static final long MIB = 1048576L;

   private MemoryProperties() {
   }

   public static MemoryProperties.Flags flags() {
      return new MemoryProperties.Flags(
         NcpbSystemProperties.booleanValue("ncpb.memory.diagnostics", false), NcpbSystemProperties.booleanValue("ncpb.memory.protection", true)
      );
   }

   public static long reportIntervalNanos() {
      return durationNanos("ncpb.memory.report_interval_ms", 5000L, 1000L);
   }

   public static MemoryProperties.Protection protection() {
      return new MemoryProperties.Protection(
         NcpbSystemProperties.booleanValue("ncpb.memory.protection", true),
         durationNanos("ncpb.memory.protection.sample_interval_ms", 2000L, 500L),
         mibToBytes(NcpbSystemProperties.longValue("ncpb.memory.protection.owned_native_mib", 512L)),
         mibToBytes(NcpbSystemProperties.longValue("ncpb.memory.protection.gpu_pbo_mib", 512L)),
         mibToBytes(NcpbSystemProperties.longValue("ncpb.memory.protection.ffmpeg_mib", 1024L)),
         mibToBytes(NcpbSystemProperties.longValue("ncpb.memory.protection.d3d11_logical_mib", 2048L)),
         NcpbSystemProperties.longValue("ncpb.memory.protection.d3d11_surfaces", 256L),
         NcpbSystemProperties.intValue("ncpb.memory.protection.consecutive_samples", 15),
         durationNanos("ncpb.memory.protection.cooldown_ms", 60000L, 5000L),
         NcpbSystemProperties.doubleValue("ncpb.memory.protection.recovery_ratio", 0.65)
      );
   }

   private static long durationNanos(String key, long fallbackMillis, long minimumMillis) {
      long millis = Math.max(minimumMillis, NcpbSystemProperties.longValue(key, fallbackMillis));
      return TimeUnit.MILLISECONDS.toNanos(millis);
   }

   private static long mibToBytes(long mib) {
      long safeMib = Math.max(0L, mib);
      return safeMib > 8796093022207L ? Long.MAX_VALUE : safeMib * 1048576L;
   }

   public record Flags(boolean diagnosticsEnabled, boolean protectionEnabled) {
   }

   public record Protection(
      boolean enabled,
      long sampleIntervalNanos,
      long ownedNativeBytes,
      long gpuPboBytes,
      long ffmpegBytes,
      long d3d11LogicalBytes,
      long d3d11Surfaces,
      int consecutiveSamples,
      long cooldownNanos,
      double recoveryRatio
   ) {
      public Protection(
         boolean enabled,
         long sampleIntervalNanos,
         long ownedNativeBytes,
         long gpuPboBytes,
         long ffmpegBytes,
         long d3d11LogicalBytes,
         long d3d11Surfaces,
         int consecutiveSamples,
         long cooldownNanos,
         double recoveryRatio
      ) {
         sampleIntervalNanos = Math.max(1L, sampleIntervalNanos);
         ownedNativeBytes = Math.max(0L, ownedNativeBytes);
         gpuPboBytes = Math.max(0L, gpuPboBytes);
         ffmpegBytes = Math.max(0L, ffmpegBytes);
         d3d11LogicalBytes = Math.max(0L, d3d11LogicalBytes);
         consecutiveSamples = Math.max(1, consecutiveSamples);
         cooldownNanos = Math.max(0L, cooldownNanos);
         recoveryRatio = Math.max(0.05, Math.min(0.95, recoveryRatio));
         this.enabled = enabled;
         this.sampleIntervalNanos = sampleIntervalNanos;
         this.ownedNativeBytes = ownedNativeBytes;
         this.gpuPboBytes = gpuPboBytes;
         this.ffmpegBytes = ffmpegBytes;
         this.d3d11LogicalBytes = d3d11LogicalBytes;
         this.d3d11Surfaces = d3d11Surfaces;
         this.consecutiveSamples = consecutiveSamples;
         this.cooldownNanos = cooldownNanos;
         this.recoveryRatio = recoveryRatio;
      }
   }
}
