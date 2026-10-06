package com.zhongbai233.net_music_can_play_bili.media.local;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一条轨的样本重封装成**单轨 fMP4**（init + 分片 + sidx）。
 *
 * <p>三条硬约束来自本模组现有的消费者：
 * <ol>
 *   <li>文件必须 {@code ftyp} 开头、紧跟 {@code moov}，且 moov 里只有一个 trak ——
 *       {@code Fmp4RangeSeekSupport.extractInitSegment} 只认这种 init；
 *   <li>每个 moof 里只有一个 traf，trun 里逐样本给出**时长 + 字节数**（可选 CTS 偏移）——
 *       这是 {@code Fmp4ToMp4Converter.parseTrun} 唯一能读对的布局（见下面 {@code TRUN_*} 注释）；
 *   <li>sidx 必须紧跟在 moov 之后（`first_offset=0`），seek 时
 *       {@code parseSidx(data, indexStart)} 才会把第一条分片的绝对偏移算对。
 * </ol>
 *
 * <p>视频分片只在**关键帧**处切开：这样 sidx 里每条都是真 SAP，跳转到任意分片都能立刻出画。
 * 源文件关键帧太远（或有 stss 却没有关键帧标记）时，退化为按字节上限切，并在 sidx 里如实标记
 * {@code starts_with_SAP=0} —— 查找逻辑会跳过这类分片，宁可选早一点的关键帧，也不假装能精确落点。
 */
public final class Fmp4TrackWriter {
   public static final int DEFAULT_FRAGMENT_MILLIS = 1000;
   public static final long DEFAULT_MAX_FRAGMENT_BYTES = 24L * 1024 * 1024;
   private static final long HARD_FRAGMENT_BYTES_MULTIPLIER = 4L;
   private static final int COPY_BUFFER_BYTES = 256 * 1024;

   /**
    * trun 的 flags 位。这里按本模组既有解析器（{@code Fmp4ToMp4Converter.parseTrun}）的读法写入，
    * 字节布局与之一一对应：数据偏移(0x1) → 逐样本时长(0x100) → 逐样本字节数(0x200) → 逐样本 CTS(0x800)。
    * 0x4 与 0x400（首样本标志 / 逐样本标志）刻意不置位：解码器判关键帧是直接看 NAL 类型，
    * 容器里的标志位没人读，少写两个字段就少两处可能对不齐的字节。
    */
   private static final int TRUN_DATA_OFFSET = 0x000001;
   private static final int TRUN_SAMPLE_DURATION = 0x000100;
   private static final int TRUN_SAMPLE_SIZE = 0x000200;
   private static final int TRUN_SAMPLE_CTS = 0x000800;
   private static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

   private Fmp4TrackWriter() {
   }

   /** 重封装结果：字节区间用于向 seek 逻辑登记 segment base。 */
   public record Result(
      long fileSize, long initEnd, long indexStart, long indexEnd, int fragmentCount, long copiedBytes, long durationMillis
   ) {
   }

   private record Fragment(int start, int end, boolean sap) {
   }

