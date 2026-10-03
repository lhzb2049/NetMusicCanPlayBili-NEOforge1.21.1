package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliCdnSelector;
import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

public final class ChunkPrefetchInputStream extends InputStream {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int COPY_BUFFER_SIZE = NcpbSystemProperties.intValue(
      "ncpb.media.prefetch.copy_buffer_bytes", "bili.media.prefetch.copy_buffer_bytes", 262144
   );
   public static final int DEFAULT_CHUNK_SIZE = NcpbSystemProperties.intValue("ncpb.media.prefetch.chunk_bytes", "bili.media.prefetch.chunk_bytes", 4194304);
   public static final int DEFAULT_LOW_WATER = NcpbSystemProperties.intValue(
      "ncpb.media.prefetch.low_water_bytes", "bili.media.prefetch.low_water_bytes", 8388608
   );
   public static final int DEFAULT_HIGH_WATER = NcpbSystemProperties.intValue(
      "ncpb.media.prefetch.high_water_bytes", "bili.media.prefetch.high_water_bytes", 33554432
   );
   private static final long STARTUP_PREBUFFER_BYTES = Math.max(
      0L, NcpbSystemProperties.longValue("ncpb.media.prefetch.startup_bytes", "bili.media.prefetch.startup_bytes", 786432L)
   );
   private static final long SEEK_STARTUP_PREBUFFER_BYTES = Math.max(
      0L, NcpbSystemProperties.longValue("ncpb.media.prefetch.seek_startup_bytes", "bili.media.prefetch.seek_startup_bytes", 65536L)
   );
   private static final long STARTUP_PREBUFFER_MAX_WAIT_MILLIS = Math.max(
      0L, NcpbSystemProperties.longValue("ncpb.media.prefetch.startup_max_wait_ms", "bili.media.prefetch.startup_max_wait_ms", 1500L)
   );
   private static final int PER_HOST_ATTEMPTS = Math.max(
      1, NcpbSystemProperties.intValue("ncpb.media.prefetch.per_host_attempts", "bili.media.prefetch.per_host_attempts", 2)
   );
   private static final long RETRY_BACKOFF_MILLIS = Math.max(
      0L, NcpbSystemProperties.longValue("ncpb.media.prefetch.retry_backoff_ms", "bili.media.prefetch.retry_backoff_ms", 350L)
   );
   private final URL url;
   private final HttpRangeClient client;
   private final TempFileByteSpool spool;
   private final Thread downloader;
   private final int chunkSize;
   private final int lowWater;
   private final int highWater;
   private final Object demandLock = new Object();
   private final AtomicReference<InputStream> activeBody = new AtomicReference<>();
   private final AtomicBoolean closeStarted = new AtomicBoolean();
   private final AtomicReference<URL> activeRequestUrl = new AtomicReference<>();
   private final AtomicReference<URL> startupFailoverUrl = new AtomicReference<>();
   private final long startupPrebufferBytes;
   private final long startupPrebufferMaxWaitMillis;
   private volatile boolean closed;
   private volatile long readPosition;
   private volatile boolean startupPrebufferDone;
   private volatile ChunkPrefetchInputStream.Mode mode = ChunkPrefetchInputStream.Mode.UNKNOWN;
   private volatile long totalLength = -1L;
   private final long startByteOffset;
   private boolean backpressurePaused;

   public ChunkPrefetchInputStream(URL url) throws IOException {
      this(url, 0L);
   }

   public ChunkPrefetchInputStream(URL url, long startByteOffset) throws IOException {
      this(url, new HttpRangeClient(), DEFAULT_CHUNK_SIZE, DEFAULT_LOW_WATER, DEFAULT_HIGH_WATER, Math.max(0L, startByteOffset));
   }

   public ChunkPrefetchInputStream(URL url, HttpRangeClient client, int chunkSize, int lowWater, int highWater) throws IOException {
      this(url, client, chunkSize, lowWater, highWater, 0L);
   }

