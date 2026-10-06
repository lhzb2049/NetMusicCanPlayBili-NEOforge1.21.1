package com.zhongbai233.net_music_can_play_bili.media.local;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 普通（progressive）MP4 的索引：把 {@code moov} 里的样本表读成每条轨的 {@link Mp4TrackIndex}。
 *
 * <p>只读元数据，不读样本：视频文件里样本可能有几个 GB，但 moov 通常只有几 KB~几 MB。
 * 因此 {p parse(Path)} 用 {@link SeekableByteChannel} 跳过 mdat 只把 moov 拉进内存；
 * 这种「moov 在文件末尾」的布局（手机录屏、OBS 录制都常见）才不会被迫整文件读一遍。
 *
 * <p>阶段 2 的输入范围刻意收窄到「单文件、非分片、未加密、样本表完整」的 progressive MP4：
 * 分片 MP4（含 moof）本来就该直接喂给现有解码器，加密文件（encv/enca）本模组没有解密能力。
 */
public final class ProgressiveMp4Index {
   private static final int MAX_HEADER_PROBE = 16;

   private final long fileSize;
   private final List<Mp4TrackIndex> tracks;
   private final boolean hasEditLists;

   private ProgressiveMp4Index(long fileSize, List<Mp4TrackIndex> tracks, boolean hasEditLists) {
      this.fileSize = fileSize;
      this.tracks = List.copyOf(tracks);
      this.hasEditLists = hasEditLists;
   }

   public long fileSize() {
      return this.fileSize;
   }

   public List<Mp4TrackIndex> tracks() {
      return this.tracks;
   }

   /** 第一条视频轨；没有视频轨返回 null。 */
   public Mp4TrackIndex videoTrack() {
      for (Mp4TrackIndex track : this.tracks) {
         if (track.isVideo()) {
            return track;
         }
      }

      return null;
   }

   /** 第一条音频轨；没有音频轨返回 null。 */
   public Mp4TrackIndex audioTrack() {
      for (Mp4TrackIndex track : this.tracks) {
         if (track.isAudio()) {
            return track;
         }
      }

      return null;
   }

   /** 源文件带编辑列表（edts/elst）—— 阶段 2 不套用编辑，只在日志里说明。 */
   public boolean hasEditLists() {
      return this.hasEditLists;
   }

   public String describe() {
      StringBuilder text = new StringBuilder("MP4 ").append(this.fileSize).append("B ").append(this.tracks.size()).append("轨");

      for (Mp4TrackIndex track : this.tracks) {
         text.append(" | ").append(track.describe());
      }

      if (this.hasEditLists) {
         text.append(" | 含 edts（未套用）");
      }

      return text.toString();
   }

   /** 从文件解析：只把 moov 读进内存。 */
   public static ProgressiveMp4Index parse(Path file) throws IOException {
      long size = Files.size(file);
      byte[] moov = null;
      boolean editLists = false;

      try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
         ByteBuffer header = ByteBuffer.allocate(MAX_HEADER_PROBE).order(ByteOrder.BIG_ENDIAN);
         long position = 0L;

         while (position + 8L <= size) {
            header.clear();
            header.limit(8);
            readFully(channel, position, header);
            long boxSize = header.getInt(0) & 0xFFFFFFFFL;
            String type = fourCc(header, 4);
            int headerSize = 8;
            if (boxSize == 1L) {
               header.clear();
               header.limit(16);
               readFully(channel, position, header);
               boxSize = header.getLong(8);
               headerSize = 16;
            } else if (boxSize == 0L) {
               boxSize = size - position;
            }

            if (boxSize < headerSize || position + boxSize > size) {
               throw new IOException("MP4 盒尺寸非法: type=%s size=%d offset=%d".formatted(type, boxSize, position));
            }

            if ("moov".equals(type)) {
               long payload = boxSize - headerSize;
               if (payload > Mp4BoxReader.MAX_BOX_BYTES) {
                  throw new IOException("MP4 moov 过大: " + payload + "B");
               }

               moov = new byte[(int)payload];
               readFully(channel, position + headerSize, ByteBuffer.wrap(moov));
            }

            position += boxSize;
         }
      }

