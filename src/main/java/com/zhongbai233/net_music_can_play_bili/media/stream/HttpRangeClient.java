package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliCdnSelector;
import com.zhongbai233.net_music_can_play_bili.bili.BiliRequestHeaders;
import com.zhongbai233.net_music_can_play_bili.bili.BiliWbiSigner;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

public final class HttpRangeClient {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_REDIRECTS = 5;
   private static final long REQUEST_TIMEOUT_SECONDS = Math.max(
      5L, NcpbSystemProperties.longValue("ncpb.media.http.request_timeout_seconds", "bili.media.http.request_timeout_seconds", 30L)
   );

   public HttpRangeClient.CdnResponse get(URL url) throws IOException {
      return this.send(url, null, null);
   }

   public HttpRangeClient.CdnResponse getRange(URL url, long start, long endInclusive) throws IOException {
      if (start >= 0L && endInclusive >= start) {
         return this.send(url, start, endInclusive);
      } else {
         throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
      }
   }

   public HttpRangeClient.CdnResponse getRangeDirect(URL url, long start, long endInclusive) throws IOException {
      if (start >= 0L && endInclusive >= start) {
         HttpRangeClient.CdnResponse response = this.send(url, start, endInclusive, 0);
         URL responseUrl = response.sourceUrl();
         BiliRequestHeaders.recordBiliCdnResponse(responseUrl, response.statusCode());
         if (isRetryableStatus(response.statusCode())) {
            CdnHealthTracker.recordFailure(
               responseUrl, response.statusCode() == 403 ? CdnHealthTracker.FailureKind.HTTP_FORBIDDEN : CdnHealthTracker.FailureKind.HTTP_RETRYABLE
            );
         }

         return response;
      } else {
         throw new IllegalArgumentException("invalid range: " + start + "-" + endInclusive);
      }
   }

   private HttpRangeClient.CdnResponse send(URL url, Long start, Long endInclusive) throws IOException {
      List<URL> candidates = CdnUrlFallbacks.candidates(url);
      IOException lastError = null;

      for (int i = 0; i < candidates.size(); i++) {
         URL candidate = candidates.get(i);

         try {
            long started = System.currentTimeMillis();
            HttpRangeClient.CdnResponse response = this.send(candidate, start, endInclusive, 0);
            URL responseUrl = response.sourceUrl();
            BiliRequestHeaders.recordBiliCdnResponse(responseUrl, response.statusCode());
            if (isRetryableStatus(response.statusCode())) {
               CdnHealthTracker.recordFailure(
                  responseUrl, response.statusCode() == 403 ? CdnHealthTracker.FailureKind.HTTP_FORBIDDEN : CdnHealthTracker.FailureKind.HTTP_RETRYABLE
               );
               if (i + 1 < candidates.size()) {
                  int status = response.statusCode();
                  response.close();
                  LOGGER.warn(
                     "CDN range request returned HTTP {}, retrying alternate host {} -> {} range={}-{}{}",
                     new Object[]{
                        status,
                        safeHost(candidate),
                        safeHost(candidates.get(i + 1)),
                        start != null ? start : 0L,
                        endInclusive != null ? endInclusive : -1L,
                        bili403Hint(status, candidate)
                     }
                  );
                  continue;
               }
            }

            if (response.statusCode() == 200 || response.statusCode() == 206) {
               CdnHealthTracker.recordSuccess(responseUrl, System.currentTimeMillis() - started, Math.max(0L, response.contentLength()));
               BiliCdnSelector.recordSuccess(responseUrl.toString());
            }

            return response;
         } catch (IOException var13) {
            lastError = var13;
            CdnHealthTracker.recordFailure(candidate, CdnHealthTracker.FailureKind.IO);
            if (i + 1 >= candidates.size()) {
               break;
            }

            LOGGER.warn(
               "CDN range request failed on host {}, retrying alternate host {} range={}-{}: {}",
               new Object[]{
                  safeHost(candidate),
                  safeHost(candidates.get(i + 1)),
                  start != null ? start : 0L,
                  endInclusive != null ? endInclusive : -1L,
                  var13.getMessage()
               }
            );
         }
      }

      throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
   }

   private HttpRangeClient.CdnResponse send(URL url, Long start, Long endInclusive, int redirects) throws IOException {
      URL requestUrl = PlaybackSync.strip(url);
      Duration timeout = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS);
      Builder builder = start != null && endInclusive != null
         ? HttpRangeHeaders.boundedRangeRequest(requestUrl, start, endInclusive, timeout)
         : HttpRequest.newBuilder(URI.create(requestUrl.toString())).timeout(timeout).GET();
      BiliRequestHeaders.applyBiliCdnHeaders(builder, requestUrl);
      HttpRequestCloseDiagnostics diagnostics = HttpRequestCloseDiagnostics.global();
      long operationId = diagnostics.begin(
         start != null ? "range" : "get", safeHost(requestUrl), start != null ? start : -1L, endInclusive != null ? endInclusive : -1L, System.nanoTime()
      );
      CancellableHttpTransport.Response response = CancellableHttpTransport.send(BiliWbiSigner.HTTP, builder.build(), diagnostics, operationId);
      if (HttpRangeHeaders.isRedirectStatus(response.statusCode())) {
         Optional<String> location = response.headers().firstValue("Location");
         response.body().close();
         if (location.isEmpty()) {
            throw new IOException("HTTP " + response.statusCode() + " redirect without Location");
         } else if (redirects >= 5) {
            throw new IOException("too many HTTP redirects");
         } else {
            URL redirected = URI.create(requestUrl.toString()).resolve(location.get()).toURL();
            return this.send(redirected, start, endInclusive, redirects + 1);
         }
      } else {
         long contentLength = parseLong(response.headers().firstValue("Content-Length"));
         HttpRangeHeaders.ContentRange contentRange = response.headers()
            .firstValue("Content-Range")
            .map(HttpRangeHeaders::parseContentRange)
            .orElseGet(HttpRangeHeaders.ContentRange::unknown);
         return new HttpRangeClient.CdnResponse(
            response.statusCode(),
            response.body(),
            requestUrl,
            contentLength,
            contentRange.start(),
            contentRange.endInclusive(),
            contentRange.totalLength(),
            start != null
         );
      }
   }

   private static boolean isRetryableStatus(int statusCode) {
      return statusCode == 403 || statusCode == 404 || statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
   }

   private static String safeHost(URL url) {
      return BiliRequestHeaders.safeHost(url);
   }

   private static String bili403Hint(int statusCode, URL url) {
      return statusCode == 403 && BiliRequestHeaders.isBiliHost(url)
         ? "; possible Bilibili DASH URL expiry or anti-hotlink check, refresh playurl if all CDN candidates fail"
         : "";
   }

   private static long parseLong(Optional<String> value) {
      if (value.isEmpty()) {
         return -1L;
      } else {
         try {
            return Long.parseLong(value.get().trim());
         } catch (NumberFormatException var2) {
            return -1L;
         }
      }
   }

   public record CdnResponse(
      int statusCode, InputStream body, URL sourceUrl, long contentLength, long rangeStart, long rangeEndInclusive, long totalLength, boolean rangeRequested
   ) implements AutoCloseable {
      public boolean isPartialContent() {
         return this.statusCode == 206;
      }

      @Override
      public void close() throws IOException {
         this.body.close();
      }
   }
}
