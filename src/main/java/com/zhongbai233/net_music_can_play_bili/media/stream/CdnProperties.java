package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Locale;

public final class CdnProperties {
   static final String SELECTOR_ENABLED = "ncpb.bili.cdn_selector.enabled";
   static final String SELECTOR_RACE = "ncpb.bili.cdn_selector.race";
   static final String RACE_BYTES = "ncpb.bili.cdn_selector.race_bytes";
   static final String LEGACY_RACE_BYTES = "ncpb.ncpb.bili.cdn_selector.race_bytes";
   static final String RACE_TIMEOUT_MILLIS = "ncpb.bili.cdn_selector.race_timeout_ms";
   static final String LEGACY_RACE_TIMEOUT_MILLIS = "ncpb.ncpb.bili.cdn_selector.race_timeout_ms";
   static final String MAX_RACE_CANDIDATES = "ncpb.bili.cdn_selector.max_race_candidates";
   static final String MIN_PERSIST_INTERVAL_MILLIS = "ncpb.bili.cdn_selector.min_persist_interval_ms";
   static final String BACKGROUND_RACE_INTERVAL_MILLIS = "ncpb.bili.cdn_selector.background_race_interval_ms";
   static final String PREFERRED_HOST = "ncpb.bili.cdn.preferred_host";
   static final String FALLBACK_MAX_GROUPS = "ncpb.bili.cdn_fallback.max_groups";

   private CdnProperties() {
   }

   public static CdnProperties.Selector selector() {
      return new CdnProperties.Selector(
         NcpbSystemProperties.booleanValue("ncpb.bili.cdn_selector.enabled", true),
         NcpbSystemProperties.booleanValue("ncpb.bili.cdn_selector.race", false),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.bili.cdn_selector.race_bytes", "ncpb.ncpb.bili.cdn_selector.race_bytes", 2048)),
         Math.max(250L, NcpbSystemProperties.longValue("ncpb.bili.cdn_selector.race_timeout_ms", "ncpb.ncpb.bili.cdn_selector.race_timeout_ms", 2500L)),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.bili.cdn_selector.max_race_candidates", 4)),
         Math.max(0L, NcpbSystemProperties.longValue("ncpb.bili.cdn_selector.min_persist_interval_ms", 5000L)),
         Math.max(1000L, NcpbSystemProperties.longValue("ncpb.bili.cdn_selector.background_race_interval_ms", 60000L)),
         normalizeHost(NcpbSystemProperties.stringValue("ncpb.bili.cdn.preferred_host", ""))
      );
   }

   public static CdnProperties.Fallback fallback() {
      return new CdnProperties.Fallback(Math.max(1, NcpbSystemProperties.intValue("ncpb.bili.cdn_fallback.max_groups", 512)));
   }

   private static String normalizeHost(String host) {
      return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
   }

   public record Fallback(int maxGroups) {
   }

   public record Selector(
      boolean enabled,
      boolean raceEnabled,
      int raceBytes,
      long raceTimeoutMillis,
      int maxRaceCandidates,
      long minPersistIntervalMillis,
      long backgroundRaceIntervalMillis,
      String preferredHost
   ) {
   }
}
