package com.zhongbai233.net_music_can_play_bili.media;

import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import org.slf4j.Logger;

public final class Fmp4ToMp4Converter {
   private static final int TRUN_DATA_OFFSET = 1;
   private static final int TRUN_FIRST_SAMPLE_FLAGS = 4;
   private static final int TRUN_SAMPLE_DURATION = 256;
   private static final int TRUN_SAMPLE_SIZE = 512;
   private static final int TRUN_SAMPLE_FLAGS = 1024;
   private static final int TRUN_SAMPLE_COMP_TIME = 2048;
   private static final int TFHD_BASE_DATA_OFFSET = 1;
   private static final int TFHD_SAMPLE_DESCRIPTION_INDEX = 2;
   private static final int TFHD_DEFAULT_SAMPLE_DURATION = 8;
   private static final int TFHD_DEFAULT_SAMPLE_SIZE = 16;
   private static final int TFHD_DEFAULT_SAMPLE_FLAGS = 32;
   private static final byte[] DEFAULT_ASC = new byte[]{17, -112};

   private Fmp4ToMp4Converter() {
   }

   private static Logger logger() {
      return Fmp4ToMp4Converter.LoggerHolder.INSTANCE;
   }

   public static byte[] convertToStandardMp4(byte[] fmp4Data) throws IOException {
      ByteBuffer buf = ByteBuffer.wrap(fmp4Data).order(ByteOrder.BIG_ENDIAN);
      byte[] asc = null;
      int audioTimescale = 48000;
      List<Integer> allSizes = new ArrayList<>();
      List<Integer> allDurs = new ArrayList<>();
      List<byte[]> mdatChunks = new ArrayList<>();

      while (buf.remaining() >= 8) {
         Fmp4ToMp4Converter.BoxHeader box = readBoxHeader(buf);
         if (box == null) {
            break;
         }

         long dataSize = box.dataSize();
         if ("moov".equals(box.type)) {
            byte[] moovData = new byte[(int)dataSize];
            buf.get(moovData);
            Fmp4ToMp4Converter.ParseResult pr = parseMoov(moovData);
            if (pr.asc != null) {
               asc = pr.asc;
            }

            if (pr.timescale > 0) {
               audioTimescale = pr.timescale;
            }
         } else if ("moof".equals(box.type)) {
            byte[] moofData = new byte[(int)dataSize];
            buf.get(moofData);
            Fmp4ToMp4Converter.ParseResult prx = parseMoof(moofData);
            if (prx.sampleSizes != null) {
               for (int i = 0; i < prx.sampleSizes.length; i++) {
                  int sz = prx.sampleSizes[i];
                  allSizes.add(sz);
                  allDurs.add(sampleDuration(prx, i, audioTimescale));
               }
            } else if (prx.defaultSampleSize > 0 && prx.sampleCount > 0) {
               for (int i = 0; i < prx.sampleCount; i++) {
                  allSizes.add(prx.defaultSampleSize);
                  allDurs.add(sampleDuration(prx, i, audioTimescale));
               }
            }
         } else if ("mdat".equals(box.type)) {
            if (dataSize > 0L) {
               byte[] mdat = new byte[(int)Math.min(dataSize, 2147483647L)];
               buf.get(mdat);
               mdatChunks.add(mdat);
            }
         } else {
            buf.position(buf.position() + (int)Math.min(dataSize, (long)buf.remaining()));
         }
      }

      if (asc == null) {
         logger().warn("AAC ASC 提取失败，回退到默认 ASC (48000Hz 立体声)。低品质流可能产生噪音。");
         asc = DEFAULT_ASC;
      }

      ByteArrayOutputStream raw = new ByteArrayOutputStream();

      for (byte[] c : mdatChunks) {
         raw.write(c);
      }

      byte[] full = raw.toByteArray();
      int[] sizes = allSizes.stream().mapToInt(ix -> ix).toArray();
      int[] durs = allDurs.stream().mapToInt(ix -> ix).toArray();
      logger().debug("[Fmp4ToAdts] MP4转换: {}帧, {}B AAC", sizes.length, full.length);
      return Fmp4AacMp4Muxer.build(asc, sizes, durs, full, audioTimescale);
   }

