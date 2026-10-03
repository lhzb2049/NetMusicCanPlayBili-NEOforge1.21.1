package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class Fmp4SeekRangeCache {
   private static final long TTL_MILLIS = TimeUnit.MINUTES.toMillis(10L);
   private static final int MAX_ENTRIES = 64;
   private static final int MAX_CACHED_BYTES = 1048576;
   private static final ConcurrentHashMap<Fmp4SeekRangeCache.Key, Fmp4SeekRangeCache.CachedRange> RANGES = new ConcurrentHashMap<>();

   private Fmp4SeekRangeCache() {
   }

   public static Fmp4SeekRangeCache.CachedRange get(URL url, long start, long endInclusive) {
      Fmp4SeekRangeCache.Key key = key(url, start, endInclusive);
      if (key == null) {
         return null;
      } else {
         Fmp4SeekRangeCache.CachedRange cached = RANGES.get(key);
         long now = System.currentTimeMillis();
         if (cached != null && cached.expiresAtMillis() >= now) {
            return cached;
         } else {
            if (cached != null) {
               RANGES.remove(key, cached);
            }

            return null;
         }
      }
   }

   public static void put(URL url, long start, long endInclusive, byte[] bytes, long totalLength, String sourceHost, String sourceUrl) {
      Fmp4SeekRangeCache.Key key = key(url, start, endInclusive);
      long rangeWidth = endInclusive - start;
      if (key != null && rangeWidth >= 0L && rangeWidth < 1048576L && bytes != null && bytes.length == rangeWidth + 1L) {
         long now = System.currentTimeMillis();
         cleanup(now);
         RANGES.put(
            key,
            new Fmp4SeekRangeCache.CachedRange(bytes, totalLength, sourceHost != null ? sourceHost : "", sourceUrl != null ? sourceUrl : "", now + TTL_MILLIS)
         );
         cleanup(now);
      }
   }

   static void clearForTests() {
      RANGES.clear();
   }

   private static void cleanup(long now) {
      RANGES.entrySet().removeIf(entryx -> ((Fmp4SeekRangeCache.CachedRange)entryx.getValue()).expiresAtMillis() < now);

      while (RANGES.size() > 64) {
         Fmp4SeekRangeCache.Key oldest = null;
         long oldestExpiry = Long.MAX_VALUE;

         for (Entry<Fmp4SeekRangeCache.Key, Fmp4SeekRangeCache.CachedRange> entry : RANGES.entrySet()) {
            if (entry.getValue().expiresAtMillis() < oldestExpiry) {
               oldest = entry.getKey();
               oldestExpiry = entry.getValue().expiresAtMillis();
            }
         }

         if (oldest == null) {
            return;
         }

         RANGES.remove(oldest);
      }
   }

   private static Fmp4SeekRangeCache.Key key(URL url, long start, long endInclusive) {
      if (url != null && start >= 0L && endInclusive >= start) {
         try {
            URI stripped = PlaybackSync.strip(url).toURI();
            String path = stripped.getRawPath();
            if (path != null && !path.isBlank()) {
               String query = stripped.getRawQuery();
               return new Fmp4SeekRangeCache.Key(path + (query != null ? "?" + query : ""), start, endInclusive);
            } else {
               return null;
            }
         } catch (Exception var8) {
            return null;
         }
      } else {
         return null;
      }
   }

   public record CachedRange(byte[] bytes, long totalLength, String sourceHost, String sourceUrl, long expiresAtMillis) {
      public CachedRange(byte[] bytes, long totalLength, String sourceHost, String sourceUrl, long expiresAtMillis) {
         bytes = Arrays.copyOf(bytes, bytes.length);
         this.bytes = bytes;
         this.totalLength = totalLength;
         this.sourceHost = sourceHost;
         this.sourceUrl = sourceUrl;
         this.expiresAtMillis = expiresAtMillis;
      }

      public byte[] bytes() {
         return Arrays.copyOf(this.bytes, this.bytes.length);
      }
   }

   private record Key(String resource, long start, long endInclusive) {
   }
}