   public ChunkPrefetchInputStream(URL url, HttpRangeClient client, int chunkSize, int lowWater, int highWater, long startByteOffset) throws IOException {
      this(
         url,
         client,
         chunkSize,
         lowWater,
         highWater,
         startByteOffset,
         startByteOffset > 0L ? SEEK_STARTUP_PREBUFFER_BYTES : STARTUP_PREBUFFER_BYTES,
         STARTUP_PREBUFFER_MAX_WAIT_MILLIS
      );
   }

   ChunkPrefetchInputStream(
      URL url,
      HttpRangeClient client,
      int chunkSize,
      int lowWater,
      int highWater,
      long startByteOffset,
      long startupPrebufferBytes,
      long startupPrebufferMaxWaitMillis
   ) throws IOException {
      this.url = url;
      this.client = client;
      this.chunkSize = Math.max(1048576, chunkSize);
      this.lowWater = Math.max(this.chunkSize, lowWater);
      this.highWater = Math.max(this.lowWater, highWater);
      this.startByteOffset = Math.max(0L, startByteOffset);
      this.startupPrebufferBytes = Math.max(0L, startupPrebufferBytes);
      this.startupPrebufferMaxWaitMillis = Math.max(0L, startupPrebufferMaxWaitMillis);
      this.spool = new TempFileByteSpool("http-prefetch-");
      this.downloader = NetMusicThreadFactory.daemonThread("HttpPrefetch", this::downloadLoop);
      this.downloader.start();
   }

   @Override
   public int read() throws IOException {
      byte[] one = new byte[1];
      int n = this.read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
   }

   @Override
   public int read(byte[] b, int off, int len) throws IOException {
      if (b == null) {
         throw new NullPointerException("buffer");
      } else if (off < 0 || len < 0 || len > b.length - off) {
         throw new IndexOutOfBoundsException();
      } else if (len == 0) {
         return 0;
      } else if (this.closed) {
         return -1;
      } else {
         this.awaitStartupPrebufferIfNeeded();
         if (this.closed) {
            return -1;
         } else {
            int n = this.spool.read(this.readPosition, b, off, len);
            if (n > 0) {
               this.readPosition += n;
               this.notifyDemand();
            }

            return n;
         }
      }
   }

   @Override
   public void close() throws IOException {
      if (this.closeStarted.compareAndSet(false, true)) {
         this.closed = true;
         this.notifyDemand();
         this.downloader.interrupt();

         try {
            this.spool.close();
         } finally {
            this.closeActiveBody();
         }
      }
   }

   private void awaitStartupPrebufferIfNeeded() throws IOException {
      if (!this.startupPrebufferDone && this.readPosition <= 0L) {
         this.startupPrebufferDone = true;
         long target = this.startupPrebufferBytes;
         if (target > 0L) {
            long before = System.currentTimeMillis();
            long cached = this.spool.waitUntilCached(target, this.startupPrebufferMaxWaitMillis);
            long waited = System.currentTimeMillis() - before;
            if (cached > 0L) {
               LOGGER.debug(
                  "HTTP startup prebuffer ready: cached={} target={} waited={}ms offset={} host={}",
                  new Object[]{cached, target, waited, this.startByteOffset, safeHost(this.url)}
               );
            } else {
               LOGGER.debug(
                  "HTTP startup prebuffer empty after wait: target={} waited={}ms offset={} host={}",
                  new Object[]{target, waited, this.startByteOffset, safeHost(this.url)}
               );
               if (this.requestStartupFailover()) {
                  long retryBefore = System.currentTimeMillis();
                  cached = this.spool.waitUntilCached(target, this.startupPrebufferMaxWaitMillis);
                  LOGGER.debug(
                     "HTTP startup prebuffer after CDN failover: cached={} target={} waited={}ms offset={} host={}",
                     new Object[]{cached, target, System.currentTimeMillis() - retryBefore, this.startByteOffset, safeHost(this.url)}
                  );
               }
            }
         }
      }
   }

   private void downloadLoop() {
      try {
         this.downloadWithRangeProbe();
         this.spool.complete();
      } catch (IOException var5) {
         if (!this.closed) {
            LOGGER.warn(
               "HTTP chunk prefetch failed at cached={} read={} mode={}: {}",
               new Object[]{this.spool.cachedLength(), this.readPosition, this.mode, var5.getMessage()}
            );
            this.spool.fail(var5);
         }
      } finally {
         this.spool.complete();
      }
   }