   private static int sampleDuration(Fmp4ToMp4Converter.ParseResult pr, int index, int fallbackTimescale) {
      if (pr.sampleDurations != null && index >= 0 && index < pr.sampleDurations.length && pr.sampleDurations[index] > 0L) {
         return (int)Math.min(2147483647L, pr.sampleDurations[index]);
      } else {
         return pr.defaultSampleDuration > 0L ? (int)Math.min(2147483647L, pr.defaultSampleDuration) : 1024;
      }
   }

   public static AudioFormat sniffAudioFormat(byte[] partialFmp4) {
      ByteBuffer buf = ByteBuffer.wrap(partialFmp4).order(ByteOrder.BIG_ENDIAN);

      while (buf.remaining() >= 8) {
         Fmp4ToMp4Converter.BoxHeader box = readBoxHeader(buf);
         if (box == null) {
            break;
         }

         long dataSize = box.dataSize();
         if ("moov".equals(box.type) && dataSize > 0L && dataSize <= buf.remaining()) {
            byte[] moovData = new byte[(int)dataSize];
            buf.get(moovData);
            Fmp4ToMp4Converter.ParseResult pr = parseMoov(moovData);
            if (pr.asc != null) {
               return ascToAudioFormat(pr.asc);
            }
            break;
         }

         int skip = (int)Math.min(dataSize, (long)buf.remaining());
         buf.position(buf.position() + skip);
      }

      return null;
   }

   private static byte[] extractDfLaFromFlacStsd(byte[] flacEntry) {
      if (flacEntry.length < 36) {
         return null;
      } else {
         int pos = 28;

         while (pos + 8 <= flacEntry.length) {
            int boxSize = readIntBE(flacEntry, pos);
            String boxType = new String(flacEntry, pos + 4, 4);
            if (boxSize < 8 || pos + boxSize > flacEntry.length) {
               break;
            }

            if ("dfLa".equals(boxType) && boxSize >= 12) {
               byte[] dfLa = new byte[boxSize - 8];
               System.arraycopy(flacEntry, pos + 8, dfLa, 0, dfLa.length);
               return dfLa;
            }

            pos += boxSize;
         }

         return null;
      }
   }

