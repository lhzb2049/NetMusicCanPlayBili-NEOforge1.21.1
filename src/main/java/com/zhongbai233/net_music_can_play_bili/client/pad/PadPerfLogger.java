package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.PadRenderProperties;
import java.util.Locale;
import org.slf4j.Logger;

public final class PadPerfLogger {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final PadRenderProperties.Performance PROPERTIES = PadRenderProperties.performance();
   private static final boolean EXPLICIT_ENABLED = PROPERTIES.explicitEnabled();
   private static final long SLOW_WARN_NS = PROPERTIES.slowWarnMillis() * 1000000L;
   private static final long SLOW_WARN_COOLDOWN_NS = PROPERTIES.slowWarnCooldownMillis() * 1000000L;
   private static final long REPORT_INTERVAL_NS = 5000000000L;
   private static long nextReportNs = System.nanoTime() + 5000000000L;
   private static long nextSlowWarnNs;
   private static long sampleStepNs;
   private static long sampleStepMaxNs;
   private static int sampleSteps;
   private static int sampleJobs;
   private static int sampleJobStepsTotal;
   private static int sampleJobStepsMax;
   private static long bakeNs;
   private static long bakeMaxNs;
   private static int bakes;
   private static long guiNs;
   private static long guiMaxNs;
   private static int guiFrames;
   private static int cellCacheHits;
   private static int cellCacheMisses;

   private PadPerfLogger() {
   }

   public static void recordSampleStep(long ns) {
      maybeWarnSlow("sampleStep", ns);
      if (enabled()) {
         sampleStepNs += ns;
         sampleStepMaxNs = Math.max(sampleStepMaxNs, ns);
         sampleSteps++;
         maybeReport();
      }
   }

   public static void recordSampleJobComplete(int steps) {
      if (enabled()) {
         sampleJobs++;
         sampleJobStepsTotal += steps;
         sampleJobStepsMax = Math.max(sampleJobStepsMax, steps);
         maybeReport();
      }
   }

   public static void recordCellCacheHit() {
      if (enabled()) {
         cellCacheHits++;
      }
   }

   public static void recordCellCacheMiss() {
      if (enabled()) {
         cellCacheMisses++;
      }
   }

   public static void recordMapBake(long ns) {
      maybeWarnSlow("mapBake", ns);
      if (enabled()) {
         bakeNs += ns;
         bakeMaxNs = Math.max(bakeMaxNs, ns);
         bakes++;
         maybeReport();
      }
   }

   public static void recordGuiFrame(long ns) {
      maybeWarnSlow("guiFrame", ns);
      if (enabled()) {
         guiNs += ns;
         guiMaxNs = Math.max(guiMaxNs, ns);
         guiFrames++;
         maybeReport();
      }
   }

   private static void maybeReport() {
      long now = System.nanoTime();
      if (now >= nextReportNs) {
         nextReportNs = now + 5000000000L;
         if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(
               "Pad性能峰值: sampleStepMax={}ms jobStepsMax={} mapBakeMax={}ms guiFrameMax={}ms",
               new Object[]{millis(sampleStepMaxNs), sampleJobStepsMax, millis(bakeMaxNs), millis(guiMaxNs)}
            );
         }

         LOGGER.debug(
            "Pad性能: sampleSteps={} avg={}ms max={}ms jobs={} jobStepsAvg={} jobStepsMax={} cacheHit={} cacheMiss={} hitRate={} mapBakes={} avg={}ms max={}ms guiFrames={} avg={}ms max={}ms",
            new Object[]{
               sampleSteps,
               millis(avg(sampleStepNs, sampleSteps)),
               millis(sampleStepMaxNs),
               sampleJobs,
               avgInt(sampleJobStepsTotal, sampleJobs),
               sampleJobStepsMax,
               cellCacheHits,
               cellCacheMisses,
               hitRate(),
               bakes,
               millis(avg(bakeNs, bakes)),
               millis(bakeMaxNs),
               guiFrames,
               millis(avg(guiNs, guiFrames)),
               millis(guiMaxNs)
            }
         );
         sampleStepNs = 0L;
         sampleStepMaxNs = 0L;
         sampleSteps = 0;
         sampleJobs = 0;
         sampleJobStepsTotal = 0;
         sampleJobStepsMax = 0;
         bakeNs = 0L;
         bakeMaxNs = 0L;
         bakes = 0;
         guiNs = 0L;
         guiMaxNs = 0L;
         guiFrames = 0;
         cellCacheHits = 0;
         cellCacheMisses = 0;
      }
   }

   private static void maybeWarnSlow(String phase, long ns) {
      if (SLOW_WARN_NS > 0L && ns >= SLOW_WARN_NS && LOGGER.isDebugEnabled()) {
         long now = System.nanoTime();
         if (now >= nextSlowWarnNs) {
            nextSlowWarnNs = now + SLOW_WARN_COOLDOWN_NS;
            LOGGER.debug("Pad慢操作: phase={} cost={}ms", phase, millis(ns));
         }
      }
   }

   private static boolean enabled() {
      return EXPLICIT_ENABLED || LOGGER.isDebugEnabled() || LOGGER.isTraceEnabled();
   }

   private static long avg(long total, int count) {
      return count <= 0 ? 0L : total / count;
   }

   private static int avgInt(int total, int count) {
      return count <= 0 ? 0 : Math.round((float)total / count);
   }

   private static String millis(long ns) {
      return String.format(Locale.ROOT, "%.3f", ns / 1000000.0);
   }

   private static String hitRate() {
      int total = cellCacheHits + cellCacheMisses;
      return total <= 0 ? "0.0%" : String.format(Locale.ROOT, "%.1f%%", cellCacheHits * 100.0 / total);
   }
}