   public static Result write(Mp4TrackIndex track, Path source, Path target, int fragmentMillis, long maxFragmentBytes) throws IOException {
      int requested = fragmentMillis > 0 ? fragmentMillis : DEFAULT_FRAGMENT_MILLIS;
      long byteCap = maxFragmentBytes > 0L ? maxFragmentBytes : DEFAULT_MAX_FRAGMENT_BYTES;
      List<Fragment> fragments = plan(track, requested, byteCap);
      byte[] ftyp = fileType(track);
      byte[] moov = movieBox(track);
      int sidxSize = 32 + 12 * fragments.size();
      byte[] sidx = new byte[sidxSize];
      writeSidx(sidx, track, fragments, new long[fragments.size()], new long[fragments.size()], true);
      long indexStart = (long)ftyp.length + moov.length;
      long indexEnd = indexStart + sidxSize - 1L;
      long[] fragmentSizes = new long[fragments.size()];
      long[] fragmentDurations = new long[fragments.size()];
      long copied = 0L;
      ByteBuffer copyBuffer = ByteBuffer.allocate(COPY_BUFFER_BYTES).order(ByteOrder.BIG_ENDIAN);

      try (SeekableByteChannel sourceChannel = Files.newByteChannel(source, StandardOpenOption.READ);
         SeekableByteChannel sink = Files.newByteChannel(
            target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
         OutputStream raw = Channels.newOutputStream(sink);
         BufferedOutputStream out = new BufferedOutputStream(raw, 1 << 20)
      ) {
         out.write(ftyp);
         out.write(moov);
         out.write(sidx);
         long decodeTime = 0L;

         for (int index = 0; index < fragments.size(); index++) {
            Fragment fragment = fragments.get(index);
            long units = 0L;
            long bytes = 0L;

            for (int sample = fragment.start(); sample < fragment.end(); sample++) {
               units += Math.max(1, track.sampleDuration(sample));
               bytes += Math.max(0, track.sampleSize(sample));
            }

            byte[] moof = movieFragment(track, fragment, index + 1, decodeTime, bytes);
            byte[] mdatHeader = boxHeader(8L + bytes, "mdat");
            out.write(moof);
            out.write(mdatHeader);

            for (int sample = fragment.start(); sample < fragment.end(); sample++) {
               int size = track.sampleSize(sample);
               if (size > 0) {
                  copySample(sourceChannel, out, track.sampleOffset(sample), size, copyBuffer);
                  copied += size;
               }
            }

            fragmentSizes[index] = moof.length + mdatHeader.length + bytes;
            fragmentDurations[index] = units;
            decodeTime += units;
         }
      }

      byte[] finalSidx = new byte[sidxSize];
      writeSidx(finalSidx, track, fragments, fragmentSizes, fragmentDurations, false);
      try (SeekableByteChannel patch = Files.newByteChannel(target, StandardOpenOption.WRITE)) {
         ByteBuffer buffer = ByteBuffer.wrap(finalSidx);
         long position = indexStart;

         while (buffer.hasRemaining()) {
            patch.position(position);
            int written = patch.write(buffer);
            if (written <= 0) {
               throw new IOException("sidx 回填失败: position=" + position);
            }

            position += written;
         }
      }

      long durationMillis = Math.round(totalUnits(track) * 1000.0 / track.timescale());
      long fileSize = Files.size(target);
      return new Result(fileSize, indexStart - 1L, indexStart, indexEnd, fragments.size(), copied, durationMillis);
   }

   /** 分片计划：视频在关键帧处切（并受目标时长 / 字节上限约束），音频只按时长与字节上限切。 */
   static List<Fragment> plan(Mp4TrackIndex track, int fragmentMillis, long maxFragmentBytes) {
      int count = track.sampleCount();
      long targetUnits = Math.max(1L, (long)track.timescale() * Math.max(20, fragmentMillis) / 1000L);
      long hardBytes = Math.max(maxFragmentBytes, 1L) * HARD_FRAGMENT_BYTES_MULTIPLIER;
      List<Fragment> fragments = new ArrayList<>();
      int start = 0;
      long units = 0L;
      long bytes = 0L;

      for (int i = 0; i < count; i++) {
         if (i > start) {
            boolean sync = track.isSyncSample(i);
            boolean due = units >= targetUnits || bytes >= maxFragmentBytes;
            // 视频只在关键帧处切；音频（每样本都是同步样本）到点就切
            if (due && (sync || !track.isVideo()) || sync && bytes >= hardBytes) {
               fragments.add(new Fragment(start, i, track.isSyncSample(start)));
               start = i;
               units = 0L;
               bytes = 0L;
            }
         }

         units += Math.max(1, track.sampleDuration(i));
         bytes += Math.max(0, track.sampleSize(i));
      }

      fragments.add(new Fragment(start, count, count > 0 && track.isSyncSample(start)));
      return fragments;
   }

   private static byte[] movieFragment(Mp4TrackIndex track, Fragment fragment, int sequence, long decodeTime, long bytes) throws IOException {
      int count = fragment.end() - fragment.start();
      boolean composition = track.hasCompositionOffsets();
      int trunFlags = TRUN_DATA_OFFSET | TRUN_SAMPLE_DURATION | TRUN_SAMPLE_SIZE | (composition ? TRUN_SAMPLE_CTS : 0);
      boolean negativeOffsets = false;

      if (composition) {
         for (int sample = fragment.start(); sample < fragment.end(); sample++) {
            if (track.compositionOffset(sample) < 0) {
               negativeOffsets = true;
               break;
            }
         }
      }

      byte[] mfhd = new byte[16];
      ByteBuffer mfhdBuffer = ByteBuffer.wrap(mfhd).order(ByteOrder.BIG_ENDIAN);
      mfhdBuffer.putInt(16);
      putFourCc(mfhdBuffer, "mfhd");
      mfhdBuffer.putInt(0);
      mfhdBuffer.putInt(sequence);
      int trunPayload = 8 + count * (composition ? 12 : 8);
      byte[] tfhd = new byte[16];
      ByteBuffer tfhdBuffer = ByteBuffer.wrap(tfhd).order(ByteOrder.BIG_ENDIAN);
      tfhdBuffer.putInt(16);
      putFourCc(tfhdBuffer, "tfhd");
      tfhdBuffer.putInt(TFHD_DEFAULT_BASE_IS_MOOF);
      tfhdBuffer.putInt(track.trackId());
      byte[] tfdt = new byte[20];
      ByteBuffer tfdtBuffer = ByteBuffer.wrap(tfdt).order(ByteOrder.BIG_ENDIAN);
      tfdtBuffer.putInt(20);
      putFourCc(tfdtBuffer, "tfdt");
      tfdtBuffer.putInt(0x01000000);
      tfdtBuffer.putLong(Math.max(0L, decodeTime));
      byte[] trun = new byte[8 + 4 + trunPayload];
      ByteBuffer trunBuffer = ByteBuffer.wrap(trun).order(ByteOrder.BIG_ENDIAN);
      trunBuffer.putInt(trun.length);
      putFourCc(trunBuffer, "trun");
      trunBuffer.putInt(negativeOffsets ? 0x01000000 | trunFlags : trunFlags);
      trunBuffer.putInt(count);
      // moof 头部(8) + mfhd + traf 头部(8) + tfhd + tfdt + trun；data_offset 指向 mdat 负载（再过 8 字节头部）
      long moofSize = 8L + mfhd.length + 8L + tfhd.length + tfdt.length + trun.length;
      trunBuffer.putInt((int)(moofSize + 8L));
      for (int sample = fragment.start(); sample < fragment.end(); sample++) {
         trunBuffer.putInt(Math.max(1, track.sampleDuration(sample)));
         trunBuffer.putInt(Math.max(0, track.sampleSize(sample)));
         if (composition) {
            trunBuffer.putInt(track.compositionOffset(sample));
         }
      }

      byte[] traf = concat("traf", tfhd, tfdt, trun);
      return concat("moof", mfhd, traf);
   }

   private static void writeSidx(
      byte[] target, Mp4TrackIndex track, List<Fragment> fragments, long[] sizes, long[] durations, boolean placeholder
   ) {
      ByteBuffer buffer = ByteBuffer.wrap(target).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(target.length);
      putFourCc(buffer, "sidx");
      buffer.putInt(0);
      buffer.putInt(track.trackId());
      buffer.putInt(track.timescale());
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putShort((short)0);
      buffer.putShort((short)fragments.size());

      for (int i = 0; i < fragments.size(); i++) {
         long size = placeholder ? 0L : Math.max(0L, sizes[i]);
         long duration = placeholder ? 0L : Math.max(0L, durations[i]);
         buffer.putInt((int)Math.min(0x7FFFFFFFL, size));
         buffer.putInt((int)Math.min(0xFFFFFFFFL, duration));
         buffer.putInt(fragments.get(i).sap() ? 0x90000000 : 0x10000000);
      }
   }

   static long totalUnits(Mp4TrackIndex track) {
      long units = track.durationUnits();
      if (units <= 0L) {
         units = track.sumDurations();
      }

      return units;
   }

   /** ftyp：major brand 用 isom，兼容 iso6/mp41/dash —— 与 DASH 分片一致的通用组合。 */
   private static byte[] fileType(Mp4TrackIndex track) {
      ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + 4 + 4 * 4).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(buffer.capacity());
      putFourCc(buffer, "ftyp");
      putFourCc(buffer, "isom");
      buffer.putInt(0x200);
      putFourCc(buffer, "isom");
      putFourCc(buffer, "iso6");
      putFourCc(buffer, "mp41");
      putFourCc(buffer, "dash");
      return buffer.array();
   }

