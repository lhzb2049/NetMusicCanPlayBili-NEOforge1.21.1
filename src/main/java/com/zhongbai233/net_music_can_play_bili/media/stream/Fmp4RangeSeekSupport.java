package com.zhongbai233.net_music_can_play_bili.media.stream;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Fmp4RangeSeekSupport {
   private Fmp4RangeSeekSupport() {
   }

   public static Fmp4RangeSeekSupport.InitSegment extractInitSegment(byte[] prefix, long contentLength, Fmp4RangeSeekSupport.TimescaleResolver resolver) {
      int pos = 0;

      while (pos + 8 <= prefix.length) {
         Fmp4RangeSeekSupport.Mp4Box box = readCompleteMp4Box(prefix, pos, prefix.length);
         if (box == null) {
            return null;
         }

         if (isBoxType(prefix, pos + 4, 'm', 'o', 'o', 'v')) {
            byte[] initBytes = Arrays.copyOf(prefix, pos + (int)box.size());
            byte[] moovPayload = Arrays.copyOfRange(prefix, pos + box.headerSize(), pos + (int)box.size());
            Fmp4ToMp4Converter.ParseResult moov = Fmp4ToMp4Converter.parseMoov(moovPayload);
            int timescale = resolver != null ? resolver.resolve(moovPayload, moov) : moov.timescale;
            return new Fmp4RangeSeekSupport.InitSegment(initBytes, contentLength, timescale);
         }

         pos += (int)box.size();
      }

      return null;
   }

   public static Fmp4RangeSeekSupport.MoofProbe readMoofProbe(
      InputStream range, float targetSeconds, int timescale, int maxScanBytes, double targetEpsilonSeconds, double closeFragmentSeconds
   ) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[262144];
      Fmp4RangeSeekSupport.MoofCandidate best = null;

      while (out.size() < maxScanBytes) {
         int request = Math.min(buffer.length, maxScanBytes - out.size());
         int n = range.read(buffer, 0, request);
         if (n < 0) {
            break;
         }

         if (n != 0) {
            out.write(buffer, 0, n);
            byte[] data = out.toByteArray();
            best = findBestMoofCandidate(data, data.length, targetSeconds, timescale, targetEpsilonSeconds);
            if (isCloseCandidate(best, targetSeconds, targetEpsilonSeconds, closeFragmentSeconds)) {
               return new Fmp4RangeSeekSupport.MoofProbe(data, best);
            }
         }
      }

      byte[] data = out.toByteArray();
      best = best != null ? best : findBestMoofCandidate(data, data.length, targetSeconds, timescale, targetEpsilonSeconds);
      return best != null ? new Fmp4RangeSeekSupport.MoofProbe(data, best) : null;
   }

   public static boolean shouldRetry(
      Fmp4RangeSeekSupport.MoofCandidate candidate, float targetSeconds, double targetEpsilonSeconds, double closeFragmentSeconds
   ) {
      if (candidate != null && !Double.isNaN(candidate.fragmentSeconds())) {
         double delta = targetSeconds - candidate.fragmentSeconds();
         return delta < -targetEpsilonSeconds || delta > closeFragmentSeconds;
      } else {
         return false;
      }
   }

   public static boolean isAfterTargetCandidate(Fmp4RangeSeekSupport.MoofCandidate candidate, float targetSeconds, double targetEpsilonSeconds) {
      return candidate != null && !Double.isNaN(candidate.fragmentSeconds()) && candidate.fragmentSeconds() > targetSeconds + targetEpsilonSeconds;
   }

   public static long nextRangeStart(
      Fmp4RangeSeekSupport.MoofCandidate candidate,
      float targetSeconds,
      double durationSeconds,
      long contentLength,
      long absoluteMoofOffset,
      int initLength,
      long prerollBytes
   ) {
      double bytesPerSecond = contentLength / Math.max(1.0, durationSeconds);
      double deltaSeconds = targetSeconds - candidate.fragmentSeconds();
      long adjusted = absoluteMoofOffset + Math.round(deltaSeconds * bytesPerSecond) - prerollBytes;
      return Math.max((long)initLength, Math.min(contentLength - 1L, adjusted));
   }

   public static float residualSeconds(
      float targetSeconds, Fmp4RangeSeekSupport.MoofCandidate candidate, double durationSeconds, long contentLength, long absoluteMoofOffset
   ) {
      double fragmentSeconds = candidate.fragmentSeconds();
      if (Double.isNaN(fragmentSeconds) && contentLength > 0L && durationSeconds > 0.0) {
         fragmentSeconds = durationSeconds * ((double)absoluteMoofOffset / contentLength);
      }

      return Double.isNaN(fragmentSeconds) ? targetSeconds : (float)Math.max(0.0, Math.min((double)targetSeconds, targetSeconds - fragmentSeconds));
   }

   public static Fmp4RangeSeekSupport.SidxIndex parseSidx(byte[] data, long absoluteStart) {
      int sidxOffset = -1;
      Fmp4RangeSeekSupport.Mp4Box sidxBox = null;
      int pos = 0;

      while (pos + 8 <= data.length) {
         Fmp4RangeSeekSupport.Mp4Box box = readCompleteMp4Box(data, pos, data.length);
         if (box == null || box.size() <= 0L || box.size() > 2147483647L) {
            break;
         }

         if (isBoxType(data, pos + 4, 's', 'i', 'd', 'x')) {
            sidxOffset = pos;
            sidxBox = box;
            break;
         }

         pos += (int)box.size();
      }

      if (sidxOffset >= 0 && sidxBox != null) {
         pos = sidxOffset + sidxBox.headerSize();
         int end = sidxOffset + (int)sidxBox.size();
         if (pos + 12 > end) {
            return null;
         } else {
            int version = data[pos] & 255;
            pos += 4;
            pos += 4;
            long timescale = readUInt32(data, pos);
            pos += 4;
            long earliestPresentationTime;
            long firstOffset;
            if (version == 0) {
               if (pos + 8 > end) {
                  return null;
               }

               earliestPresentationTime = readUInt32(data, pos);
               pos += 4;
               firstOffset = readUInt32(data, pos);
               pos += 4;
            } else {
               if (pos + 16 > end) {
                  return null;
               }

               earliestPresentationTime = readUInt64(data, pos);
               pos += 8;
               firstOffset = readUInt64(data, pos);
               pos += 8;
            }

            if (pos + 4 <= end && timescale > 0L) {
               pos += 2;
               int referenceCount = (data[pos] & 255) << 8 | data[pos + 1] & 255;
               pos += 2;
               long currentTime = earliestPresentationTime;
               long currentByte = absoluteStart + sidxOffset + sidxBox.size() + firstOffset;
               List<Fmp4RangeSeekSupport.SidxEntry> entries = new ArrayList<>();

               for (int i = 0; i < referenceCount && pos + 12 <= end; i++) {
                  long ref = readUInt32(data, pos);
                  pos += 4;
                  boolean referenceType = (ref & 2147483648L) != 0L;
                  long size = ref & 2147483647L;
                  long duration = readUInt32(data, pos);
                  pos += 4;
                  long sap = readUInt32(data, pos);
                  pos += 4;
                  boolean startsWithSap = (sap & 2147483648L) != 0L;
                  if (!referenceType && size > 0L) {
                     entries.add(new Fmp4RangeSeekSupport.SidxEntry((double)currentTime / timescale, currentByte, currentByte + size - 1L, startsWithSap));
                  }

                  currentTime += duration;
                  currentByte += size;
               }

               return entries.isEmpty() ? null : new Fmp4RangeSeekSupport.SidxIndex(timescale, entries);
            } else {
               return null;
            }
         }
      } else {
         return null;
      }
   }

   public static Fmp4RangeSeekSupport.Mp4Box readCompleteMp4Box(byte[] data, int offset, int length) {
      if (offset >= 0 && offset + 8 <= length) {
         long size = readUInt32(data, offset);
         int headerSize = 8;
         if (size == 1L) {
            if (offset + 16 > length) {
               return null;
            }

            size = readUInt64(data, offset + 8);
            headerSize = 16;
         }

         return size >= headerSize && size <= length - offset ? new Fmp4RangeSeekSupport.Mp4Box(size, headerSize) : null;
      } else {
         return null;
      }
   }

   public static long readUInt32(byte[] data, int offset) {
      return (data[offset] & 255L) << 24 | (data[offset + 1] & 255L) << 16 | (data[offset + 2] & 255L) << 8 | data[offset + 3] & 255L;
   }

   public static long readUInt64(byte[] data, int offset) {
      long value = 0L;

      for (int i = 0; i < 8; i++) {
         value = value << 8 | data[offset + i] & 255L;
      }

      return value;
   }

   public static boolean isBoxType(byte[] data, int offset, char a, char b, char c, char d) {
      return offset >= 0
         && offset + 4 <= data.length
         && data[offset] == (byte)a
         && data[offset + 1] == (byte)b
         && data[offset + 2] == (byte)c
         && data[offset + 3] == (byte)d;
   }

   private static boolean isCloseCandidate(
      Fmp4RangeSeekSupport.MoofCandidate candidate, float targetSeconds, double targetEpsilonSeconds, double closeFragmentSeconds
   ) {
      if (candidate != null && !Double.isNaN(candidate.fragmentSeconds())) {
         double delta = targetSeconds - candidate.fragmentSeconds();
         return delta >= -targetEpsilonSeconds && delta <= closeFragmentSeconds;
      } else {
         return false;
      }
   }

   private static Fmp4RangeSeekSupport.MoofCandidate findBestMoofCandidate(
      byte[] probe, int length, float targetSeconds, int timescale, double targetEpsilonSeconds
   ) {
      Fmp4RangeSeekSupport.MoofCandidate first = null;
      Fmp4RangeSeekSupport.MoofCandidate bestBeforeTarget = null;

      for (int i = 0; i + 8 <= length; i++) {
         if (isBoxType(probe, i + 4, 'm', 'o', 'o', 'f')) {
            Fmp4RangeSeekSupport.MoofCandidate candidate = readMoofCandidate(probe, i, length, timescale);
            if (candidate != null) {
               if (first == null) {
                  first = candidate;
               }

               if (!Double.isNaN(candidate.fragmentSeconds())) {
                  if (candidate.fragmentSeconds() <= targetSeconds + targetEpsilonSeconds) {
                     bestBeforeTarget = candidate;
                  } else if (bestBeforeTarget != null) {
                     break;
                  }
               }

               Fmp4RangeSeekSupport.Mp4Box box = readCompleteMp4Box(probe, i, length);
               if (box != null && box.size() <= 2147483647L) {
                  i += Math.max(0, (int)box.size() - 1);
               }
            }
         }
      }

      return bestBeforeTarget != null ? bestBeforeTarget : first;
   }

   private static Fmp4RangeSeekSupport.MoofCandidate readMoofCandidate(byte[] probe, int offset, int length, int timescale) {
      Fmp4RangeSeekSupport.Mp4Box box = readCompleteMp4Box(probe, offset, length);
      if (box != null && box.size() <= 2147483647L && box.size() >= box.headerSize()) {
         byte[] moofPayload = Arrays.copyOfRange(probe, offset + box.headerSize(), offset + (int)box.size());
         Fmp4ToMp4Converter.ParseResult moof = Fmp4ToMp4Converter.parseMoof(moofPayload);
         if (moof.sampleCount <= 0 && moof.baseMediaDecodeTime < 0L) {
            return null;
         } else {
            double fragmentSeconds = moof.baseMediaDecodeTime >= 0L && timescale > 0 ? (double)moof.baseMediaDecodeTime / timescale : Double.NaN;
            return new Fmp4RangeSeekSupport.MoofCandidate(offset, fragmentSeconds);
         }
      } else {
         return null;
      }
   }

   public record InitSegment(byte[] bytes, long contentLength, int timescale) {
   }

   public record MoofCandidate(int offset, double fragmentSeconds) {
   }

   public record MoofProbe(byte[] bytes, Fmp4RangeSeekSupport.MoofCandidate candidate) {
   }

   public record Mp4Box(long size, int headerSize) {
   }

   public record SidxEntry(double timeSeconds, long byteStart, long byteEnd, boolean startsWithSap) {
   }

   public record SidxIndex(long timescale, List<Fmp4RangeSeekSupport.SidxEntry> entries) {
   }

   @FunctionalInterface
   public interface TimescaleResolver {
      int resolve(byte[] var1, Fmp4ToMp4Converter.ParseResult var2);
   }
}
