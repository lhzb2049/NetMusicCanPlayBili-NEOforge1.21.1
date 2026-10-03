package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

public final class BiliCdnSelector {
   private static final CdnProperties.Selector PROPERTIES = CdnProperties.selector();
   private static final boolean ENABLED = PROPERTIES.enabled();
   private static final boolean RACE_ENABLED = PROPERTIES.raceEnabled();
   private static final int RACE_BYTES = PROPERTIES.raceBytes();
   private static final long RACE_TIMEOUT_MILLIS = PROPERTIES.raceTimeoutMillis();
   private static final int MAX_RACE_CANDIDATES = PROPERTIES.maxRaceCandidates();
   private static final long MIN_PERSIST_INTERVAL_MILLIS = PROPERTIES.minPersistIntervalMillis();
   private static final long BACKGROUND_RACE_INTERVAL_MILLIS = PROPERTIES.backgroundRaceIntervalMillis();
   private static final Object PREFERENCE_LOCK = new Object();
   private static final AtomicLong LAST_BACKGROUND_RACE_AT = new AtomicLong();
   private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_RACE_CANDIDATES, NetMusicThreadFactory.daemon("bili-cdn-selector"));
   private static volatile String preferredHost = PROPERTIES.preferredHost();
   private static volatile long preferredUpdatedAtMillis;

   private BiliCdnSelector() {
   }

   public static void load(JsonObject root) {
      if (root != null && root.has("cdnPreference") && !root.get("cdnPreference").isJsonNull()) {
         try {
            JsonObject cdn = root.getAsJsonObject("cdnPreference");
            JsonElement host = cdn.get("preferredHost");
            if (host != null && !host.isJsonNull()) {
               preferredHost = normalizeHost(host.getAsString());
            }

            JsonElement updated = cdn.get("updatedAtMillis");
            if (updated != null && !updated.isJsonNull()) {
               preferredUpdatedAtMillis = Math.max(0L, updated.getAsLong());
            }

            if (!preferredHost.isBlank()) {
               logger().info("已加载 B站 CDN 优选域名: {}", preferredHost);
            }
         } catch (Exception var4) {
            logger().warn("加载 B站 CDN 优选配置失败", var4);
         }
      }
   }

   public static void save(JsonObject root) {
      if (root != null) {
         JsonObject cdn = new JsonObject();
         cdn.addProperty("preferredHost", preferredHost != null ? preferredHost : "");
         cdn.addProperty("updatedAtMillis", preferredUpdatedAtMillis);
         root.add("cdnPreference", cdn);
      }
   }

   public static String selectPreferred(List<String> candidates) {
      List<String> ordered = orderCandidates(candidates);
      if (ordered.isEmpty()) {
         return "";
      } else if (!ENABLED || !RACE_ENABLED || ordered.size() <= 1) {
         return ordered.get(0);
      } else if (hasPreferredHost(ordered)) {
         refreshPreferredInBackground(ordered);
         return ordered.get(0);
      } else {
         String raced = raceFirstReadable(ordered);
         return raced != null && !raced.isBlank() ? raced : ordered.get(0);
      }
   }

   public static List<String> orderCandidates(List<String> candidates) {
      if (candidates != null && !candidates.isEmpty()) {
         Set<String> clean = new LinkedHashSet<>();

         for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
               clean.add(candidate);
            }
         }

         List<String> ordered = new ArrayList<>(clean);
         if (!ENABLED) {
            return ordered;
         } else {
            String preferred = preferredHost;
            if (!preferred.isBlank()) {
               ordered.sort(Comparator.<String>comparingInt(url -> preferred.equals(hostOf(url)) ? 0 : 1).thenComparingDouble(url -> {
                  try {
                     return CdnHealthTracker.score(URI.create(url).toURL());
                  } catch (Exception var2) {
                     return 0.0;
                  }
               }));
               return ordered;
            } else {
               ordered.sort(Comparator.<String>comparingDouble(url -> {
                  try {
                     return CdnHealthTracker.score(URI.create(url).toURL());
                  } catch (Exception var2) {
                     return 0.0;
                  }
               }).thenComparing(url -> hostOf(url)));
               return ordered;
            }
         }
      } else {
         return List.of();
      }
   }

   public static boolean hasPreferredHost() {
      return ENABLED && preferredHost != null && !preferredHost.isBlank();
   }

   public static boolean hasPreferredHost(List<?> candidates) {
      if (hasPreferredHost() && candidates != null && !candidates.isEmpty()) {
         String preferred = preferredHost;

         for (Object candidate : candidates) {
            String host = "";
            if (candidate instanceof URL url) {
               host = normalizeHost(url.getHost());
            } else if (candidate instanceof String value) {
               host = hostOf(value);
            }

            if (preferred.equals(host)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static void recordSuccess(String url) {
      if (ENABLED) {
         String host = hostOf(url);
         if (!host.isBlank() && !host.equals(preferredHost)) {
            synchronized (PREFERENCE_LOCK) {
               if (!host.equals(preferredHost)) {
                  long now = System.currentTimeMillis();
                  if (!preferredHost.isBlank() && now - preferredUpdatedAtMillis < MIN_PERSIST_INTERVAL_MILLIS) {
                     logger().debug("B站 CDN 优选更新已防抖: current={} candidate={}", preferredHost, host);
                  } else {
                     preferredHost = host;
                     preferredUpdatedAtMillis = now;
                     BiliConfig.save();
                     logger().info("B站 CDN 优选更新: host={}", host);
                  }
               }
            }
         }
      }
   }

   private static String raceFirstReadable(List<String> candidates) {
      String winner = raceFirstReadable(candidates, true);
      return winner != null ? winner : "";
   }

   private static String raceFirstReadable(List<String> candidates, boolean persistWinner) {
      String winner = raceFirstReadableCandidate(candidates);
      if (persistWinner && winner != null && !winner.isBlank()) {
         recordSuccess(winner);
      }

      return winner;
   }

   private static void refreshPreferredInBackground(List<String> candidates) {
      long now = System.currentTimeMillis();
      long previous = LAST_BACKGROUND_RACE_AT.get();
      if (now - previous >= BACKGROUND_RACE_INTERVAL_MILLIS && LAST_BACKGROUND_RACE_AT.compareAndSet(previous, now)) {
         List<String> snapshot = List.copyOf(candidates);
         Thread thread = NetMusicThreadFactory.daemonThread("bili-cdn-background-race", () -> {
            String winner = raceFirstReadable(snapshot, false);
            if (winner != null && !winner.isBlank()) {
               logger().debug("B站 CDN 后台优选完成: host={}", hostOf(winner));
            }
         });
         thread.start();
      }
   }

   private static String raceFirstReadableCandidate(List<String> candidates) {
      int count = Math.min(MAX_RACE_CANDIDATES, candidates.size());
      CompletableFuture<String> first = new CompletableFuture<>();
      List<Future<?>> tasks = new ArrayList<>(count);

      for (int i = 0; i < count; i++) {
         String url = candidates.get(i);
         tasks.add(EXECUTOR.submit(() -> {
            if (!first.isDone()) {
               if (probeReadable(url)) {
                  first.complete(url);
               }
            }
         }, EXECUTOR));
      }

      String var12;
      try {
         return first.get(RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      } catch (Exception var9) {
         var12 = "";
      } finally {
         tasks.forEach(task -> task.cancel(true));
      }

      return var12;
   }

   private static boolean probeReadable(String url) {
      long started = System.currentTimeMillis();

      try {
         URL parsed = URI.create(url).toURL();
         URL requestUrl = PlaybackSync.strip(parsed);

         boolean var9;
         try (HttpRangeClient.CdnResponse response = new HttpRangeClient().getRangeDirect(requestUrl, 0L, RACE_BYTES - 1L)) {
            InputStream body = response.body();
            int status = response.statusCode();
            BiliRequestHeaders.recordBiliCdnResponse(parsed, status);
            if (status != 200 && status != 206) {
               CdnHealthTracker.recordFailure(parsed, status == 403 ? CdnHealthTracker.FailureKind.HTTP_FORBIDDEN : CdnHealthTracker.FailureKind.HTTP_RETRYABLE);
               return false;
            }

            byte[] bytes = body.readNBytes(1);
            if (bytes.length > 0) {
               CdnHealthTracker.recordSuccess(
                  parsed, System.currentTimeMillis() - started, response.contentLength() >= 0L ? response.contentLength() : bytes.length
               );
               return true;
            }

            CdnHealthTracker.recordFailure(parsed, CdnHealthTracker.FailureKind.EMPTY);
            var9 = false;
         }

         return var9;
      } catch (Exception var13) {
         try {
            CdnHealthTracker.recordFailure(URI.create(url).toURL(), CdnHealthTracker.FailureKind.IO);
         } catch (Exception var10) {
         }

         return false;
      }
   }

   private static String hostOf(String url) {
      try {
         return normalizeHost(URI.create(url).getHost());
      } catch (Exception var2) {
         return "";
      }
   }

   private static String normalizeHost(String host) {
      return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
   }

   private static Logger logger() {
      return BiliCdnSelector.LoggerHolder.INSTANCE;
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }
}
