package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpRangeHeaders {
   private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", 2);

   private HttpRangeHeaders() {
   }

   public static Optional<Long> parseContentRangeTotal(String value) {
      HttpRangeHeaders.ContentRange range = parseContentRange(value);
      return range.hasKnownTotalLength() ? Optional.of(range.totalLength()) : Optional.empty();
   }

   public static HttpRangeHeaders.ContentRange parseContentRange(String value) {
      if (value == null) {
         return HttpRangeHeaders.ContentRange.unknown();
      } else {
         Matcher matcher = CONTENT_RANGE.matcher(value.trim());
         if (!matcher.matches()) {
            return HttpRangeHeaders.ContentRange.unknown();
         } else {
            try {
               long start = Long.parseLong(matcher.group(1));
               long endInclusive = Long.parseLong(matcher.group(2));
               long totalLength = "*".equals(matcher.group(3)) ? -1L : Long.parseLong(matcher.group(3));
               return endInclusive < start ? HttpRangeHeaders.ContentRange.unknown() : new HttpRangeHeaders.ContentRange(start, endInclusive, totalLength);
            } catch (NumberFormatException var8) {
               return HttpRangeHeaders.ContentRange.unknown();
            }
         }
      }
   }

   public static boolean isRedirectStatus(int statusCode) {
      return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
   }

   public static Builder rangeRequest(URL url, long rangeOffset, boolean probe, Duration timeout) {
      return HttpRequest.newBuilder(URI.create(url.toString()))
         .timeout(timeout)
         .GET()
         .header("Range", probe ? "bytes=0-0" : "bytes=%d-".formatted(Math.max(0L, rangeOffset)));
   }

   public static Builder boundedRangeRequest(URL url, long start, long endInclusive, Duration timeout) {
      if (start >= 0L && endInclusive >= start) {
         return HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout).GET().header("Range", "bytes=%d-%d".formatted(start, endInclusive));
      } else {
         throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
      }
   }

   public record ContentRange(long start, long endInclusive, long totalLength) {
      public static HttpRangeHeaders.ContentRange unknown() {
         return new HttpRangeHeaders.ContentRange(-1L, -1L, -1L);
      }

      public boolean isKnown() {
         return this.start >= 0L && this.endInclusive >= this.start;
      }

      public boolean hasKnownTotalLength() {
         return this.totalLength >= 0L;
      }
   }
}