      if (moov == null) {
         throw new IOException("MP4 里没有 moov 盒（可能是未写完的录制文件）");
      }

      return build(size, moov);
   }

   /** 从内存解析（离线验证用）：样本偏移仍然是文件内绝对偏移。 */
   public static ProgressiveMp4Index parseBytes(byte[] data) throws IOException {
      byte[] moov = findTopLevelBox(data, "moov");
      if (moov == null) {
         throw new IOException("MP4 里没有 moov 盒");
      }

      return build(data.length, moov);
   }

   private static ProgressiveMp4Index build(long fileSize, byte[] moov) throws IOException {
      List<Mp4TrackIndex> tracks = new ArrayList<>();
      boolean editLists = false;

      for (Mp4BoxReader.Box trak : Mp4BoxReader.children(moov, 0, moov.length)) {
         if (!"trak".equals(trak.type())) {
            continue;
         }

         if (Mp4BoxReader.findIn(moov, trak, "edts") != null) {
            editLists = true;
         }

         Mp4TrackIndex parsed = parseTrak(moov, trak, fileSize);
         if (parsed != null) {
            tracks.add(parsed);
         }
      }

      if (tracks.isEmpty()) {
         throw new IOException("MP4 里没有可用的 trak");
      }

      return new ProgressiveMp4Index(fileSize, tracks, editLists);
   }

   private static Mp4TrackIndex parseTrak(byte[] moov, Mp4BoxReader.Box trak, long fileSize) throws IOException {
      Mp4BoxReader.Box tkhd = Mp4BoxReader.findIn(moov, trak, "tkhd");
      Mp4BoxReader.Box mdia = Mp4BoxReader.findIn(moov, trak, "mdia");
      if (mdia == null) {
         throw new IOException("trak 缺少 mdia");
      }

      Mp4BoxReader.Box mdhd = Mp4BoxReader.findIn(moov, mdia, "mdhd");
      Mp4BoxReader.Box hdlr = Mp4BoxReader.findIn(moov, mdia, "hdlr");
      Mp4BoxReader.Box stbl = Mp4BoxReader.path(moov, mdia, "minf", "stbl");
      if (mdhd == null || hdlr == null || stbl == null) {
         throw new IOException("trak 缺少 mdhd/hdlr/stbl");
      }

      int trackId = tkhd == null ? 0 : readTrackId(moov, tkhd);
      String handlerType = readHandlerType(moov, hdlr);
      int[] movieTimescale = readTimescale(moov, mdhd);
      int timescale = movieTimescale[0];
      long durationUnits = movieTimescale[1];
      int[] dimensions = readDimensions(moov, tkhd);
      Mp4BoxReader.Box stsd = Mp4BoxReader.findIn(moov, stbl, "stsd");
      if (stsd == null) {
         throw new IOException("stbl 缺少 stsd（无法确定编码）");
      }

      byte[] sampleEntry = firstSampleEntry(moov, stsd);
      String codec = Mp4BoxReader.fourCc(sampleEntry, 4).toLowerCase(Locale.ROOT);
      if ("encv".equals(codec) || "enca".equals(codec)) {
         throw new IOException("MP4 轨道已加密（" + codec + "），本模组无法播放");
      }

      int[] audio = readAudioSampleEntry(sampleEntry);
      int[] sizes = readSampleSizes(moov, stbl);
      int[] durations = readSampleDurations(moov, stbl, sizes.length);
      int[] compositionOffsets = readCompositionOffsets(moov, stbl, sizes.length);
      boolean[] sync = readSyncSamples(moov, stbl, sizes.length);
      long[] offsets = readSampleOffsets(moov, stbl, sizes);
      for (int i = 0; i < sizes.length; i++) {
         long end = offsets[i] + Math.max(0, sizes[i]);
         if (offsets[i] < 0L || end > fileSize) {
            throw new IOException(
               "样本 %d 超出文件范围: offset=%d size=%d fileSize=%d".formatted(i, offsets[i], sizes[i], fileSize)
            );
         }
      }

      return new Mp4TrackIndex(
         trackId,
         handlerType,
         codec,
         timescale,
         durationUnits,
         dimensions[0],
         dimensions[1],
         audio[0],
         audio[1],
         sampleEntry,
         offsets,
         sizes,
         durations,
         compositionOffsets,
         sync
      );
   }

   private static int readTrackId(byte[] data, Mp4BoxReader.Box tkhd) {
      int offset = tkhd.payloadStart();
      long trackId = Mp4BoxReader.u32(data, data[offset] == 1 ? offset + 20 : offset + 12);
      return trackId > 0L ? (int)trackId : 1;
   }

   private static int[] readDimensions(byte[] data, Mp4BoxReader.Box tkhd) {
      if (tkhd == null) {
         return new int[]{0, 0};
      }

      int offset = tkhd.payloadStart() + (data[tkhd.payloadStart()] == 1 ? 88 : 76);
      if (offset + 8 > tkhd.end()) {
         return new int[]{0, 0};
      }

      int width = (int)(Mp4BoxReader.u32(data, offset) >> 16);
      int height = (int)(Mp4BoxReader.u32(data, offset + 4) >> 16);
      return new int[]{Math.max(0, width), Math.max(0, height)};
   }

   /** 返回 {@code [timescale, durationUnits]}。 */
   private static int[] readTimescale(byte[] data, Mp4BoxReader.Box mdhd) {
      int offset = mdhd.payloadStart();
      boolean version1 = data[offset] == 1;
      int timescale = (int)Mp4BoxReader.u32(data, offset + (version1 ? 20 : 12));
      long duration = version1 ? Mp4BoxReader.u64(data, offset + 24) : Mp4BoxReader.u32(data, offset + 16);
      return new int[]{Math.max(1, timescale), (int)Math.max(0L, duration)};
   }

   private static String readHandlerType(byte[] data, Mp4BoxReader.Box hdlr) {
      int offset = hdlr.payloadStart() + 8;
      return offset + 4 <= hdlr.end() ? Mp4BoxReader.fourCc(data, offset) : "????";
   }

   private static byte[] firstSampleEntry(byte[] data, Mp4BoxReader.Box stsd) {
      int offset = stsd.payloadStart() + 4;
      if (offset + 4 > stsd.end()) {
         throw new IndexOutOfBoundsException("stsd 不完整");
      }

      int count = (int)Mp4BoxReader.u32(data, offset);
      offset += 4;
      if (count <= 0 || offset + 8 > stsd.end()) {
         throw new IndexOutOfBoundsException("stsd 没有样本描述");
      }

      long entrySize = Mp4BoxReader.u32(data, offset);
      if (entrySize < 8L || offset + entrySize > stsd.end()) {
         throw new IndexOutOfBoundsException("stsd 样本描述尺寸非法");
      }

      byte[] entry = new byte[(int)entrySize];
      System.arraycopy(data, offset, entry, 0, entry.length);
      return entry;
   }

   /** {@code [channels, sampleRate]}；非音频样本描述返回 0。偏移量相对**整个**样本描述盒（含 8 字节头部）。 */
   private static int[] readAudioSampleEntry(byte[] entry) {
      if (entry.length < 36) {
         return new int[]{0, 0};
      }

      int channels = Mp4BoxReader.u16(entry, 24);
      int sampleRate = (int)(Mp4BoxReader.u32(entry, 32) >> 16);
      if (sampleRate <= 0) {
         sampleRate = channels > 0 ? 48000 : 0;
      }

      return new int[]{Math.max(0, channels), Math.max(0, sampleRate)};
   }

   private static int[] readSampleSizes(byte[] data, Mp4BoxReader.Box stbl) throws IOException {
      Mp4BoxReader.Box stsz = Mp4BoxReader.findIn(data, stbl, "stsz");
      if (stsz == null) {
         throw new IOException("stbl 缺少 stsz");
      }

      int offset = stsz.payloadStart();
      int constantSize = (int)Mp4BoxReader.u32(data, offset + 4);
      int count = (int)Mp4BoxReader.u32(data, offset + 8);
      if (count < 0 || count > 50_000_000) {
         throw new IOException("stsz 样本数非法: " + count);
      }

      int[] sizes = new int[count];
      if (constantSize > 0) {
         java.util.Arrays.fill(sizes, constantSize);
         return sizes;
      }

      if (offset + 12 + (long)count * 4L > stsz.end()) {
         throw new IOException("stsz 表被截断: count=" + count);
      }

      for (int i = 0; i < count; i++) {
         sizes[i] = (int)Mp4BoxReader.u32(data, offset + 12 + i * 4);
      }

      return sizes;
   }

   private static int[] readSampleDurations(byte[] data, Mp4BoxReader.Box stbl, int sampleCount) throws IOException {
      Mp4BoxReader.Box stts = Mp4BoxReader.findIn(data, stbl, "stts");
      if (stts == null) {
         throw new IOException("stbl 缺少 stts");
      }

      int offset = stts.payloadStart();
      int entries = (int)Mp4BoxReader.u32(data, offset + 4);
      int[] durations = new int[sampleCount];
      int cursor = 0;
      int fallback = 0;

      for (int i = 0; i < entries; i++) {
         int entryOffset = offset + 8 + i * 8;
         if (entryOffset + 8 > stts.end()) {
            break;
         }

         int count = (int)Mp4BoxReader.u32(data, entryOffset);
         int delta = (int)Mp4BoxReader.u32(data, entryOffset + 4);
         if (count <= 0) {
            continue;
         }

         if (fallback == 0) {
            fallback = delta;
         }

         for (int n = 0; n < count && cursor < sampleCount; n++) {
            durations[cursor++] = delta;
         }
      }

      for (int i = cursor; i < sampleCount; i++) {
         durations[i] = Math.max(1, fallback);
      }

      return durations;
   }

   private static int[] readCompositionOffsets(byte[] data, Mp4BoxReader.Box stbl, int sampleCount) {
      Mp4BoxReader.Box ctts = Mp4BoxReader.findIn(data, stbl, "ctts");
      if (ctts == null) {
         return null;
      }

      int offset = ctts.payloadStart();
      int version = data[offset] & 0xFF;
      int entries = (int)Mp4BoxReader.u32(data, offset + 4);
      int[] offsets = new int[sampleCount];
      int cursor = 0;
      boolean anyNonZero = false;

      for (int i = 0; i < entries && cursor < sampleCount; i++) {
         int entryOffset = offset + 8 + i * 8;
         if (entryOffset + 8 > ctts.end()) {
            break;
         }

         int count = (int)Mp4BoxReader.u32(data, entryOffset);
         int value = version == 0
            ? (int)Mp4BoxReader.u32(data, entryOffset + 4)
            : Mp4BoxReader.i32(data, entryOffset + 4);

         for (int n = 0; n < count && cursor < sampleCount; n++) {
            offsets[cursor++] = value;
            anyNonZero |= value != 0;
         }
      }

      return anyNonZero ? offsets : null;
   }

   private static boolean[] readSyncSamples(byte[] data, Mp4BoxReader.Box stbl, int sampleCount) {
      Mp4BoxReader.Box stss = Mp4BoxReader.findIn(data, stbl, "stss");
      if (stss == null) {
         return null;
      }

      int offset = stss.payloadStart();
      int entries = (int)Mp4BoxReader.u32(data, offset + 4);
      boolean[] sync = new boolean[sampleCount];

      for (int i = 0; i < entries; i++) {
         int entryOffset = offset + 8 + i * 4;
         if (entryOffset + 4 > stss.end()) {
            break;
         }

         int number = (int)Mp4BoxReader.u32(data, entryOffset);
         if (number >= 1 && number <= sampleCount) {
            sync[number - 1] = true;
         }
      }

      return sync;
   }

   /**
    * 由 stsc（样本→块）+ stco/co64（块→文件偏移）展开出每个样本的绝对偏移。
    *
    * <p>与 stsz 的样本数对不上就报错：宁可拒绝播放，也不要把错位的字节喂给解码器。
    */
   private static long[] readSampleOffsets(byte[] data, Mp4BoxReader.Box stbl, int[] sizes) throws IOException {
      Mp4BoxReader.Box stsc = Mp4BoxReader.findIn(data, stbl, "stsc");
      Mp4BoxReader.Box stco = Mp4BoxReader.findIn(data, stbl, "stco");
      Mp4BoxReader.Box co64 = stco == null ? Mp4BoxReader.findIn(data, stbl, "co64") : null;
      if (stsc == null || stco == null && co64 == null) {
         throw new IOException("stbl 缺少 stsc/stco，样本偏移无法定位");
      }

      Mp4BoxReader.Box chunkOffsets = stco != null ? stco : co64;
      boolean wide = stco == null;
      int chunkCount = (int)Mp4BoxReader.u32(data, chunkOffsets.payloadStart() + 4);
      if (chunkCount <= 0) {
         throw new IOException("stco 里没有块");
      }

      long[] offsets = new long[sizes.length];
      int stscOffset = stsc.payloadStart();
      int stscEntries = (int)Mp4BoxReader.u32(data, stscOffset + 4);
      if (stscEntries <= 0) {
         throw new IOException("stsc 里没有条目");
      }

      int cursor = 0;
      for (int i = 0; i < stscEntries && cursor < sizes.length; i++) {
         int entryOffset = stscOffset + 8 + i * 12;
         if (entryOffset + 12 > stsc.end()) {
            break;
         }

         int firstChunk = (int)Mp4BoxReader.u32(data, entryOffset);
         int samplesPerChunk = (int)Mp4BoxReader.u32(data, entryOffset + 4);
         int lastChunk = chunkCount;
         if (i + 1 < stscEntries) {
            int nextOffset = stscOffset + 8 + (i + 1) * 12;
            if (nextOffset + 12 <= stsc.end()) {
               lastChunk = (int)Mp4BoxReader.u32(data, nextOffset) - 1;
            }
         }

         if (firstChunk < 1 || samplesPerChunk < 0) {
            throw new IOException("stsc 条目非法: firstChunk=%d samplesPerChunk=%d".formatted(firstChunk, samplesPerChunk));
         }

         for (int chunk = firstChunk; chunk <= lastChunk && chunk <= chunkCount && cursor < sizes.length; chunk++) {
            int chunkOffsetEntry = chunkOffsets.payloadStart() + 8 + (chunk - 1) * (wide ? 8 : 4);
            if (chunkOffsetEntry + (wide ? 8 : 4) > chunkOffsets.end()) {
               throw new IOException("stco 条目越界: chunk=" + chunk);
            }

            long position = wide ? Mp4BoxReader.u64(data, chunkOffsetEntry) : Mp4BoxReader.u32(data, chunkOffsetEntry);

            for (int n = 0; n < samplesPerChunk && cursor < sizes.length; n++) {
               offsets[cursor] = position;
               position += Math.max(0, sizes[cursor]);
               cursor++;
            }
         }
      }

      if (cursor < sizes.length) {
         throw new IOException("样本表不完整: stsz=%d 但只定位到 %d 个样本".formatted(sizes.length, cursor));
      }

      return offsets;
   }

   private static byte[] findTopLevelBox(byte[] data, String type) {
      int position = 0;

      while (position + 8 <= data.length) {
         long size = Mp4BoxReader.u32(data, position);
         String boxType = Mp4BoxReader.fourCc(data, position + 4);
         int headerSize = 8;
         if (size == 1L) {
            if (position + 16 > data.length) {
               return null;
            }

            size = Mp4BoxReader.u64(data, position + 8);
            headerSize = 16;
         } else if (size == 0L) {
            size = data.length - position;
         }

         if (size < headerSize || position + size > data.length) {
            return null;
         }

         if (type.equals(boxType)) {
            byte[] payload = new byte[(int)size - headerSize];
            System.arraycopy(data, position + headerSize, payload, 0, payload.length);
            return payload;
         }

         position += (int)size;
      }

      return null;
   }

   private static void readFully(SeekableByteChannel channel, long position, ByteBuffer buffer) throws IOException {
      channel.position(position);
      long read = 0L;
      int wanted = buffer.limit();

      while (read < wanted) {
         int n = channel.read(buffer);
         if (n < 0) {
            throw new EOFException("MP4 在 offset=" + (position + read) + " 处提前结束");
         }

         read += n;
      }

      buffer.flip();
   }

   private static String fourCc(ByteBuffer buffer, int offset) {
      byte[] text = new byte[4];

      for (int i = 0; i < 4; i++) {
         text[i] = buffer.get(offset + i);
      }

      return new String(text, java.nio.charset.StandardCharsets.ISO_8859_1);
   }
}