   private void downloadWithRangeProbe() throws IOException {
      if (this.isBiliCdnHost() || this.startByteOffset != 0L || CdnUrlFallbacks.candidates(this.url).size() > 1 || !this.tryFullDownload()) {
         long nextStart = this.startByteOffset;

         while (!this.closed && !this.spool.isComplete()) {
            if (this.mode == ChunkPrefetchInputStream.Mode.RANGE) {
               this.waitUntilCacheNeedsData();
            }

            if (this.closed) {
               return;
            }

            long end = this.safeRangeEnd(nextStart);
            nextStart = this.downloadChunkWithCdnFallback(nextStart, end);
         }
      }
   }

   private long downloadChunkWithCdnFallback(long nextStart, long end) throws IOException {
      List<URL> candidates = CdnUrlFallbacks.candidates(this.url);
      IOException lastError = null;

      for (int i = 0; i < candidates.size(); i++) {
         URL candidate = candidates.get(i);

         for (int attempt = 1; attempt <= PER_HOST_ATTEMPTS; attempt++) {
            try {
               long downloadedTo = this.downloadChunk(candidate, nextStart, end);
               this.startupFailoverUrl.compareAndSet(candidate, null);
               return downloadedTo;
            } catch (ChunkPrefetchInputStream.EmptyCdnResponseException var12) {
               lastError = var12;
               if (this.consumeStartupFailover(candidate)) {
                  Thread.interrupted();
                  logStartupFailover(candidate, candidates, i, nextStart, end);
                  break;
               }

               if (attempt >= PER_HOST_ATTEMPTS || CdnHealthTracker.isCoolingDown(candidate)) {
                  if (i + 1 < candidates.size()) {
                     LOGGER.warn(
                        "CDN returned empty media chunk, retrying alternate host {} -> {} range={}-{}: {}",
                        new Object[]{safeHost(candidate), safeHost(candidates.get(i + 1)), nextStart, end, var12.getMessage()}
                     );
                  }
                  break;
               }

               LOGGER.warn(
                  "CDN returned empty media chunk, retrying same host {} attempt={}/{} range={}-{}: {}",
                  new Object[]{safeHost(candidate), attempt + 1, PER_HOST_ATTEMPTS, nextStart, end, var12.getMessage()}
               );
               backoffBeforeRetry();
            } catch (IOException var13) {
               lastError = var13;
               if (this.consumeStartupFailover(candidate)) {
                  Thread.interrupted();
                  logStartupFailover(candidate, candidates, i, nextStart, end);
                  break;
               }

               if (attempt >= PER_HOST_ATTEMPTS || CdnHealthTracker.isCoolingDown(candidate)) {
                  if (i + 1 < candidates.size()) {
                     LOGGER.warn(
                        "CDN media chunk failed, retrying alternate host {} -> {} range={}-{}: {}",
                        new Object[]{safeHost(candidate), safeHost(candidates.get(i + 1)), nextStart, end, var13.getMessage()}
                     );
                  }
                  break;
               }

               LOGGER.warn(
                  "CDN media chunk failed, retrying same host {} attempt={}/{} range={}-{}: {}",
                  new Object[]{safeHost(candidate), attempt + 1, PER_HOST_ATTEMPTS, nextStart, end, var13.getMessage()}
               );
               backoffBeforeRetry();
            }
         }
      }

      throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
   }