   public static AudioFormat flacDfLaToAudioFormat(byte[] dfLa) {
      if (dfLa != null && dfLa.length >= 4) {
         int pos = 4;

         while (pos + 4 <= dfLa.length) {
            int header = (dfLa[pos] & 255) << 24 | (dfLa[pos + 1] & 255) << 16 | (dfLa[pos + 2] & 255) << 8 | dfLa[pos + 3] & 255;
            boolean lastBlock = (header & -2147483648) != 0;
            int blockType = header >> 24 & 127;
            int blockLen = header & 16777215;
            pos += 4;
            if (pos + blockLen > dfLa.length) {
               break;
            }

            if (blockType == 0 && blockLen >= 18) {
               int b10 = dfLa[pos + 10] & 255;
               int b11 = dfLa[pos + 11] & 255;
               int b12 = dfLa[pos + 12] & 255;
               int sampleRate = b10 << 12 | b11 << 4 | b12 >> 4 & 15;
               int channels = (b12 >> 1 & 7) + 1;
               int bps = ((b12 & 1) << 4 | (dfLa[pos + 13] & 240) >> 4) + 1;
               if (bps < 4) {
                  bps = 16;
               }

               return new AudioFormat(sampleRate, bps, channels, true, false);
            }

            pos += blockLen;
            if (lastBlock) {
               break;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public static byte[] dfLaToNativeFlacMetadata(byte[] dfLa) {
      if (dfLa != null && dfLa.length > 4) {
         byte[] metadata = new byte[dfLa.length - 4];
         System.arraycopy(dfLa, 4, metadata, 0, metadata.length);
         return metadata;
      } else {
         return new byte[0];
      }
   }

   public static int[] extractSampleSizesFromMoof(byte[] moofData) {
      Fmp4ToMp4Converter.ParseResult pr = parseMoof(moofData);
      if (pr.sampleSizes != null) {
         return pr.sampleSizes;
      } else if (pr.defaultSampleSize > 0 && pr.sampleCount > 0) {
         int[] sizes = new int[pr.sampleCount];

         for (int i = 0; i < sizes.length; i++) {
            sizes[i] = pr.defaultSampleSize;
         }

         return sizes;
      } else {
         return new int[0];
      }
   }

   public static Fmp4ToMp4Converter.SampleTable extractSampleTableFromMoof(byte[] moofData, int timescale, int fallbackFps) {
      Fmp4ToMp4Converter.ParseResult pr = parseMoof(moofData);
      int count = pr.sampleCount;
      if (count <= 0 && pr.sampleSizes != null) {
         count = pr.sampleSizes.length;
      }

      if (count <= 0) {
         return Fmp4ToMp4Converter.SampleTable.EMPTY;
      } else {
         int[] sizes = pr.sampleSizes;
         if ((sizes == null || sizes.length < count) && pr.defaultSampleSize > 0) {
            sizes = new int[count];
            Arrays.fill(sizes, pr.defaultSampleSize);
         }

         if (sizes == null) {
            sizes = new int[0];
         }

         long[] ptsNanos = new long[count];
         long decodeTime = Math.max(0L, pr.baseMediaDecodeTime);
         int scale = Math.max(1, timescale);
         long fallbackDuration = Math.max(1L, Math.round((double)scale / Math.max(1, fallbackFps)));

         for (int i = 0; i < count; i++) {
            long duration = pr.sampleDurations != null && i < pr.sampleDurations.length && pr.sampleDurations[i] > 0L
               ? pr.sampleDurations[i]
               : (pr.defaultSampleDuration > 0L ? pr.defaultSampleDuration : fallbackDuration);
            long compositionOffset = pr.sampleCompositionOffsets != null && i < pr.sampleCompositionOffsets.length ? pr.sampleCompositionOffsets[i] : 0L;
            ptsNanos[i] = Math.max(0L, Math.round(Math.max(0L, decodeTime + compositionOffset) * 1.0E9 / scale));
            decodeTime += duration;
         }

         return new Fmp4ToMp4Converter.SampleTable(sizes, ptsNanos);
      }
   }

   private static AudioFormat ascToAudioFormat(byte[] asc) {
      if (asc != null && asc.length >= 2) {
         int b0 = asc[0] & 255;
         int b1 = asc[1] & 255;
         int freqIndex = (b0 & 7) << 1 | b1 >> 7 & 1;
         int channels = b1 >> 3 & 15;
         if (channels < 1) {
            channels = 2;
         }

         int[] rates = new int[]{96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
         float rate = freqIndex < rates.length ? rates[freqIndex] : 44100.0F;
         return new AudioFormat(rate, 16, channels, true, false);
      } else {
         return null;
      }
   }

   private static Fmp4ToMp4Converter.BoxHeader readBoxHeader(ByteBuffer buf) {
      if (buf.remaining() < 8) {
         return null;
      } else {
         long size = buf.getInt() & 4294967295L;
         byte[] tb = new byte[4];
         buf.get(tb);
         String type = new String(tb);
         int hs = 8;
         if (size == 1L) {
            if (buf.remaining() < 8) {
               return null;
            }

            size = buf.getLong();
            hs = 16;
         } else if (size == 0L) {
            size = buf.remaining() + hs;
         }

         return size < hs ? null : new Fmp4ToMp4Converter.BoxHeader(size, type, hs);
      }
   }

   public static Fmp4ToMp4Converter.ParseResult parseMoof(byte[] data) {
      Fmp4ToMp4Converter.ParseResult r = new Fmp4ToMp4Converter.ParseResult();
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
      int td = 0;

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("traf".equals(t)) {
            td = parseTraf(cd, r, td);
         }
      }

      return r;
   }

   private static int parseTraf(byte[] data, Fmp4ToMp4Converter.ParseResult r, int prevDefault) {
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
      int ds = prevDefault;

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("tfhd".equals(t)) {
            ds = parseTfhd(cd, r);
         } else if ("tfdt".equals(t)) {
            parseTfdt(cd, r);
         } else if ("trun".equals(t)) {
            parseTrun(cd, r, ds);
         }
      }

      return ds;
   }

   private static int parseTfhd(byte[] cd, Fmp4ToMp4Converter.ParseResult r) {
      if (cd.length < 8) {
         return 0;
      } else {
         int flags = (cd[1] & 255) << 16 | (cd[2] & 255) << 8 | cd[3] & 255;
         ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
         int pos = 8;
         if ((flags & 1) != 0) {
            pos += 8;
         }

         if ((flags & 2) != 0) {
            pos += 4;
         }

         if ((flags & 8) != 0) {
            if (pos + 4 <= cd.length) {
               r.defaultSampleDuration = bb.getInt(pos) & 4294967295L;
            }

            pos += 4;
         }

         if ((flags & 16) != 0 && pos + 4 <= cd.length) {
            return bb.getInt(pos);
         } else {
            if ((flags & 32) != 0) {
               pos += 4;
            }

            return 0;
         }
      }
   }

   private static void parseTfdt(byte[] cd, Fmp4ToMp4Converter.ParseResult r) {
      if (cd.length >= 8) {
         int version = cd[0] & 255;
         ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
         if (version == 1 && cd.length >= 12) {
            r.baseMediaDecodeTime = Math.max(0L, bb.getLong(4));
         } else {
            r.baseMediaDecodeTime = bb.getInt(4) & 4294967295L;
         }
      }
   }

   private static void parseTrun(byte[] cd, Fmp4ToMp4Converter.ParseResult r, int defaultSampleSize) {
      if (cd.length >= 8) {
         int version = cd[0] & 255;
         int flags = (cd[1] & 255) << 16 | (cd[2] & 255) << 8 | cd[3] & 255;
         ByteBuffer trun = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
         int pos = 4;
         int sc = trun.getInt(pos);
         pos += 4;
         boolean hdo = (flags & 1) != 0;
         boolean hfs = (flags & 4) != 0;
         boolean hsd = (flags & 256) != 0;
         boolean hss = (flags & 512) != 0;
         boolean hsf = (flags & 1024) != 0;
         boolean hct = (flags & 2048) != 0;
         if (hdo && pos + 4 <= cd.length) {
            pos += 4;
         }

         if (hfs && pos + 4 <= cd.length) {
            pos += 4;
         }

         r.sampleCount += sc;
         r.ensureTimingCapacity(r.sampleDurations != null ? r.sampleDurations.length + sc : sc);
         int ti = r.sampleDurations.length - sc;
         if (hss && sc > 0) {
            r.ensureCapacity(r.sampleSizes != null ? r.sampleSizes.length + sc : sc);
            int wi = r.sampleSizes.length - sc;

            for (int i = 0; i < sc; i++) {
               if (hsd && pos + 4 <= cd.length) {
                  r.sampleDurations[ti + i] = trun.getInt(pos) & 4294967295L;
                  pos += 4;
               }

               if (pos + 4 <= cd.length) {
                  r.sampleSizes[wi + i] = trun.getInt(pos);
                  pos += 4;
               }

               if (hsf && pos + 4 <= cd.length) {
                  pos += 4;
               }

               if (hct && pos + 4 <= cd.length) {
                  int rawOffset = trun.getInt(pos);
                  r.sampleCompositionOffsets[ti + i] = version == 0 ? rawOffset & 4294967295L : rawOffset;
                  pos += 4;
               }
            }
         } else {
            for (int i = 0; i < sc; i++) {
               if (hsd && pos + 4 <= cd.length) {
                  r.sampleDurations[ti + i] = trun.getInt(pos) & 4294967295L;
                  pos += 4;
               }

               if (hss && pos + 4 <= cd.length) {
                  pos += 4;
               }

               if (hsf && pos + 4 <= cd.length) {
                  pos += 4;
               }

               if (hct && pos + 4 <= cd.length) {
                  int rawOffset = trun.getInt(pos);
                  r.sampleCompositionOffsets[ti + i] = version == 0 ? rawOffset & 4294967295L : rawOffset;
                  pos += 4;
               }
            }

            if (defaultSampleSize > 0) {
               r.defaultSampleSize = defaultSampleSize;
            }
         }
      }
   }

   public static Fmp4ToMp4Converter.ParseResult parseMoov(byte[] data) {
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
      Fmp4ToMp4Converter.ParseResult r = new Fmp4ToMp4Converter.ParseResult();

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("trak".equals(t)) {
            parseTrak(cd, r);
         }
      }

      return r;
   }

   public static int parseVideoTimescale(byte[] data) {
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("trak".equals(t) && isTrackType(cd, "vide")) {
            int timescale = parseTrackTimescale(cd);
            if (timescale > 0) {
               return timescale;
            }
         }
      }

      return 0;
   }

   private static void parseTrak(byte[] data, Fmp4ToMp4Converter.ParseResult r) {
      if (isAudioTrack(data)) {
         ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

         while (b.remaining() >= 8) {
            int s = b.getInt();
            String t = read4cc(b);
            if (s < 8 || s - 8 > b.remaining()) {
               break;
            }

            int cs = s - 8;
            byte[] cd = new byte[cs];
            b.get(cd);
            if ("mdia".equals(t)) {
               parseMdia(cd, r);
            }
         }
      }
   }

   private static boolean isAudioTrack(byte[] d) {
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         if ("hdlr".equals(t)) {
            byte[] hd = new byte[cs];
            b.get(hd);
            if (cs >= 12 && "soun".equals(new String(hd, 8, 4))) {
               return true;
            }
         } else if ("mdia".equals(t)) {
            byte[] md = new byte[cs];
            b.get(md);
            if (isAudioTrack(md)) {
               return true;
            }
         } else {
            b.position(b.position() + cs);
         }
      }

      return indexOf(d, "mp4a".getBytes()) >= 0;
   }

