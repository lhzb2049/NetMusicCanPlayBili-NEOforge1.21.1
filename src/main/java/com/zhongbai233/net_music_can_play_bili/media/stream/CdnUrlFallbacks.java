package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CdnUrlFallbacks {
   private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(30L);
   private static final int MAX_GROUPS = CdnProperties.fallback().maxGroups();
   private static final ConcurrentHashMap<String, CdnUrlFallbacks.UrlGroup> GROUPS_BY_URL = new ConcurrentHashMap<>();

   private CdnUrlFallbacks() {
   }

   public static void registerAlternates(List<String> urls) {
      if (urls != null && urls.size() > 1) {
         Set<String> clean = new LinkedHashSet<>();

         for (String url : urls) {
            String key = key(url);
            if (key != null) {
               clean.add(key);
            }
         }

         if (clean.size() > 1) {
            long now = System.currentTimeMillis();
            cleanup(now);
            CdnUrlFallbacks.UrlGroup group = new CdnUrlFallbacks.UrlGroup(List.copyOf(clean), now + TTL_MILLIS);

            for (String urlx : clean) {
               GROUPS_BY_URL.put(urlx, group);
            }

            cleanup(now);
         }
      }
   }

   public static List<URL> candidates(URL primary) {
      String primaryKey = key(primary);
      if (primaryKey == null) {
         return List.of(primary);
      } else {
         CdnUrlFallbacks.UrlGroup group = GROUPS_BY_URL.get(primaryKey);
         if (group != null && group.expiresAtMillis() >= System.currentTimeMillis()) {
            List<URL> result = new ArrayList<>(group.urls().size());
            addUrl(result, primaryKey);

            for (String url : group.urls()) {
               if (!url.equals(primaryKey)) {
                  addUrl(result, url);
               }
            }

            List<URL> available = result.stream()
               .filter(candidate -> !CdnHealthTracker.isCoolingDown(candidate))
               .sorted(Comparator.comparingDouble(CdnHealthTracker::score))
               .toList();
            return !available.isEmpty()
               ? available
               : result.stream()
                  .min(Comparator.comparingLong(CdnHealthTracker::cooldownUntilMillis).thenComparingDouble(CdnHealthTracker::score))
                  .map(List::of)
                  .orElseGet(() -> List.of(primary));
         } else {
            GROUPS_BY_URL.remove(primaryKey, group);
            return List.of(primary);
         }
      }
   }

   private static void addUrl(List<URL> result, String value) {
      try {
         result.add(URI.create(value).toURL());
      } catch (Exception var3) {
      }
   }

   private static void cleanup(long now) {
      GROUPS_BY_URL.forEach((url, groupx) -> {
         if (groupx.expiresAtMillis() < now) {
            GROUPS_BY_URL.remove(url, groupx);
         }
      });

      while (GROUPS_BY_URL.size() > MAX_GROUPS) {
         CdnUrlFallbacks.UrlGroup oldest = null;

         for (CdnUrlFallbacks.UrlGroup group : GROUPS_BY_URL.values()) {
            if (oldest == null || group.expiresAtMillis() < oldest.expiresAtMillis()) {
               oldest = group;
            }
         }

         if (oldest == null) {
            return;
         }

         CdnUrlFallbacks.UrlGroup groupToRemove = oldest;
         GROUPS_BY_URL.entrySet().removeIf(entry -> entry.getValue() == groupToRemove);
      }
   }

   private static String key(URL url) {
      return url == null ? null : key(url.toString());
   }

   private static String key(String url) {
      if (url != null && !url.isBlank()) {
         try {
            return PlaybackSync.strip(url).toString();
         } catch (Exception var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private record UrlGroup(List<String> urls, long expiresAtMillis) {
   }
}