   private static void backoffBeforeRetry() throws IOException {
      if (RETRY_BACKOFF_MILLIS > 0L) {
         try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
         } catch (InterruptedException var1) {
            Thread.currentThread().interrupt();
            throw new IOException("prefetch retry interrupted", var1);
         }
      }
   }

   private long downloadChunk(URL requestUrl, long nextStart, long end) throws IOException {
      long started = System.currentTimeMillis();
      boolean outcomeRecorded = false;
      this.activeRequestUrl.set(requestUrl);

      long var34;
      try (HttpRangeClient.CdnResponse response = this.client.getRangeDirect(requestUrl, nextStart, end)) {
         int status = response.statusCode();
         if (status == 403 || status == 404 || status == 408 || status == 425 || status == 429 || status >= 500) {
            outcomeRecorded = true;
         }

         if (status == 416) {
            this.mode = this.mode == ChunkPrefetchInputStream.Mode.UNKNOWN ? ChunkPrefetchInputStream.Mode.RANGE : this.mode;
            this.spool.complete();
            return nextStart;
         }

         if (status != 206) {
            if (status == 200 && nextStart == 0L) {
               this.mode = ChunkPrefetchInputStream.Mode.SEQUENTIAL;
               this.totalLength = response.contentLength();
               LOGGER.debug("HTTP prefetch mode: sequential GET, contentLength={} host={}", this.totalLength, safeHost(requestUrl));
               long received = this.copyResponseToSpool(response.body(), -1L, Long.MAX_VALUE);
               if (received == 0L) {
                  CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.EMPTY);
                  outcomeRecorded = true;
                  throw new ChunkPrefetchInputStream.EmptyCdnResponseException("0 bytes for sequential GET host=" + safeHost(requestUrl));
               }

               CdnHealthTracker.recordSuccess(requestUrl, System.currentTimeMillis() - started, received);
               BiliCdnSelector.recordSuccess(requestUrl.toString());
               outcomeRecorded = true;
               this.spool.complete();
               return received;
            }

            throw new IOException(
               "HTTP "
                  + status
                  + " from CDN range request host="
                  + safeHost(requestUrl)
                  + " range="
                  + nextStart
                  + "-"
                  + end
                  + " offset="
                  + this.startByteOffset
            );
         }

         if (this.mode != ChunkPrefetchInputStream.Mode.RANGE) {
            this.mode = ChunkPrefetchInputStream.Mode.RANGE;
         }

         if (response.totalLength() > 0L) {
            this.totalLength = response.totalLength();
         }

         long requestedLength = end - nextStart + 1L;
         long expectedLength = this.validatePartialResponse(response, nextStart, end, requestedLength);
         byte[] chunk = this.readCompleteRangeChunk(response.body(), expectedLength);
         long received = chunk.length;
         this.spool.write(chunk, 0, chunk.length);
         LOGGER.trace(
            "HTTP chunk received: range={}-{} received={} cached={} host={}",
            new Object[]{nextStart, end, received, this.spool.cachedLength(), safeHost(requestUrl)}
         );
         long newNextStart = nextStart + received;
         CdnHealthTracker.recordSuccess(requestUrl, System.currentTimeMillis() - started, received);
         BiliCdnSelector.recordSuccess(requestUrl.toString());
         outcomeRecorded = true;
         if (this.totalLength > 0L && newNextStart >= this.totalLength) {
            this.spool.complete();
            return newNextStart;
         }

         var34 = newNextStart;
      } catch (IOException var29) {
         if (!outcomeRecorded) {
            CdnHealthTracker.recordFailure(requestUrl, CdnHealthTracker.FailureKind.IO);
         }

         throw var29;
      } finally {
         this.activeRequestUrl.compareAndSet(requestUrl, null);
      }

      return var34;
   }

   private long validatePartialResponse(HttpRangeClient.CdnResponse response, long requestedStart, long requestedEnd, long requestedLength) throws IOException {
      if (response.rangeStart() >= 0L && response.rangeStart() != requestedStart) {
         throw new IOException("CDN Content-Range starts at " + response.rangeStart() + " instead of requested " + requestedStart);
      } else if (response.rangeEndInclusive() < 0L || response.rangeEndInclusive() >= requestedStart && response.rangeEndInclusive() <= requestedEnd) {
         if (response.rangeEndInclusive() < 0L
            || response.rangeEndInclusive() >= requestedEnd
            || response.totalLength() > 0L && response.rangeEndInclusive() + 1L >= response.totalLength()) {
            long contentRangeLength = response.rangeStart() >= 0L && response.rangeEndInclusive() >= response.rangeStart()
               ? response.rangeEndInclusive() - response.rangeStart() + 1L
               : -1L;
            long expectedLength = contentRangeLength > 0L ? contentRangeLength : (response.contentLength() > 0L ? response.contentLength() : requestedLength);
            if (response.contentLength() > 0L && contentRangeLength > 0L && response.contentLength() != contentRangeLength) {
               throw new IOException("CDN Content-Length " + response.contentLength() + " does not match Content-Range length " + contentRangeLength);
            } else if (expectedLength > 0L && expectedLength <= requestedLength && expectedLength <= 2147483647L) {
               return expectedLength;
            } else {
               throw new IOException("invalid CDN range response length " + expectedLength + " for requested " + requestedStart + "-" + requestedEnd);
            }
         } else {
            throw new IOException("CDN Content-Range ended early at " + response.rangeEndInclusive() + " before requested " + requestedEnd);
         }
      } else {
         throw new IOException("CDN Content-Range ends at " + response.rangeEndInclusive() + " outside requested " + requestedStart + "-" + requestedEnd);
      }
   }

   private byte[] readCompleteRangeChunk(InputStream body, long expectedLength) throws IOException {
      this.activeBody.set(body);

      byte[] var4;
      try {
         var4 = CompleteChunkReader.read(body, expectedLength, COPY_BUFFER_SIZE);
      } finally {
         this.activeBody.compareAndSet(body, null);
      }

      return var4;
   }

   private long copyResponseToSpool(InputStream body, long contentLength, long maxBytes) throws IOException {
      this.activeBody.set(body);
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      long copied = 0L;

      try {
         while (!this.closed && copied < maxBytes) {
            int writable = this.awaitWritableBytes();
            if (writable <= 0) {
               break;
            }

            int toRead = (int)Math.min((long)Math.min(buffer.length, writable), maxBytes - copied);
            int n = body.read(buffer, 0, toRead);
            if (n < 0) {
               break;
            }

            if (n != 0) {
               this.spool.write(buffer, 0, n);
               copied += n;
            }
         }
      } finally {
         this.activeBody.compareAndSet(body, null);
      }

      if (!this.closed && contentLength >= 0L && copied < contentLength) {
         throw new IOException("CDN response ended early: expected " + contentLength + ", got " + copied);
      } else {
         return copied;
      }
   }

   private int awaitWritableBytes() throws IOException {
      synchronized (this.demandLock) {
         long ahead = Math.max(0L, this.spool.cachedLength() - this.readPosition);
         if (ahead >= this.highWater) {
            this.backpressurePaused = true;
         }

         for (;
            !this.closed && !this.spool.isComplete() && this.backpressurePaused && (ahead > this.lowWater || ahead >= this.highWater);
            ahead = Math.max(0L, this.spool.cachedLength() - this.readPosition)
         ) {
            try {
               this.demandLock.wait(500L);
            } catch (InterruptedException var6) {
               Thread.currentThread().interrupt();
               throw new IOException("prefetch downloader interrupted", var6);
            }
         }

         if (!this.closed && !this.spool.isComplete()) {
            this.backpressurePaused = false;
            return (int)Math.min(2147483647L, this.highWater - ahead);
         } else {
            return 0;
         }
      }
   }

   private static String safeHost(URL url) {
      String host = url != null ? url.getHost() : null;
      return host != null && !host.isBlank() ? host : "unknown";
   }

   private long safeRangeEnd(long start) {
      long requestBytes = StartupCdnFailoverPolicy.firstRequestBytes(
         this.chunkSize, this.startupPrebufferBytes, start == this.startByteOffset && this.spool.cachedLength() == 0L
      );
      long end = start + Math.max(1L, requestBytes) - 1L;
      return end < start ? Long.MAX_VALUE : end;
   }

   private void waitUntilCacheNeedsData() throws IOException {
      synchronized (this.demandLock) {
         while (!this.closed && !this.spool.isComplete() && this.spool.cachedLength() - this.readPosition >= this.highWater) {
            try {
               this.demandLock.wait(500L);
            } catch (InterruptedException var4) {
               Thread.currentThread().interrupt();
               throw new IOException("prefetch downloader interrupted", var4);
            }
         }

         if (!this.closed && this.spool.cachedLength() - this.readPosition <= this.lowWater) {
            LOGGER.trace(
               "HTTP cache low: read={} cached={} ahead={} mode={}",
               new Object[]{this.readPosition, this.spool.cachedLength(), this.spool.cachedLength() - this.readPosition, this.mode}
            );
         }
      }
   }

   private void notifyDemand() {
      synchronized (this.demandLock) {
         this.demandLock.notifyAll();
      }
   }

   private boolean isBiliCdnHost() {
      String host = this.url.getHost();
      if (host == null) {
         return false;
      } else {
         String lower = host.toLowerCase(Locale.ROOT);
         return lower.contains("bilibili") || lower.contains("bilivideo") || lower.contains("hdslb") || lower.contains("mcdn");
      }
   }

   private boolean tryFullDownload() throws IOException {
      long started = System.currentTimeMillis();

      try {
         boolean received;
         try (HttpRangeClient.CdnResponse response = this.client.get(this.url)) {
            int status = response.statusCode();
            if (status == 200) {
               this.mode = ChunkPrefetchInputStream.Mode.SEQUENTIAL;
               this.totalLength = response.contentLength();
               LOGGER.debug("HTTP prefetch mode: full GET, contentLength={}", this.totalLength);
               long receivedx = this.copyResponseToSpool(response.body(), response.contentLength(), Long.MAX_VALUE);
               if (receivedx == 0L) {
                  CdnHealthTracker.recordFailure(this.url, CdnHealthTracker.FailureKind.EMPTY);
                  throw new ChunkPrefetchInputStream.EmptyCdnResponseException("0 bytes for full GET host=" + safeHost(this.url));
               }

               CdnHealthTracker.recordSuccess(this.url, System.currentTimeMillis() - started, receivedx);
               return true;
            }

            received = false;
         }

         return received;
      } catch (IOException var10) {
         LOGGER.debug("HTTP full GET not supported ({}), falling back to Range", var10.getMessage());
         return false;
      }
   }

   private void closeActiveBody() {
      InputStream body = this.activeBody.getAndSet(null);
      if (body != null) {
         try {
            body.close();
         } catch (IOException var3) {
         }
      }
   }

   private boolean requestStartupFailover() {
      long cached = this.spool.cachedLength();
      int candidateCount = CdnUrlFallbacks.candidates(this.url).size();
      URL active = this.activeRequestUrl.get();
      if (!StartupCdnFailoverPolicy.shouldSwitch(this.closed, cached, candidateCount, active != null, this.startupFailoverUrl.get() != null)) {
         return false;
      } else if (active != null && this.startupFailoverUrl.compareAndSet(null, active)) {
         if (this.spool.cachedLength() > 0L) {
            this.startupFailoverUrl.compareAndSet(active, null);
            return false;
         } else {
            LOGGER.warn(
               "CDN startup produced no bytes within {}ms; cancelling slow host {} and trying alternate", this.startupPrebufferMaxWaitMillis, safeHost(active)
            );
            this.closeActiveBody();
            this.downloader.interrupt();
            return true;
         }
      } else {
         return false;
      }
   }

   private boolean consumeStartupFailover(URL candidate) {
      return candidate != null && this.startupFailoverUrl.compareAndSet(candidate, null);
   }

   private static void logStartupFailover(URL candidate, List<URL> candidates, int index, long nextStart, long end) {
      if (index + 1 < candidates.size()) {
         LOGGER.warn(
            "Slow CDN startup switched to alternate host {} -> {} range={}-{}",
            new Object[]{safeHost(candidate), safeHost(candidates.get(index + 1)), nextStart, end}
         );
      }
   }

   public static final class EmptyCdnResponseException extends IOException {
      public EmptyCdnResponseException(String message) {
         super(message);
      }
   }

   private static enum Mode {
      UNKNOWN,
      RANGE,
      SEQUENTIAL;
   }
}
