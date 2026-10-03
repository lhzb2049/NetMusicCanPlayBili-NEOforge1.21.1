package com.zhongbai233.net_music_can_play_bili.bili;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4SeekRangeCache;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.LifecycleClose;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

final class Fmp4AudioStreamSeeker {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final AudioStreamProperties.Http PROPERTIES = AudioStreamProperties.http();
   private static final int INIT_PROBE_BYTES = 4194304;
   private static final int MOOF_SCAN_BYTES = 2097152;
   private static final int SEEK_MAX_ATTEMPTS = 3;
   private static final double CLOSE_FRAGMENT_SECONDS = 15.0;
   private static final double TARGET_EPSILON_SECONDS = 0.05;
   private static final long SEEK_PREROLL_BYTES = 262144L;
   private static final long SEGMENT_BASE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30L);
   private static final int RANGE_RACE_MAX_CANDIDATES = PROPERTIES.rangeRaceMaxCandidates();
   private static final long RANGE_RACE_TIMEOUT_MILLIS = PROPERTIES.rangeRaceTimeoutMillis();
   private static final int MAX_SEGMENT_BASE_ENTRIES = PROPERTIES.segmentBaseCacheMaxEntries();
   private static final ExecutorService RANGE_RACE_EXECUTOR = Executors.newFixedThreadPool(
      RANGE_RACE_MAX_CANDIDATES, NetMusicThreadFactory.daemon("bili-audio-range-race")
   );
   private static final ConcurrentHashMap<String, Fmp4AudioStreamSeeker.SegmentBaseInfo> SEGMENT_BASE_BY_URL = new ConcurrentHashMap<>();

   private Fmp4AudioStreamSeeker() {
   }

   static void registerSegmentBase(String audioUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
      if (audioUrl != null && !audioUrl.isBlank() && initStart >= 0L && initEnd >= initStart && indexStart >= 0L && indexEnd >= indexStart) {
         long now = System.currentTimeMillis();
         cleanupSegmentBaseInfo(now);
         SEGMENT_BASE_BY_URL.put(audioUrl, new Fmp4AudioStreamSeeker.SegmentBaseInfo(initStart, initEnd, indexStart, indexEnd, now));
      }
   }

   static void clearSegmentBases() {
      SEGMENT_BASE_BY_URL.clear();
   }

   static Fmp4AudioStreamSeeker.StreamStart open(URL url, PlaybackRequest request, float startOffsetSeconds) throws IOException {
      if (request != null && startOffsetSeconds > 1.0F && request.totalMillis() > 0L) {
         Fmp4AudioStreamSeeker.StreamStart ranged = tryOpenRangeSeek(url, request, startOffsetSeconds);
         if (ranged != null) {
            return ranged;
         }
      }

      return new Fmp4AudioStreamSeeker.StreamStart(openPrefetchWithCdnFallback(url, 0L), startOffsetSeconds);
   }

   private static ChunkPrefetchInputStream openPrefetchWithCdnFallback(URL primary, long startByteOffset) throws IOException {
      List<URL> candidates = CdnUrlFallbacks.candidates(primary);
      IOException lastError = null;

      for (int i = 0; i < candidates.size(); i++) {
         URL candidate = candidates.get(i);

         try {
            return new ChunkPrefetchInputStream(candidate, startByteOffset);
         } catch (ChunkPrefetchInputStream.EmptyCdnResponseException var8) {
            lastError = var8;
            if (i + 1 < candidates.size()) {
               LOGGER.warn(
                  "Audio CDN returned empty body, retrying alternate host {} -> {} offset={}: {}",
                  new Object[]{candidate.getHost(), candidates.get(i + 1).getHost(), startByteOffset, var8.getMessage()}
               );
            }
         }
      }

      throw lastError != null ? lastError : new IOException("no CDN URL candidates available");
   }

   private static Fmp4AudioStreamSeeker.StreamStart tryOpenRangeSeek(URL url, PlaybackRequest request, float targetSeconds) {
      ChunkPrefetchInputStream lastRange = null;

      try {
         long started = System.currentTimeMillis();
         Fmp4RangeSeekSupport.InitSegment init = readInitSegment(url);
         long contentLength = init.contentLength();
         if (contentLength <= 0L) {
            return null;
         } else {
            Fmp4AudioStreamSeeker.StreamStart sidxStart = tryOpenSidxSeek(url, request, init, targetSeconds);
            if (sidxStart != null) {
               LOGGER.debug(
                  "音频fMP4 seek 总耗时: mode=sidx cost={}ms target={}s host={}", new Object[]{System.currentTimeMillis() - started, targetSeconds, url.getHost()}
               );
               return sidxStart;
            } else {
               long elapsedMillis = Math.max(0L, request.elapsedMillis());
               long totalMillis = Math.max(1L, request.totalMillis());
               double ratio = Math.max(0.0, Math.min(0.98, (double)elapsedMillis / totalMillis));
               long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * ratio)));
               long rangeStart = Math.max((long)init.bytes().length, estimatedOffset - 262144L);
               int timescale = init.timescale() > 0 ? init.timescale() : '뮀';

               for (int attempt = 0; attempt < 3; attempt++) {
                  ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, rangeStart);
                  lastRange = range;

                  Object absoluteMoofOffset;
                  try {
                     Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(range, targetSeconds, timescale, 2097152, 0.05, 15.0);
                     if (probe != null) {
                        byte[] probeBytes = probe.bytes();
                        Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                        long absoluteMoofOffsetx = rangeStart + candidate.offset();
                        if (attempt + 1 < 3 && Fmp4RangeSeekSupport.shouldRetry(candidate, targetSeconds, 0.05, 15.0)) {
                           long nextStart = Fmp4RangeSeekSupport.nextRangeStart(
                              candidate, targetSeconds, totalMillis / 1000.0, contentLength, absoluteMoofOffsetx, init.bytes().length, 262144L
                           );
                           if (Math.abs(nextStart - rangeStart) > 262144L) {
                              closeQuietly(range);
                              lastRange = null;
                              rangeStart = nextStart;
                              continue;
                           }
                        }

                        if (!Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds, 0.05)) {
                           float residualSeconds = Fmp4RangeSeekSupport.residualSeconds(
                              targetSeconds, candidate, totalMillis / 1000.0, contentLength, absoluteMoofOffsetx
                           );
                           InputStream tail = new SequenceInputStream(
                              new ByteArrayInputStream(probeBytes, candidate.offset(), probeBytes.length - candidate.offset()), range
                           );
                           lastRange = null;
                           InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                           LOGGER.debug(
                              "音频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} cost={}ms host={}",
                              new Object[]{
                                 targetSeconds,
                                 candidate.fragmentSeconds(),
                                 residualSeconds,
                                 request.startOffsetSeconds(),
                                 absoluteMoofOffsetx,
                                 contentLength,
                                 System.currentTimeMillis() - started,
                                 url.getHost()
                              }
                           );
                           return new Fmp4AudioStreamSeeker.StreamStart(combined, residualSeconds);
                        }

                        LOGGER.debug(
                           "fMP4 range seek candidate is after target: target={}s fragment={}s byte={} attemptsExhausted=true; falling back to decoded skip",
                           new Object[]{targetSeconds, candidate.fragmentSeconds(), absoluteMoofOffsetx}
                        );
                        return null;
                     }

                     closeQuietly(range);
                     lastRange = null;
                     long nextStart = Math.min(contentLength - 1L, rangeStart + 2097152L);
                     if (attempt + 1 < 3 && nextStart > rangeStart) {
                        rangeStart = nextStart;
                        continue;
                     }

                     absoluteMoofOffset = null;
                  } finally {
                     if (lastRange == range) {
                        closeQuietly(range);
                        lastRange = null;
                     }
                  }

                  return (Fmp4AudioStreamSeeker.StreamStart)absoluteMoofOffset;
               }

               return null;
            }
         }
      } catch (RuntimeException | IOException var36) {
         LOGGER.debug("fMP4 range seek unavailable, falling back to decoded skip: {}", var36.getMessage());
         closeQuietly(lastRange);
         return null;
      }
   }

   private static Fmp4AudioStreamSeeker.StreamStart tryOpenSidxSeek(
      URL url, PlaybackRequest request, Fmp4RangeSeekSupport.InitSegment init, float targetSeconds
   ) {
      Fmp4AudioStreamSeeker.SegmentBaseInfo info = segmentBaseInfo(url.toString());
      if (info == null) {
         return null;
      } else {
         try {
            byte[] sidxBytes = readRangeBytes(url, info.indexStart(), info.indexEnd());
            Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxBytes, info.indexStart());
            if (sidx != null && !sidx.entries().isEmpty()) {
               Fmp4RangeSeekSupport.SidxEntry selected = null;

               for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                  if (entry.timeSeconds() > targetSeconds + 0.05) {
                     break;
                  }

                  if (entry.startsWithSap()) {
                     selected = entry;
                  }
               }

               if (selected == null) {
                  for (Fmp4RangeSeekSupport.SidxEntry entry : sidx.entries()) {
                     if (entry.timeSeconds() > targetSeconds + 0.05) {
                        break;
                     }

                     selected = entry;
                  }
               }

               if (selected == null) {
                  selected = sidx.entries().get(0);
               }

               ChunkPrefetchInputStream range = openPrefetchWithCdnFallback(url, selected.byteStart());

               try {
                  int timescale = init.timescale() > 0 ? init.timescale() : (int)Math.min(2147483647L, sidx.timescale());
                  Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(
                     range, targetSeconds, timescale > 0 ? timescale : '뮀', 2097152, 0.05, 15.0
                  );
                  if (probe == null) {
                     closeQuietly(range);
                     return null;
                  } else {
                     byte[] probeBytes = probe.bytes();
                     Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                     if (Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds, 0.05)) {
                        LOGGER.debug(
                           "音频fMP4 SidxSeek 命中目标之后 fragment，回退 Moof RangeSeek: target={}s fragment={}s byte={}",
                           new Object[]{targetSeconds, candidate.fragmentSeconds(), selected.byteStart()}
                        );
                        closeQuietly(range);
                        return null;
                     } else {
                        double fragmentSeconds = !Double.isNaN(candidate.fragmentSeconds()) ? candidate.fragmentSeconds() : selected.timeSeconds();
                        float residualSeconds = (float)Math.max(0.0, Math.min((double)targetSeconds, targetSeconds - fragmentSeconds));
                        InputStream tail = new SequenceInputStream(
                           new ByteArrayInputStream(probeBytes, candidate.offset(), probeBytes.length - candidate.offset()), range
                        );
                        InputStream combined = new SequenceInputStream(new ByteArrayInputStream(init.bytes()), tail);
                        LOGGER.debug(
                           "音频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} host={}",
                           new Object[]{
                              targetSeconds,
                              fragmentSeconds,
                              residualSeconds,
                              request.startOffsetSeconds(),
                              selected.byteStart(),
                              init.contentLength(),
                              url.getHost()
                           }
                        );
                        LOGGER.debug(
                           "音频fMP4 SidxSeek 选择: target={}s selectedFragment={}s startsWithSap={} byte={}",
                           new Object[]{targetSeconds, fragmentSeconds, selected.startsWithSap(), selected.byteStart()}
                        );
                        return new Fmp4AudioStreamSeeker.StreamStart(combined, residualSeconds);
                     }
                  }
               } catch (RuntimeException | IOException var18) {
                  closeQuietly(range);
                  throw var18;
               }
            } else {
               return null;
            }
         } catch (RuntimeException | IOException var19) {
            LOGGER.debug("fMP4 audio sidx seek unavailable, falling back to range seek: {}", var19.getMessage());
            return null;
         }
      }
   }

   private static byte[] readRangeBytes(URL url, long start, long end) throws IOException {
      return readRange(url, start, end).bytes();
   }

   private static Fmp4AudioStreamSeeker.RangeBytes readRange(URL url, long start, long end) throws IOException {
      Fmp4SeekRangeCache.CachedRange cached = Fmp4SeekRangeCache.get(url, start, end);
      if (cached != null) {
         return new Fmp4AudioStreamSeeker.RangeBytes(cached.bytes(), cached.totalLength(), cached.sourceHost(), cached.sourceUrl());
      } else {
         List<URL> candidates = CdnUrlFallbacks.candidates(url);
         if (candidates.size() > 1 && RANGE_RACE_MAX_CANDIDATES > 1) {
            Fmp4AudioStreamSeeker.RangeBytes raced = readRangeRace(candidates, start, end);
            if (raced != null) {
               Fmp4SeekRangeCache.put(url, start, end, raced.bytes(), raced.totalLength(), raced.host(), raced.url());
               return raced;
            }
         }

         Fmp4AudioStreamSeeker.RangeBytes result = readRangeSingle(url, start, end);
         Fmp4SeekRangeCache.put(url, start, end, result.bytes(), result.totalLength(), result.host(), result.url());
         return result;
      }
   }

   private static Fmp4AudioStreamSeeker.RangeBytes readRangeRace(List<URL> candidates, long start, long end) throws IOException {
      int count = Math.min(RANGE_RACE_MAX_CANDIDATES, candidates.size());
      CompletableFuture<Fmp4AudioStreamSeeker.RangeBytes> first = new CompletableFuture<>();
      List<Future<?>> tasks = new ArrayList<>(count);
      AtomicInteger failures = new AtomicInteger();
      AtomicReference<IOException> lastError = new AtomicReference<>();

      for (int i = 0; i < count; i++) {
         URL candidate = candidates.get(i);
         tasks.add(RANGE_RACE_EXECUTOR.submit(() -> {
            if (!first.isDone()) {
               try {
                  first.complete(readRangeSingle(candidate, start, end, false));
               } catch (IOException var10x) {
                  lastError.set(var10x);
                  if (failures.incrementAndGet() >= count) {
                     first.completeExceptionally(lastError.get());
                  }
               }
            }
         }));
      }

      Object var24;
      try {
         Fmp4AudioStreamSeeker.RangeBytes winner = first.get(RANGE_RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
         BiliCdnSelector.recordSuccess(winner.url());
         LOGGER.debug(
            "音频小范围 CDN 赛马完成: range={}-{} bytes={} total={} host={}", new Object[]{start, end, winner.bytes().length, winner.totalLength(), winner.host()}
         );
         return winner;
      } catch (TimeoutException var18) {
         LOGGER.debug("音频小范围 CDN 赛马超时，回退串行读取: range={}-{} timeout={}ms candidates={}", new Object[]{start, end, RANGE_RACE_TIMEOUT_MILLIS, count});
         var24 = null;
      } catch (InterruptedException var19) {
         Thread.currentThread().interrupt();
         throw new IOException("interrupted while racing audio range", var19);
      } catch (ExecutionException var20) {
         Throwable cause = var20.getCause();
         if (cause instanceof IOException io) {
            throw io;
         }

         throw new IOException("audio range race failed", cause);
      } finally {
         tasks.forEach(task -> task.cancel(true));
      }

      return (Fmp4AudioStreamSeeker.RangeBytes)var24;
   }

   private static Fmp4AudioStreamSeeker.RangeBytes readRangeSingle(URL url, long start, long end) throws IOException {
      return readRangeSingle(url, start, end, true);
   }

   private static Fmp4AudioStreamSeeker.RangeBytes readRangeSingle(URL url, long start, long end, boolean persistCdnSuccess) throws IOException {
      HttpRangeClient client = new HttpRangeClient();
      long started = System.currentTimeMillis();
      boolean failureRecorded = false;
      URL actualUrl = url;

      try {
         Fmp4AudioStreamSeeker.RangeBytes var24;
         try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, start, end)) {
            actualUrl = response.sourceUrl();
            int status = response.statusCode();
            if (status != 206 && (status != 200 || start != 0L)) {
               failureRecorded = isRetryableCdnStatus(status);
               throw new IOException("HTTP " + status + " while reading fMP4 range");
            }

            if (status == 206 && response.rangeStart() >= 0L && response.rangeStart() != start) {
               throw new IOException("fMP4 Content-Range starts at " + response.rangeStart() + " instead of requested " + start);
            }

            long maxBytes = Math.max(1L, end - start + 1L);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(maxBytes, 1048576L));
            byte[] buffer = new byte[65536];
            long remaining = maxBytes;

            while (remaining > 0L) {
               int n = response.body().read(buffer, 0, (int)Math.min((long)buffer.length, remaining));
               if (n < 0) {
                  break;
               }

               if (n != 0) {
                  out.write(buffer, 0, n);
                  remaining -= n;
               }
            }

            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
               CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.EMPTY);
               failureRecorded = true;
               throw new IOException("empty fMP4 range response from " + actualUrl.getHost());
            }

            long declaredLength = response.rangeStart() >= 0L && response.rangeEndInclusive() >= response.rangeStart()
               ? response.rangeEndInclusive() - response.rangeStart() + 1L
               : Math.min(maxBytes, response.contentLength());
            if (declaredLength > 0L && bytes.length < declaredLength) {
               CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.SHORT_READ);
               failureRecorded = true;
               throw new IOException("short fMP4 range response from " + actualUrl.getHost() + ": expected=" + declaredLength + " actual=" + bytes.length);
            }

            CdnHealthTracker.recordSuccess(actualUrl, System.currentTimeMillis() - started, bytes.length);
            if (persistCdnSuccess) {
               BiliCdnSelector.recordSuccess(actualUrl.toString());
            }

            long totalLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            var24 = new Fmp4AudioStreamSeeker.RangeBytes(bytes, totalLength, actualUrl.getHost(), actualUrl.toString());
         }

         return var24;
      } catch (IOException var27) {
         if (!failureRecorded) {
            CdnHealthTracker.recordFailure(actualUrl, CdnHealthTracker.FailureKind.IO);
         }

         throw var27;
      }
   }

   private static boolean isRetryableCdnStatus(int status) {
      return status == 403 || status == 404 || status == 408 || status == 425 || status == 429 || status >= 500;
   }

   private static Fmp4RangeSeekSupport.InitSegment readInitSegment(URL url) throws IOException {
      Fmp4AudioStreamSeeker.SegmentBaseInfo info = segmentBaseInfo(url.toString());
      if (info != null) {
         Fmp4AudioStreamSeeker.RangeBytes initRange = readRange(url, info.initStart(), info.initEnd());
         Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(
            initRange.bytes(), initRange.totalLength(), (moovPayload, moov) -> moov.timescale
         );
         if (init != null && init.contentLength() > 0L) {
            return init;
         }

         LOGGER.debug(
            "fMP4 audio segment_base init unusable, falling back to probe: initRange={}-{} host={}",
            new Object[]{info.initStart(), info.initEnd(), url.getHost()}
         );
      }

      HttpRangeClient client = new HttpRangeClient();

      Fmp4RangeSeekSupport.InitSegment var16;
      try (HttpRangeClient.CdnResponse response = client.getRange(url, 0L, 4194303L)) {
         int status = response.statusCode();
         if (status != 206 && status != 200) {
            throw new IOException("HTTP " + status + " while probing fMP4 init segment");
         }

         long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
         ByteArrayOutputStream prefix = new ByteArrayOutputStream();
         byte[] buffer = new byte[65536];
         Fmp4RangeSeekSupport.InitSegment init = null;

         while (prefix.size() < 4194304) {
            int request = Math.min(buffer.length, 4194304 - prefix.size());
            int n = response.body().read(buffer, 0, request);
            if (n < 0) {
               break;
            }

            if (n != 0) {
               prefix.write(buffer, 0, n);
               init = Fmp4RangeSeekSupport.extractInitSegment(prefix.toByteArray(), contentLength, (moovPayload, moov) -> moov.timescale);
               if (init != null) {
                  break;
               }
            }
         }

         if (init == null) {
            throw new IOException("unable to read complete fMP4 init segment");
         }

         var16 = init;
      }

      return var16;
   }

   private static Fmp4AudioStreamSeeker.SegmentBaseInfo segmentBaseInfo(String url) {
      Fmp4AudioStreamSeeker.SegmentBaseInfo info = SEGMENT_BASE_BY_URL.get(url);
      if (info == null) {
         return null;
      } else {
         long now = System.currentTimeMillis();
         if (now - info.createdAtMillis() > SEGMENT_BASE_TTL_MILLIS) {
            SEGMENT_BASE_BY_URL.remove(url, info);
            return null;
         } else {
            return info;
         }
      }
   }

   private static void cleanupSegmentBaseInfo(long now) {
      if (!SEGMENT_BASE_BY_URL.isEmpty()) {
         SEGMENT_BASE_BY_URL.entrySet()
            .removeIf(entryx -> now - ((Fmp4AudioStreamSeeker.SegmentBaseInfo)entryx.getValue()).createdAtMillis() > SEGMENT_BASE_TTL_MILLIS);
         int maxEntries = Math.max(1, MAX_SEGMENT_BASE_ENTRIES);

         while (SEGMENT_BASE_BY_URL.size() > maxEntries) {
            String oldestKey = null;
            long oldestCreatedAt = Long.MAX_VALUE;

            for (Entry<String, Fmp4AudioStreamSeeker.SegmentBaseInfo> entry : SEGMENT_BASE_BY_URL.entrySet()) {
               long createdAt = entry.getValue().createdAtMillis();
               if (createdAt < oldestCreatedAt) {
                  oldestCreatedAt = createdAt;
                  oldestKey = entry.getKey();
               }
            }

            if (oldestKey == null) {
               return;
            }

            SEGMENT_BASE_BY_URL.remove(oldestKey);
         }
      }
   }

   private static void closeQuietly(InputStream stream) {
      LifecycleClose.closeQuietly(stream);
   }

   private record RangeBytes(byte[] bytes, long totalLength, String host, String url) {
   }

   private record SegmentBaseInfo(long initStart, long initEnd, long indexStart, long indexEnd, long createdAtMillis) {
   }

   record StreamStart(InputStream stream, float startOffsetSeconds) {
   }
}
