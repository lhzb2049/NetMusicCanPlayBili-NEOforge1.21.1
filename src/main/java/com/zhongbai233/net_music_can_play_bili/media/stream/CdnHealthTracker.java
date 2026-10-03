package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class CdnHealthTracker {
   private static final boolean ENABLED = NcpbSystemProperties.booleanValue("ncpb.bili.cdn_health.enabled", true);
   private static final long STALE_AFTER_MILLIS = Math.max(
      10000L, NcpbSystemProperties.longValue("ncpb.bili.cdn_health.stale_after_ms", "bili.cdn_health.stale_after_ms", 600000L)
   );
   private static final long FORBIDDEN_COOLDOWN_MILLIS = Math.max(
      1000L, NcpbSystemProperties.longValue("ncpb.bili.cdn_health.forbidden_cooldown_ms", "bili.cdn_health.forbidden_cooldown_ms", 60000L)
   );
   private static final double SUCCESS_DECAY = clamp01(
      NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.success_decay", "bili.cdn_health.success_decay", 0.72)
   );
   private static final double FAILURE_PENALTY = Math.max(
      0.0, NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.failure_penalty", "bili.cdn_health.failure_penalty", 4.0)
   );
   private static final double EMPTY_PENALTY = Math.max(
      0.0, NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.empty_penalty", "bili.cdn_health.empty_penalty", 6.0)
   );
   private static final double SHORT_READ_PENALTY = Math.max(
      0.0, NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.short_read_penalty", "bili.cdn_health.short_read_penalty", 3.0)
   );
   private static final double HTTP_RETRYABLE_PENALTY = Math.max(
      0.0, NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.http_retryable_penalty", "bili.cdn_health.http_retryable_penalty", 5.0)
   );
   private static final double MAX_PENALTY = Math.max(
      1.0, NcpbSystemProperties.doubleValue("ncpb.bili.cdn_health.max_penalty", "bili.cdn_health.max_penalty", 64.0)
   );
   private static final double UNKNOWN_HOST_SCORE = 2.5;
   private static final ConcurrentHashMap<String, CdnHealthTracker.HostHealth> HEALTH_BY_HOST = new ConcurrentHashMap<>();

   private CdnHealthTracker() {
   }

   public static void recordSuccess(URL url, long elapsedMillis, long bytes) {
      if (ENABLED) {
         String host = host(url);
         if (host != null) {
            long now = System.currentTimeMillis();
            HEALTH_BY_HOST.compute(
               host,
               (ignored, existing) -> {
                  CdnHealthTracker.HostHealth health = existing != null ? existing.fresh(now) : CdnHealthTracker.HostHealth.initial(now);
                  double latency = elapsedMillis > 0L ? elapsedMillis : health.latencyMillis();
                  double ewmaLatency = health.latencyMillis() <= 0.0 ? latency : health.latencyMillis() * 0.8 + latency * 0.2;
                  double penalty = Math.max(0.0, health.penalty() * SUCCESS_DECAY - 0.25);
                  return new CdnHealthTracker.HostHealth(
                     penalty,
                     ewmaLatency,
                     health.successes() + 1L,
                     health.failures(),
                     now,
                     health.cooldownUntilMillis() <= now ? 0L : health.cooldownUntilMillis()
                  );
               }
            );
         }
      }
   }

   public static void recordFailure(URL url, CdnHealthTracker.FailureKind kind) {
      if (ENABLED) {
         String host = host(url);
         if (host != null) {
            double delta = switch (kind) {
               case IO -> FAILURE_PENALTY;
               case EMPTY -> EMPTY_PENALTY;
               case SHORT_READ -> SHORT_READ_PENALTY;
               case HTTP_FORBIDDEN -> HTTP_RETRYABLE_PENALTY * 2.0;
               case HTTP_RETRYABLE -> HTTP_RETRYABLE_PENALTY;
            };
            long now = System.currentTimeMillis();
            HEALTH_BY_HOST.compute(host, (ignored, existing) -> {
               CdnHealthTracker.HostHealth health = existing != null ? existing.fresh(now) : CdnHealthTracker.HostHealth.initial(now);
               double penalty = Math.min(MAX_PENALTY, health.penalty() + delta);
               long cooldownUntil = kind == CdnHealthTracker.FailureKind.HTTP_FORBIDDEN ? now + FORBIDDEN_COOLDOWN_MILLIS : health.cooldownUntilMillis();
               return new CdnHealthTracker.HostHealth(penalty, health.latencyMillis(), health.successes(), health.failures() + 1L, now, cooldownUntil);
            });
         }
      }
   }

   public static boolean isCoolingDown(URL url) {
      if (!ENABLED) {
         return false;
      } else {
         String host = host(url);
         CdnHealthTracker.HostHealth health = host != null ? HEALTH_BY_HOST.get(host) : null;
         return health != null && health.cooldownUntilMillis() > System.currentTimeMillis();
      }
   }

   public static long cooldownUntilMillis(URL url) {
      if (!ENABLED) {
         return 0L;
      } else {
         String host = host(url);
         CdnHealthTracker.HostHealth health = host != null ? HEALTH_BY_HOST.get(host) : null;
         return health != null ? health.cooldownUntilMillis() : 0L;
      }
   }

   public static double score(URL url) {
      if (!ENABLED) {
         return 0.0;
      } else {
         String host = host(url);
         if (host == null) {
            return 0.0;
         } else {
            CdnHealthTracker.HostHealth health = HEALTH_BY_HOST.get(host);
            if (health == null) {
               return 2.5;
            } else {
               long age = Math.max(0L, System.currentTimeMillis() - health.updatedAtMillis());
               return ageAdjustedScore(health.penalty(), health.latencyMillis(), age, STALE_AFTER_MILLIS);
            }
         }
      }
   }

   static double ageAdjustedScore(double penalty, double latencyMillis, long ageMillis, long staleAfterMillis) {
      long safeStaleAfter = Math.max(1L, staleAfterMillis);
      double freshness = ageMillis >= safeStaleAfter ? 0.0 : 1.0 - (double)Math.max(0L, ageMillis) / safeStaleAfter;
      double latencyPenalty = latencyMillis > 0.0 ? Math.min(8.0, latencyMillis / 750.0) : 0.0;
      double observedScore = Math.max(0.0, penalty) + latencyPenalty;
      return 2.5 + (observedScore - 2.5) * freshness;
   }

   public static void clear() {
      HEALTH_BY_HOST.clear();
   }

   private static String host(URL url) {
      String host = url != null ? url.getHost() : null;
      return host != null && !host.isBlank() ? host.toLowerCase(Locale.ROOT) : null;
   }

   private static double clamp01(double value) {
      return !Double.isFinite(value) ? 0.72 : Math.max(0.0, Math.min(1.0, value));
   }

   public static enum FailureKind {
      IO,
      EMPTY,
      SHORT_READ,
      HTTP_FORBIDDEN,
      HTTP_RETRYABLE;
   }

   private record HostHealth(double penalty, double latencyMillis, long successes, long failures, long updatedAtMillis, long cooldownUntilMillis) {
      static CdnHealthTracker.HostHealth initial(long now) {
         return new CdnHealthTracker.HostHealth(0.0, 0.0, 0L, 0L, now, 0L);
      }

      CdnHealthTracker.HostHealth fresh(long now) {
         long age = Math.max(0L, now - this.updatedAtMillis);
         if (age <= 0L) {
            return this;
         } else {
            double staleRatio = Math.min(1.0, (double)age / CdnHealthTracker.STALE_AFTER_MILLIS);
            return new CdnHealthTracker.HostHealth(
               this.penalty * (1.0 - staleRatio * 0.5), this.latencyMillis, this.successes, this.failures, this.updatedAtMillis, this.cooldownUntilMillis
            );
         }
      }
   }
}
