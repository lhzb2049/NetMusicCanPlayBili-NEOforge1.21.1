package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliCdnSelector;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.AudioStreamProperties;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnUrlFallbacks;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4SeekRangeCache;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

final class Fmp4VideoStreamSeeker {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Fmp4NativeVideoProperties.Decoder PROPERTIES = Fmp4NativeVideoProperties.decoder();
   private static final Fmp4NativeVideoProperties.Seek SEEK = Fmp4NativeVideoProperties.seek();
   private static final AudioStreamProperties.Http HTTP = AudioStreamProperties.http();
   private static final int INIT_PROBE_BYTES = SEEK.initProbeBytes();
   private static final int MOOF_SCAN_BYTES = SEEK.moofScanBytes();
   private static final int SEEK_MAX_ATTEMPTS = SEEK.maxAttempts();
   private static final long SEEK_PREROLL_BYTES = SEEK.prerollBytes();
   private static final double CLOSE_FRAGMENT_SECONDS = SEEK.closeFragmentSeconds();
   private static final double TARGET_EPSILON_SECONDS = SEEK.targetEpsilonSeconds();
   private static final double SEEK_LEAD_SECONDS = SEEK.leadSeconds();
   private static final boolean RANGE_SEEK_ENABLED = SEEK.rangeEnabled();
   private static final long RANGE_SEEK_AUTO_OFFSET_MILLIS = SEEK.autoOffsetMillis();
   private static final double FALLBACK_MAX_RESIDUAL_SECONDS = SEEK.fallbackMaxResidualSeconds();
   private static final int RANGE_RACE_MAX_CANDIDATES = HTTP.rangeRaceMaxCandidates();
   private static final long RANGE_RACE_TIMEOUT_MILLIS = HTTP.rangeRaceTimeoutMillis();
   private static final long SEGMENT_BASE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30L);
   private static final int MAX_SEGMENT_BASE_ENTRIES = PROPERTIES.segmentBaseCacheMaxEntries();
   private static final ConcurrentHashMap<String, Fmp4VideoStreamSeeker.SegmentBaseInfo> SEGMENT_BASE_BY_URL = new ConcurrentHashMap<>();
   private static final ExecutorService RANGE_RACE_EXECUTOR = Executors.newFixedThreadPool(
      RANGE_RACE_MAX_CANDIDATES, NetMusicThreadFactory.daemon("bili-video-range-race")
   );
   private final URL videoUrl;
   private final long timelineStartOffsetMillis;
   private final long totalMillis;
   private final NativeVideoTrackedInputs trackedInputs;
   private final AtomicBoolean closed;
   private final HttpRangeClient http = new HttpRangeClient();

   Fmp4VideoStreamSeeker(URL videoUrl, long timelineStartOffsetMillis, long totalMillis, NativeVideoTrackedInputs trackedInputs, AtomicBoolean closed) {
      this.videoUrl = videoUrl;
      this.timelineStartOffsetMillis = Math.max(0L, timelineStartOffsetMillis);
      this.totalMillis = Math.max(0L, totalMillis);
      this.trackedInputs = trackedInputs;
      this.closed = closed;
   }

   static void registerSegmentBase(String videoUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
      if (videoUrl != null && !videoUrl.isBlank() && initStart >= 0L && initEnd >= initStart && indexStart >= 0L && indexEnd >= indexStart) {
         long now = System.currentTimeMillis();
         cleanupSegmentBaseInfo(now);
         SEGMENT_BASE_BY_URL.put(videoUrl, new Fmp4VideoStreamSeeker.SegmentBaseInfo(initStart, initEnd, indexStart, indexEnd, now));
      }
   }

   Fmp4VideoStreamSeeker.StreamStart open(long offsetMillis) throws IOException {
      if (offsetMillis <= 1000L) {
         return new Fmp4VideoStreamSeeker.StreamStart(this.trackInput(new ChunkPrefetchInputStream(this.videoUrl)), 0.0F, 0.0);
      } else {
         boolean shouldTryRangeSeek = RANGE_SEEK_ENABLED || offsetMillis >= Math.max(1001L, RANGE_SEEK_AUTO_OFFSET_MILLIS);
         if (shouldTryRangeSeek) {
            Fmp4VideoStreamSeeker.StreamStart ranged = this.tryOpenRangeSeek(offsetMillis);
            if (ranged != null) {
               return ranged;
            }
         }

         double requestedResidualSeconds = offsetMillis / 1000.0;
         float fallbackResidualSeconds = (float)Math.max(
            0.0, FALLBACK_MAX_RESIDUAL_SECONDS >= 0.0 ? Math.min(requestedResidualSeconds, FALLBACK_MAX_RESIDUAL_SECONDS) : requestedResidualSeconds
         );
         return new Fmp4VideoStreamSeeker.StreamStart(this.trackInput(new ChunkPrefetchInputStream(this.videoUrl)), fallbackResidualSeconds, 0.0);
      }
   }

   private Fmp4VideoStreamSeeker.StreamStart tryOpenRangeSeek(long offsetMillis) {
      InputStream lastRange = null;
      long seekStartNanos = System.nanoTime();

      try {
         Fmp4RangeSeekSupport.InitSegment init = this.readInitSegment();
         long contentLength = init.contentLength();
         if (contentLength <= 0L) {
            return null;
         } else {
            float playbackSeconds = (float)offsetMillis / 1000.0F;
            double durationSeconds = this.totalMillis > 0L ? this.totalMillis / 1000.0 : Math.max(playbackSeconds + SEEK_LEAD_SECONDS + 60.0, 1.0);
            float targetSeconds = seekTargetSeconds(playbackSeconds, durationSeconds);
            Fmp4VideoStreamSeeker.StreamStart sidxStart = this.tryOpenSidxSeek(init, targetSeconds, playbackSeconds, seekStartNanos);
            if (sidxStart != null) {
               return sidxStart;
            } else {
               long estimatedOffset = Math.min(contentLength - 1L, Math.max(0L, Math.round(contentLength * Math.min(0.98, targetSeconds / durationSeconds))));
               long rangeStart = Math.max((long)init.bytes().length, estimatedOffset - SEEK_PREROLL_BYTES);
               int timescale = init.timescale() > 0 ? init.timescale() : 16000;

               for (int attempt = 0; attempt < SEEK_MAX_ATTEMPTS; attempt++) {
                  Fmp4VideoStreamSeeker.ChunkRange range = this.openRange(rangeStart);
                  lastRange = range.stream();
                  Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(
                     range.stream(), targetSeconds, timescale, MOOF_SCAN_BYTES, TARGET_EPSILON_SECONDS, CLOSE_FRAGMENT_SECONDS
                  );
                  if (probe == null) {
                     this.closeQuietly(lastRange);
                     lastRange = null;
                     long nextStart = Math.min(contentLength - 1L, rangeStart + MOOF_SCAN_BYTES);
                     if (attempt + 1 >= SEEK_MAX_ATTEMPTS || nextStart <= rangeStart) {
                        return null;
                     }

                     rangeStart = nextStart;
                  } else {
                     Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
                     long absoluteMoofOffset = rangeStart + candidate.offset();
                     if (attempt + 1 < SEEK_MAX_ATTEMPTS && Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds, TARGET_EPSILON_SECONDS)) {
                        long nextStart = Math.max((long)init.bytes().length, absoluteMoofOffset - MOOF_SCAN_BYTES - SEEK_PREROLL_BYTES);
                        if (nextStart < rangeStart) {
                           this.closeQuietly(lastRange);
                           lastRange = null;
                           rangeStart = nextStart;
                           continue;
                        }
                     }

                     if (attempt + 1 < SEEK_MAX_ATTEMPTS
                        && Fmp4RangeSeekSupport.shouldRetry(candidate, targetSeconds, TARGET_EPSILON_SECONDS, CLOSE_FRAGMENT_SECONDS)) {
                        long nextStart = Fmp4RangeSeekSupport.nextRangeStart(
                           candidate, targetSeconds, durationSeconds, contentLength, absoluteMoofOffset, init.bytes().length, SEEK_PREROLL_BYTES
                        );
                        if (Math.abs(nextStart - rangeStart) > SEEK_PREROLL_BYTES) {
                           this.closeQuietly(lastRange);
                           lastRange = null;
                           rangeStart = nextStart;
                           continue;
                        }
                     }

                     float residualSeconds = Fmp4RangeSeekSupport.residualSeconds(targetSeconds, candidate, durationSeconds, contentLength, absoluteMoofOffset);
                     residualSeconds = Math.max(0.0F, residualSeconds - (targetSeconds - playbackSeconds));
                     InputStream probePrefix = this.trackInput(
                        new ByteArrayInputStream(probe.bytes(), candidate.offset(), probe.bytes().length - candidate.offset())
                     );
                     InputStream tail = this.trackInput(new SequenceInputStream(probePrefix, range.stream()));
                     InputStream initPrefix = this.trackInput(new ByteArrayInputStream(init.bytes()));
                     InputStream combined = this.trackInput(new SequenceInputStream(initPrefix, tail));
                     lastRange = null;
                     LOGGER.debug(
                        "视频fMP4 RangeSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} probe={}ms host={}",
                        new Object[]{
                           playbackSeconds,
                           candidate.fragmentSeconds(),
                           residualSeconds,
                           this.timelineStartOffsetMillis / 1000.0,
                           absoluteMoofOffset,
                           contentLength,
                           (System.nanoTime() - seekStartNanos) / 1000000L,
                           this.videoUrl.getHost()
                        }
                     );
                     return new Fmp4VideoStreamSeeker.StreamStart(
                        combined, residualSeconds, Double.isNaN(candidate.fragmentSeconds()) ? 0.0 : candidate.fragmentSeconds()
                     );
                  }
               }

               return null;
            }
         }
      } catch (RuntimeException | IOException var30) {
         LOGGER.debug("Native video fMP4 range seek unavailable: {}", var30.getMessage());
         this.closeQuietly(lastRange);
         return null;
      }
   }

   private Fmp4VideoStreamSeeker.StreamStart tryOpenSidxSeek(
      Fmp4RangeSeekSupport.InitSegment init, float targetSeconds, float playbackSeconds, long seekStartNanos
   ) {
      Fmp4VideoStreamSeeker.SegmentBaseInfo info = segmentBaseInfo(this.videoUrl.toString());
      if (info == null) {
         return null;
      } else {
         InputStream rangeStream = null;

         Object fragmentSeconds;
         try {
            Fmp4VideoStreamSeeker.SeekRangeBytes sidxRange = this.readSeekMetadataRange(info.indexStart(), info.indexEnd());
            Fmp4RangeSeekSupport.SidxIndex sidx = Fmp4RangeSeekSupport.parseSidx(sidxRange.bytes(), info.indexStart());
            if (sidx == null || sidx.entries().isEmpty()) {
               return null;
            }

            Fmp4RangeSeekSupport.SidxEntry selected = selectVideoSidxEntry(sidx.entries(), targetSeconds);
            if (selected == null) {
               selected = sidx.entries().get(0);
            }

            URL seekUrl = this.sourceUrlOrVideoUrl(sidxRange.sourceUrl());
            Fmp4VideoStreamSeeker.ChunkRange range = this.openRange(seekUrl, selected.byteStart());
            rangeStream = range.stream();
            Fmp4RangeSeekSupport.MoofProbe probe = Fmp4RangeSeekSupport.readMoofProbe(
               rangeStream, targetSeconds, init.timescale() > 0 ? init.timescale() : 16000, MOOF_SCAN_BYTES, TARGET_EPSILON_SECONDS, CLOSE_FRAGMENT_SECONDS
            );
            if (probe == null) {
               return null;
            }

            Fmp4RangeSeekSupport.MoofCandidate candidate = probe.candidate();
            if (!Fmp4RangeSeekSupport.isAfterTargetCandidate(candidate, targetSeconds, TARGET_EPSILON_SECONDS)) {
               double fragmentSecondsx = !Double.isNaN(candidate.fragmentSeconds()) ? candidate.fragmentSeconds() : selected.timeSeconds();
               float residualSeconds = (float)Math.max(0.0, targetSeconds - fragmentSecondsx);
               residualSeconds = Math.max(0.0F, residualSeconds - (targetSeconds - playbackSeconds));
               InputStream probePrefix = this.trackInput(new ByteArrayInputStream(probe.bytes(), candidate.offset(), probe.bytes().length - candidate.offset()));
               InputStream tail = this.trackInput(new SequenceInputStream(probePrefix, rangeStream));
               InputStream initPrefix = this.trackInput(new ByteArrayInputStream(init.bytes()));
               InputStream combined = this.trackInput(new SequenceInputStream(initPrefix, tail));
               rangeStream = null;
               LOGGER.debug(
                  "视频fMP4 SidxSeek: target={}s fragment={}s residual={}s timelineStart={}s byte={} totalBytes={} probe={}ms host={}",
                  new Object[]{
                     playbackSeconds,
                     fragmentSecondsx,
                     residualSeconds,
                     this.timelineStartOffsetMillis / 1000.0,
                     selected.byteStart(),
                     init.contentLength(),
                     (System.nanoTime() - seekStartNanos) / 1000000L,
                     seekUrl.getHost()
                  }
               );
               LOGGER.debug(
                  "视频fMP4 SidxSeek 选择: target={}s selectedFragment={}s startsWithSap={} byte={}",
                  new Object[]{playbackSeconds, fragmentSecondsx, selected.startsWithSap(), selected.byteStart()}
               );
               return new Fmp4VideoStreamSeeker.StreamStart(combined, residualSeconds, fragmentSecondsx);
            }

            LOGGER.debug(
               "视频fMP4 SidxSeek 命中目标之后 fragment，回退 Moof RangeSeek: target={}s fragment={}s byte={}",
               new Object[]{targetSeconds, candidate.fragmentSeconds(), selected.byteStart()}
            );
            fragmentSeconds = null;
         } catch (RuntimeException | IOException var26) {
            LOGGER.debug("Native video fMP4 sidx seek unavailable: {}", var26.getMessage());
            return null;
         } finally {
            this.closeQuietly(rangeStream);
         }

         return (Fmp4VideoStreamSeeker.StreamStart)fragmentSeconds;
      }
   }

   private static Fmp4RangeSeekSupport.SidxEntry selectVideoSidxEntry(List<Fmp4RangeSeekSupport.SidxEntry> entries, float targetSeconds) {
      Fmp4RangeSeekSupport.SidxEntry selected = null;
      boolean sawEntryBeforeTarget = false;

      for (Fmp4RangeSeekSupport.SidxEntry entry : entries) {
         if (entry.timeSeconds() > targetSeconds + 0.05) {
            break;
         }

         sawEntryBeforeTarget = true;
         if (entry.startsWithSap()) {
            selected = entry;
         }
      }

      if (selected == null && sawEntryBeforeTarget) {
         LOGGER.warn("视频 fMP4 SIDX 未找到目标前 SAP fragment，兼容使用最近 fragment: target={}s", targetSeconds);

         for (Fmp4RangeSeekSupport.SidxEntry entry : entries) {
            if (entry.timeSeconds() > targetSeconds + 0.05) {
               break;
            }

            selected = entry;
         }
      }

      return selected;
   }

   private Fmp4RangeSeekSupport.InitSegment readInitSegment() throws IOException {
      Fmp4VideoStreamSeeker.SegmentBaseInfo info = segmentBaseInfo(this.videoUrl.toString());
      if (info != null) {
         Fmp4VideoStreamSeeker.SeekRangeBytes initRange = this.readSeekMetadataRange(info.initStart(), info.initEnd());
         Fmp4RangeSeekSupport.InitSegment init = extractInitSegment(initRange.bytes(), initRange.totalLength());
         if (init != null) {
            return init;
         }

         LOGGER.debug("视频 fMP4 segment_base init 不可用，回退通用探测: range={}-{} host={}", new Object[]{info.initStart(), info.initEnd(), initRange.sourceHost()});
      }

      HttpRangeClient.CdnResponse response = this.http.getRange(this.videoUrl, 0L, INIT_PROBE_BYTES - 1L);
      InputStream body = this.trackInput(response.body());

      try (Fmp4VideoStreamSeeker.TrackedInputLease lease = new Fmp4VideoStreamSeeker.TrackedInputLease(body)) {
         InputStream leasedBody = lease.stream();
         int status = response.statusCode();
         if (status != 206 && status != 200) {
            throw new IOException("HTTP " + status + " while probing fMP4 init segment");
         } else {
            long contentLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
            ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];

            while (prefix.size() < INIT_PROBE_BYTES) {
               int request = Math.min(buffer.length, INIT_PROBE_BYTES - prefix.size());
               int n = leasedBody.read(buffer, 0, request);
               if (n >= 0) {
                  if (n == 0) {
                     continue;
                  }

                  prefix.write(buffer, 0, n);
                  Fmp4RangeSeekSupport.InitSegment init = extractInitSegment(prefix.toByteArray(), contentLength);
                  if (init == null) {
                     continue;
                  }

                  return init;
               }
               break;
            }

            throw new IOException("unable to read complete fMP4 init segment");
         }
      }
   }

   private static Fmp4RangeSeekSupport.InitSegment extractInitSegment(byte[] bytes, long contentLength) {
      return Fmp4RangeSeekSupport.extractInitSegment(bytes, contentLength, (moovPayload, moov) -> {
         int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(moovPayload);
         return videoTimescale > 0 ? videoTimescale : moov.timescale;
      });
   }

   private Fmp4VideoStreamSeeker.SeekRangeBytes readSeekMetadataRange(long start, long endInclusive) throws IOException {
      Fmp4SeekRangeCache.CachedRange cached = Fmp4SeekRangeCache.get(this.videoUrl, start, endInclusive);
      if (cached != null) {
         return new Fmp4VideoStreamSeeker.SeekRangeBytes(cached.bytes(), cached.totalLength(), cached.sourceHost(), cached.sourceUrl());
      } else {
         List<URL> candidates = CdnUrlFallbacks.candidates(this.videoUrl);
         if (candidates.size() > 1 && RANGE_RACE_MAX_CANDIDATES > 1) {
            Fmp4VideoStreamSeeker.SeekRangeBytes raced = this.readSeekMetadataRangeRace(candidates, start, endInclusive);
            if (raced != null) {
               Fmp4SeekRangeCache.put(this.videoUrl, start, endInclusive, raced.bytes(), raced.totalLength(), raced.sourceHost(), raced.sourceUrl());
               return raced;
            }
         }

         Fmp4VideoStreamSeeker.SeekRangeBytes result = this.readSeekMetadataRangeSingle(this.videoUrl, start, endInclusive, true);
         Fmp4SeekRangeCache.put(this.videoUrl, start, endInclusive, result.bytes(), result.totalLength(), result.sourceHost(), result.sourceUrl());
         return result;
      }
   }

   private Fmp4VideoStreamSeeker.SeekRangeBytes readSeekMetadataRangeRace(List<URL> candidates, long start, long endInclusive) throws IOException {
      int count = Math.min(RANGE_RACE_MAX_CANDIDATES, candidates.size());
      CompletableFuture<Fmp4VideoStreamSeeker.SeekRangeBytes> first = new CompletableFuture<>();
      List<Future<?>> tasks = new ArrayList<>(count);
      AtomicInteger failures = new AtomicInteger();

      for (int i = 0; i < count; i++) {
         URL candidate = candidates.get(i);
         tasks.add(RANGE_RACE_EXECUTOR.submit(() -> {
            if (!first.isDone()) {
               try {
                  first.complete(this.readSeekMetadataRangeSingle(candidate, start, endInclusive, false));
               } catch (IOException var10x) {
                  if (failures.incrementAndGet() >= count) {
                     first.completeExceptionally(var10x);
                  }
               }
            }
         }));
      }

      Object var24;
      try {
         Fmp4VideoStreamSeeker.SeekRangeBytes winner = first.get(RANGE_RACE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
         BiliCdnSelector.recordSuccess(winner.sourceUrl());
         LOGGER.debug(
            "视频小范围 CDN 赛马完成: range={}-{} bytes={} total={} host={}",
            new Object[]{start, endInclusive, winner.bytes().length, winner.totalLength(), winner.sourceHost()}
         );
         return winner;
      } catch (TimeoutException var18) {
         LOGGER.debug("视频小范围 CDN 赛马超时，回退串行读取: range={}-{} timeout={}ms candidates={}", new Object[]{start, endInclusive, RANGE_RACE_TIMEOUT_MILLIS, count});
         var24 = null;
      } catch (InterruptedException var19) {
         Thread.currentThread().interrupt();
         throw new IOException("interrupted while racing video seek metadata", var19);
      } catch (ExecutionException var20) {
         Throwable cause = var20.getCause();
         if (cause instanceof IOException io) {
            throw io;
         }

         throw new IOException("video seek metadata race failed", cause);
      } finally {
         tasks.forEach(task -> task.cancel(true));
      }

      return (Fmp4VideoStreamSeeker.SeekRangeBytes)var24;
   }

   private Fmp4VideoStreamSeeker.SeekRangeBytes readSeekMetadataRangeSingle(URL source, long start, long endInclusive, boolean allowCdnFallback) throws IOException {
      HttpRangeClient.CdnResponse response = allowCdnFallback
         ? this.http.getRange(source, start, endInclusive)
         : this.http.getRangeDirect(source, start, endInclusive);
      InputStream body = this.trackInput(response.body());
      long started = System.currentTimeMillis();

      Fmp4VideoStreamSeeker.SeekRangeBytes var20;
      try (Fmp4VideoStreamSeeker.TrackedInputLease lease = new Fmp4VideoStreamSeeker.TrackedInputLease(body)) {
         InputStream leasedBody = lease.stream();
         URL actualSource = response.sourceUrl();
         int status = response.statusCode();
         if (status != 206 && (status != 200 || start != 0L)) {
            throw new IOException("HTTP " + status + " while reading fMP4 seek metadata");
         }

         long expected = Math.max(1L, endInclusive - start + 1L);
         byte[] bytes = readAllBytes(leasedBody, expected);
         if (bytes.length == 0) {
            throw new IOException("empty fMP4 seek metadata range");
         }

         if (bytes.length < expected) {
            CdnHealthTracker.recordFailure(actualSource, CdnHealthTracker.FailureKind.SHORT_READ);
            throw new IOException("short fMP4 seek metadata range from " + actualSource.getHost() + ": expected=" + expected + " actual=" + bytes.length);
         }

         long totalLength = response.totalLength() > 0L ? response.totalLength() : response.contentLength();
         CdnHealthTracker.recordSuccess(actualSource, System.currentTimeMillis() - started, bytes.length);
         if (allowCdnFallback) {
            BiliCdnSelector.recordSuccess(actualSource.toString());
         }

         var20 = new Fmp4VideoStreamSeeker.SeekRangeBytes(bytes, totalLength, actualSource.getHost(), actualSource.toString());
      }

      return var20;
   }

   private Fmp4VideoStreamSeeker.ChunkRange openRange(long start) throws IOException {
      return this.openRange(this.videoUrl, start);
   }

   private Fmp4VideoStreamSeeker.ChunkRange openRange(URL source, long start) throws IOException {
      return new Fmp4VideoStreamSeeker.ChunkRange(this.trackInput(new ChunkPrefetchInputStream(source, start)));
   }

   private InputStream trackInput(InputStream stream) throws IOException {
      InputStream tracked = this.trackedInputs.track(stream);
      if (this.closed.get()) {
         this.trackedInputs.beginClose();
         throw new IOException("native video decoder closed");
      } else {
         return tracked;
      }
   }

   private void closeQuietly(InputStream stream) {
      if (stream != null) {
         this.trackedInputs.closeAsync(stream);
      }
   }

   private static byte[] readAllBytes(InputStream stream, long maxBytes) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(maxBytes, 1048576L));
      byte[] buffer = new byte[65536];
      long remaining = maxBytes;

      while (remaining > 0L) {
         int n = stream.read(buffer, 0, (int)Math.min((long)buffer.length, remaining));
         if (n < 0) {
            break;
         }

         if (n != 0) {
            out.write(buffer, 0, n);
            remaining -= n;
         }
      }

      return out.toByteArray();
   }

   private URL sourceUrlOrVideoUrl(String sourceUrl) {
      if (sourceUrl != null && !sourceUrl.isBlank()) {
         try {
            return URI.create(sourceUrl).toURL();
         } catch (Exception var3) {
            return this.videoUrl;
         }
      } else {
         return this.videoUrl;
      }
   }

   private static float seekTargetSeconds(float playbackSeconds, double durationSeconds) {
      if (SEEK_LEAD_SECONDS <= 0.0) {
         return playbackSeconds;
      } else {
         double upperBound = durationSeconds > 0.0 ? Math.max(0.0, durationSeconds - 1.0) : playbackSeconds + SEEK_LEAD_SECONDS;
         return (float)Math.max(0.0, Math.min(upperBound, playbackSeconds + SEEK_LEAD_SECONDS));
      }
   }

   private static Fmp4VideoStreamSeeker.SegmentBaseInfo segmentBaseInfo(String url) {
      Fmp4VideoStreamSeeker.SegmentBaseInfo info = SEGMENT_BASE_BY_URL.get(url);
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
      SEGMENT_BASE_BY_URL.entrySet()
         .removeIf(entryx -> now - ((Fmp4VideoStreamSeeker.SegmentBaseInfo)entryx.getValue()).createdAtMillis() > SEGMENT_BASE_TTL_MILLIS);
      int maxEntries = Math.max(1, MAX_SEGMENT_BASE_ENTRIES);

      while (SEGMENT_BASE_BY_URL.size() > maxEntries) {
         String oldestKey = null;
         long oldestCreatedAt = Long.MAX_VALUE;

         for (Entry<String, Fmp4VideoStreamSeeker.SegmentBaseInfo> entry : SEGMENT_BASE_BY_URL.entrySet()) {
            if (entry.getValue().createdAtMillis() < oldestCreatedAt) {
               oldestCreatedAt = entry.getValue().createdAtMillis();
               oldestKey = entry.getKey();
            }
         }

         if (oldestKey == null) {
            return;
         }

         SEGMENT_BASE_BY_URL.remove(oldestKey);
      }
   }

   private record ChunkRange(InputStream stream) {
   }

   private record SeekRangeBytes(byte[] bytes, long totalLength, String sourceHost, String sourceUrl) {
   }

   private record SegmentBaseInfo(long initStart, long initEnd, long indexStart, long indexEnd, long createdAtMillis) {
   }

   record StreamStart(InputStream stream, float residualSeconds, double fragmentSeconds) {
   }

   private final class TrackedInputLease implements AutoCloseable {
      private final InputStream stream;

      private TrackedInputLease(InputStream stream) {
         this.stream = stream;
      }

      private InputStream stream() {
         return this.stream;
      }

      @Override
      public void close() {
         Fmp4VideoStreamSeeker.this.trackedInputs.closeAsync(this.stream);
      }
   }
}