   private static byte[] movieBox(Mp4TrackIndex track) {
      long duration = totalUnits(track);
      byte[] mvhd = new byte[108];
      ByteBuffer mvhdBuffer = ByteBuffer.wrap(mvhd).order(ByteOrder.BIG_ENDIAN);
      mvhdBuffer.putInt(108);
      putFourCc(mvhdBuffer, "mvhd");
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(track.timescale());
      mvhdBuffer.putInt((int)Math.min(0xFFFFFFFFL, duration));
      mvhdBuffer.putInt(0x00010000);
      mvhdBuffer.putShort((short)0x0100);
      mvhdBuffer.putShort((short)0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      putUnityMatrix(mvhdBuffer);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(0);
      mvhdBuffer.putInt(track.trackId() + 1);
      byte[] trak = concat("trak", trackHeader(track, duration), mediaBox(track, duration));
      byte[] trex = new byte[32];
      ByteBuffer trexBuffer = ByteBuffer.wrap(trex).order(ByteOrder.BIG_ENDIAN);
      trexBuffer.putInt(32);
      putFourCc(trexBuffer, "trex");
      trexBuffer.putInt(0);
      trexBuffer.putInt(track.trackId());
      trexBuffer.putInt(1);
      trexBuffer.putInt(0);
      trexBuffer.putInt(0);
      trexBuffer.putInt(0);
      return concat("moov", mvhd, trak, concat("mvex", trex));
   }

   private static byte[] trackHeader(Mp4TrackIndex track, long duration) {
      byte[] tkhd = new byte[92];
      ByteBuffer buffer = ByteBuffer.wrap(tkhd).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(92);
      putFourCc(buffer, "tkhd");
      buffer.putInt(0x00000007);
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(track.trackId());
      buffer.putInt(0);
      buffer.putInt((int)Math.min(0xFFFFFFFFL, duration));
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putShort((short)0);
      buffer.putShort((short)0);
      buffer.putShort((short)(track.isAudio() ? 0x0100 : 0));
      buffer.putShort((short)0);
      putUnityMatrix(buffer);
      buffer.putInt(displayWidth(track) << 16);
      buffer.putInt(displayHeight(track) << 16);
      return tkhd;
   }

   private static byte[] mediaBox(Mp4TrackIndex track, long duration) {
      byte[] mdhd = new byte[32];
      ByteBuffer mdhdBuffer = ByteBuffer.wrap(mdhd).order(ByteOrder.BIG_ENDIAN);
      mdhdBuffer.putInt(32);
      putFourCc(mdhdBuffer, "mdhd");
      mdhdBuffer.putInt(0);
      mdhdBuffer.putInt(0);
      mdhdBuffer.putInt(0);
      mdhdBuffer.putInt(track.timescale());
      mdhdBuffer.putInt((int)Math.min(0xFFFFFFFFL, duration));
      mdhdBuffer.putShort((short)0x55C4);
      mdhdBuffer.putShort((short)0);
      byte[] handlerName = (track.isAudio() ? "SoundHandler" : "VideoHandler").getBytes(StandardCharsets.US_ASCII);
      byte[] hdlr = new byte[8 + 4 + 4 + 4 + 12 + handlerName.length + 1];
      ByteBuffer hdlrBuffer = ByteBuffer.wrap(hdlr).order(ByteOrder.BIG_ENDIAN);
      hdlrBuffer.putInt(hdlr.length);
      putFourCc(hdlrBuffer, "hdlr");
      hdlrBuffer.putInt(0);
      hdlrBuffer.putInt(0);
      putFourCc(hdlrBuffer, track.isAudio() ? "soun" : "vide");
      hdlrBuffer.putInt(0);
      hdlrBuffer.putInt(0);
      hdlrBuffer.putInt(0);
      hdlrBuffer.put(handlerName);
      hdlrBuffer.put((byte)0);
      byte[] mediaHeader;
      if (track.isAudio()) {
         ByteBuffer smhd = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
         smhd.putInt(16);
         putFourCc(smhd, "smhd");
         smhd.putInt(0);
         smhd.putShort((short)0);
         smhd.putShort((short)0);
         mediaHeader = smhd.array();
      } else {
         ByteBuffer vmhd = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
         vmhd.putInt(20);
         putFourCc(vmhd, "vmhd");
         vmhd.putInt(1);
         vmhd.putShort((short)0);
         vmhd.putShort((short)0);
         vmhd.putShort((short)0);
         mediaHeader = vmhd.array();
      }

      byte[] url = new byte[12];
      ByteBuffer urlBuffer = ByteBuffer.wrap(url).order(ByteOrder.BIG_ENDIAN);
      urlBuffer.putInt(12);
      putFourCc(urlBuffer, "url ");
      urlBuffer.putInt(1);
      byte[] dref = new byte[8 + 4 + 4 + url.length];
      ByteBuffer drefBuffer = ByteBuffer.wrap(dref).order(ByteOrder.BIG_ENDIAN);
      drefBuffer.putInt(dref.length);
      putFourCc(drefBuffer, "dref");
      drefBuffer.putInt(0);
      drefBuffer.putInt(1);
      drefBuffer.put(url);
      byte[] dinf = concat("dinf", dref);
      byte[] stbl = concat("stbl", sampleDescription(track), emptyTable("stts"), emptyTable("stsc"), emptySampleSizes(), emptyTable("stco"));
      return concat("mdia", mdhd, hdlr, concat("minf", mediaHeader, dinf, stbl));
   }

   private static byte[] sampleDescription(Mp4TrackIndex track) {
      byte[] entry = track.sampleEntry();
      if (entry == null || entry.length < 8) {
         throw new IllegalStateException("缺少样本描述盒，无法写出 fMP4 init");
      }

      byte[] stsd = new byte[8 + 4 + 4 + entry.length];
      ByteBuffer buffer = ByteBuffer.wrap(stsd).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(stsd.length);
      putFourCc(buffer, "stsd");
      buffer.putInt(0);
      buffer.putInt(1);
      buffer.put(entry);
      return stsd;
   }

   private static byte[] emptyTable(String type) {
      byte[] table = new byte[16];
      ByteBuffer buffer = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(16);
      putFourCc(buffer, type);
      buffer.putInt(0);
      buffer.putInt(0);
      return table;
   }

   private static byte[] emptySampleSizes() {
      byte[] table = new byte[20];
      ByteBuffer buffer = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(20);
      putFourCc(buffer, "stsz");
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(0);
      return table;
   }

   private static int displayWidth(Mp4TrackIndex track) {
      if (track.width() > 0) {
         return track.width();
      }

      byte[] entry = track.sampleEntry();
      return entry != null && entry.length >= 36 ? Mp4BoxReader.u16(entry, 32) : 0;
   }

   private static int displayHeight(Mp4TrackIndex track) {
      if (track.height() > 0) {
         return track.height();
      }

      byte[] entry = track.sampleEntry();
      return entry != null && entry.length >= 36 ? Mp4BoxReader.u16(entry, 34) : 0;
   }

   private static void putUnityMatrix(ByteBuffer buffer) {
      buffer.putInt(0x00010000);
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(0x00010000);
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(0);
      buffer.putInt(0x40000000);
   }

   private static byte[] concat(String type, byte[]... children) {
      long size = 8L;

      for (byte[] child : children) {
         size += child.length;
      }

      if (size > 0xFFFFFFFFL) {
         throw new IllegalStateException("MP4 盒过大: " + type);
      }

      byte[] out = new byte[(int)size];
      ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt(out.length);
      putFourCc(buffer, type);

      for (byte[] child : children) {
         buffer.put(child);
      }

      return out;
   }

   private static byte[] boxHeader(long size, String type) {
      if (size > 0xFFFFFFFFL) {
         throw new IllegalStateException("mdat 过大: " + size);
      }

      byte[] header = new byte[8];
      ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
      buffer.putInt((int)size);
      putFourCc(buffer, type);
      return header;
   }

   private static void putFourCc(ByteBuffer buffer, String type) {
      byte[] text = type.getBytes(StandardCharsets.ISO_8859_1);
      buffer.put(text, 0, 4);
   }

   private static void copySample(SeekableByteChannel source, OutputStream out, long offset, int size, ByteBuffer buffer) throws IOException {
      int remaining = size;
      long position = offset;

      while (remaining > 0) {
         buffer.clear();
         buffer.limit(Math.min(buffer.capacity(), remaining));
         source.position(position);
         int read = source.read(buffer);
         if (read <= 0) {
            throw new EOFException("读取样本字节时提前结束: offset=%d size=%d".formatted(offset, size));
         }

         out.write(buffer.array(), 0, read);
         position += read;
         remaining -= read;
      }
   }
}