   private static boolean isTrackType(byte[] d, String handlerType) {
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         if ("hdlr".equals(t)) {
            byte[] hd = new byte[cs];
            b.get(hd);
            if (cs >= 12 && handlerType.equals(new String(hd, 8, 4))) {
               return true;
            }
         } else if ("mdia".equals(t)) {
            byte[] md = new byte[cs];
            b.get(md);
            if (isTrackType(md, handlerType)) {
               return true;
            }
         } else {
            b.position(b.position() + cs);
         }
      }

      return false;
   }

   private static int parseTrackTimescale(byte[] trakData) {
      ByteBuffer b = ByteBuffer.wrap(trakData).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("mdia".equals(t)) {
            int timescale = parseMdiaTimescale(cd);
            if (timescale > 0) {
               return timescale;
            }
         }
      }

      return 0;
   }

   private static int parseMdiaTimescale(byte[] mdiaData) {
      ByteBuffer b = ByteBuffer.wrap(mdiaData).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("mdhd".equals(t)) {
            return readMdhdTimescale(cd);
         }
      }

      return 0;
   }

   private static int readMdhdTimescale(byte[] cd) {
      if (cd.length < 20) {
         return 0;
      } else {
         int version = cd[0] & 255;
         ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
         int timescale = 0;
         if (version == 1 && cd.length >= 32) {
            timescale = bb.getInt(20);
         } else if (version == 0) {
            timescale = bb.getInt(12);
         }

         return Math.max(0, timescale);
      }
   }

   private static void parseMdia(byte[] d, Fmp4ToMp4Converter.ParseResult r) {
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("mdhd".equals(t)) {
            parseMdhd(cd, r);
         } else if ("minf".equals(t)) {
            parseMinf(cd, r);
         }
      }
   }

   private static void parseMdhd(byte[] cd, Fmp4ToMp4Converter.ParseResult r) {
      if (cd.length >= 20) {
         int version = cd[0] & 255;
         ByteBuffer bb = ByteBuffer.wrap(cd).order(ByteOrder.BIG_ENDIAN);
         int timescale = 0;
         if (version == 1 && cd.length >= 32) {
            timescale = bb.getInt(20);
         } else if (version == 0) {
            timescale = bb.getInt(12);
         }

         if (timescale > 0) {
            r.timescale = timescale;
         }
      }
   }

   private static void parseMinf(byte[] d, Fmp4ToMp4Converter.ParseResult r) {
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("stbl".equals(t)) {
            parseStbl(cd, r);
         }
      }
   }

   private static void parseStbl(byte[] d, Fmp4ToMp4Converter.ParseResult r) {
      ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);

      while (b.remaining() >= 8) {
         int s = b.getInt();
         String t = read4cc(b);
         if (s < 8 || s - 8 > b.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         b.get(cd);
         if ("stsd".equals(t)) {
            parseStsd(cd, r);
         }
      }
   }

   private static void parseStsd(byte[] d, Fmp4ToMp4Converter.ParseResult r) {
      if (d.length >= 16) {
         ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.BIG_ENDIAN);
         b.position(4);
         int ec = b.getInt();
         int rem = d.length - 8;

         for (int i = 0; i < ec && rem >= 8; i++) {
            int es = b.getInt();
            String et = read4cc(b);
            if (es < 8 || es > rem) {
               break;
            }

            if ("mp4a".equals(et)) {
               byte[] md = new byte[es - 8];
               b.get(md);
               r.asc = extractAscFromMp4a(md);
               r.audioCodec = "mp4a";
               if (r.asc != null) {
                  AudioFormat af = ascToAudioFormat(r.asc);
                  if (af != null) {
                     logger().debug("AAC ASC 提取成功: {}Hz/{}ch", af.getSampleRate(), af.getChannels());
                  }

                  return;
               }
            } else if ("fLaC".equals(et) && es >= 36) {
               byte[] md = new byte[es - 8];
               b.get(md);
               r.flacDfLa = extractDfLaFromFlacStsd(md);
               r.audioCodec = "fLaC";
               if (r.flacDfLa != null) {
                  return;
               }
            } else {
               if ("ec-3".equals(et)) {
                  r.audioCodec = "ec-3";
                  return;
               }

               b.position(b.position() + es - 8);
            }

            rem -= es;
         }
      }
   }

   private static byte[] extractAscFromMp4a(byte[] d) {
      if (d.length < 28) {
         return null;
      } else {
         int pos = 28;

         while (pos + 8 <= d.length) {
            int bs = readIntBE(d, pos);
            String bt = new String(d, pos + 4, 4);
            if (bs == 0) {
               pos += 4;
            } else if (bs >= 8 && pos + bs <= d.length) {
               if ("esds".equals(bt)) {
                  return extractAscFromEsds(d, pos + 8, bs - 8);
               }

               pos += bs;
            } else {
               pos += 4;
            }
         }

         return null;
      }
   }

   private static byte[] extractAscFromEsds(byte[] d, int start, int len) {
      int pos = start + 4;
      int end = Math.min(d.length, start + len);
      return findAscDescriptor(d, pos, end);
   }

   private static byte[] findAscDescriptor(byte[] d, int start, int end) {
      int pos = start;

      while (pos < end - 1) {
         int tag = d[pos++] & 255;
         if (tag != 0) {
            int dl = 0;

            for (int i = 0; i < 4 && pos < end; i++) {
               int b = d[pos++] & 255;
               dl = dl << 7 | b & 127;
               if ((b & 128) == 0) {
                  break;
               }
            }

            int pe = Math.min(end, pos + dl);
            if (tag == 5 && dl >= 2) {
               byte[] asc = new byte[pe - pos];
               System.arraycopy(d, pos, asc, 0, asc.length);
               return asc;
            }

            int ns = pos;
            if (tag == 3 && pos + 3 <= pe) {
               int flags = d[pos + 2] & 255;
               ns = pos + 3;
               if ((flags & 128) != 0) {
                  ns += 2;
               }

               if ((flags & 64) != 0 && ns < pe) {
                  ns += 1 + (d[ns] & 255);
               }

               if ((flags & 32) != 0) {
                  ns += 2;
               }
            } else if (tag == 4) {
               ns = pos + 13;
            }

            if (ns > pos && ns < pe) {
               byte[] nested = findAscDescriptor(d, ns, pe);
               if (nested != null) {
                  return nested;
               }
            }

            pos = pe;
         }
      }

      return null;
   }

   private static String read4cc(ByteBuffer b) {
      byte[] x = new byte[4];
      b.get(x);
      return new String(x);
   }

   private static int readIntBE(byte[] d, int off) {
      return (d[off] & 0xFF) << 24 | (d[off + 1] & 0xFF) << 16 | (d[off + 2] & 0xFF) << 8 | d[off + 3] & 0xFF;
   }

   private static int indexOf(byte[] h, byte[] n) {
      label24:
      for (int i = 0; i <= h.length - n.length; i++) {
         for (int j = 0; j < n.length; j++) {
            if (h[i + j] != n[j]) {
               continue label24;
            }
         }

         return i;
      }

      return -1;
   }

   public static String listAudioCodecs(byte[] moovData) {
      List<String> codecs = new ArrayList<>();
      ByteBuffer buf = ByteBuffer.wrap(moovData).order(ByteOrder.BIG_ENDIAN);

      while (buf.remaining() >= 8) {
         int s = buf.getInt();
         String t = read4cc(buf);
         if (s < 8 || s - 8 > buf.remaining()) {
            break;
         }

         int cs = s - 8;
         byte[] cd = new byte[cs];
         buf.get(cd);
         if ("trak".equals(t)) {
            String c = findStsdCodec(cd);
            if (c != null) {
               codecs.add(c);
            }
         }
      }

      return codecs.isEmpty() ? "(未找到音频轨)" : String.join(", ", codecs);
   }

   private static String findStsdCodec(byte[] trakData) {
      int stsd = indexOf(trakData, "stsd".getBytes());
      if (stsd < 0) {
         return null;
      } else {
         ByteBuffer b = ByteBuffer.wrap(trakData).order(ByteOrder.BIG_ENDIAN);
         if (stsd + 16 > b.remaining()) {
            return null;
         } else {
            b.position(stsd + 12);
            int ec = b.getInt();
            if (ec >= 1 && b.remaining() >= 8) {
               b.getInt();
               return read4cc(b);
            } else {
               return null;
            }
         }
      }
   }

   private static class BoxHeader {
      final long size;
      final String type;
      final int headerSize;

      BoxHeader(long s, String t, int h) {
         this.size = s;
         this.type = t;
         this.headerSize = h;
      }

      long dataSize() {
         return this.size - this.headerSize;
      }
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }

   public static class ParseResult {
      public String audioCodec;
      public byte[] asc;
      public byte[] flacDfLa;
      public int defaultSampleSize;
      public long defaultSampleDuration;
      public int sampleCount;
      public int[] sampleSizes;
      public long[] sampleDurations;
      public long[] sampleCompositionOffsets;
      public int timescale;
      public long baseMediaDecodeTime = -1L;

      void ensureCapacity(int min) {
         if (this.sampleSizes == null) {
            this.sampleSizes = new int[min];
         } else if (this.sampleSizes.length < min) {
            int[] o = this.sampleSizes;
            this.sampleSizes = new int[min];
            System.arraycopy(o, 0, this.sampleSizes, 0, o.length);
         }
      }

      void ensureTimingCapacity(int min) {
         if (this.sampleDurations == null) {
            this.sampleDurations = new long[min];
            this.sampleCompositionOffsets = new long[min];
         } else if (this.sampleDurations.length < min) {
            long[] oldDurations = this.sampleDurations;
            long[] oldOffsets = this.sampleCompositionOffsets;
            this.sampleDurations = new long[min];
            this.sampleCompositionOffsets = new long[min];
            System.arraycopy(oldDurations, 0, this.sampleDurations, 0, oldDurations.length);
            System.arraycopy(oldOffsets, 0, this.sampleCompositionOffsets, 0, oldOffsets.length);
         }
      }
   }

   public record SampleTable(int[] sampleSizes, long[] ptsNanos) {
      public static final Fmp4ToMp4Converter.SampleTable EMPTY = new Fmp4ToMp4Converter.SampleTable(new int[0], new long[0]);
   }
}
