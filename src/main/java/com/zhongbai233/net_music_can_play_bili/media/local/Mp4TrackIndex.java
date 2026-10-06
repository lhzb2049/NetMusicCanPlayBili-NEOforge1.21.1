package com.zhongbai233.net_music_can_play_bili.media.local;

import java.util.Locale;

/**
 * 一条轨的完整样本表（阶段 2 的中间表示）。
 *
 * <p>样本数据本身**不在这里**：只记「每个样本在源文件里的绝对偏移 + 字节数 + 时长 + 合成偏移 +
 * 是否关键帧」，让重封装可以按任意顺序、任意分片把字节原样搬走（源文件里音视频是交织的，
 * 但每条轨自己的样本在时间上是递增的，因此按轨遍历就是顺序 I/O）。
 *
 * <p>刻意不依赖任何 Minecraft 类，也不做解码，纯粹是容器层的搬运，可离线逐字节验证。
 */
public final class Mp4TrackIndex {
   /** 本模组原生解码器只认这两条 codecId（见 VideoNativeDecoder / Fmp4NativeVideoDecodeParser）。 */
   public static final int CODEC_ID_H264 = 7;
   public static final int CODEC_ID_AV1 = 13;

   private final int trackId;
   private final String handlerType;
   private final String codecFourCc;
   private final int timescale;
   private final long durationUnits;
   private final int width;
   private final int height;
   private final int channelCount;
   private final int sampleRate;
   private final byte[] sampleEntry;
   private final long[] sampleOffsets;
   private final int[] sampleSizes;
   private final int[] sampleDurations;
   private final int[] compositionOffsets;
   private final boolean[] syncSamples;
   private final long totalSampleBytes;

   Mp4TrackIndex(
      int trackId,
      String handlerType,
      String codecFourCc,
      int timescale,
      long durationUnits,
      int width,
      int height,
      int channelCount,
      int sampleRate,
      byte[] sampleEntry,
      long[] sampleOffsets,
      int[] sampleSizes,
      int[] sampleDurations,
      int[] compositionOffsets,
      boolean[] syncSamples
   ) {
      this.trackId = trackId;
      this.handlerType = handlerType;
      this.codecFourCc = codecFourCc;
      this.timescale = Math.max(1, timescale);
      this.durationUnits = Math.max(0L, durationUnits);
      this.width = Math.max(0, width);
      this.height = Math.max(0, height);
      this.channelCount = Math.max(0, channelCount);
      this.sampleRate = Math.max(0, sampleRate);
      this.sampleEntry = sampleEntry;
      this.sampleOffsets = sampleOffsets;
      this.sampleSizes = sampleSizes;
      this.sampleDurations = sampleDurations;
      this.compositionOffsets = compositionOffsets;
      this.syncSamples = syncSamples;
      long bytes = 0L;

      for (int size : sampleSizes) {
         if (size > 0) {
            bytes += size;
         }
      }

      this.totalSampleBytes = bytes;
   }

   public int trackId() {
      return this.trackId;
   }

   public String handlerType() {
      return this.handlerType;
   }

   public String codecFourCc() {
      return this.codecFourCc;
   }

   public int timescale() {
      return this.timescale;
   }

   public long durationUnits() {
      return this.durationUnits;
   }

   public int width() {
      return this.width;
   }

   public int height() {
      return this.height;
   }

   public int channelCount() {
      return this.channelCount;
   }

   public int sampleRate() {
      return this.sampleRate;
   }

   /** stsd 里第一个样本描述盒的原始字节（含头部），重封装时原样搬进新 moov。 */
   public byte[] sampleEntry() {
      return this.sampleEntry;
   }

   public boolean isAudio() {
      return "soun".equals(this.handlerType);
   }

   public boolean isVideo() {
      return "vide".equals(this.handlerType);
   }

   public int sampleCount() {
      return this.sampleSizes.length;
   }

   public long sampleOffset(int index) {
      return this.sampleOffsets[index];
   }

   public int sampleSize(int index) {
      return this.sampleSizes[index];
   }

   public int sampleDuration(int index) {
      return this.sampleDurations[index];
   }

   /** 合成时间偏移（ctts）；源文件没有 ctts 时所有样本都是 0。 */
   public int compositionOffset(int index) {
      return this.compositionOffsets == null ? 0 : this.compositionOffsets[index];
   }

   public boolean hasCompositionOffsets() {
      return this.compositionOffsets != null;
   }

   /** 是否关键帧：源文件没有 stss 时按规范视为**每个样本都是同步样本**。 */
   public boolean isSyncSample(int index) {
      return this.syncSamples == null || this.syncSamples[index];
   }

   public boolean hasSyncSampleTable() {
      return this.syncSamples != null;
   }

   public long totalSampleBytes() {
      return this.totalSampleBytes;
   }

   /** 时间轴总时长（毫秒）；mdhd 的 duration 不可信时退化为样本时长之和。 */
   public long durationMillis() {
      long units = this.durationUnits > 0L ? this.durationUnits : this.sumDurations();
      return Math.max(0L, Math.round(units * 1000.0 / this.timescale));
   }

   public long sumDurations() {
      long total = 0L;

      for (int duration : this.sampleDurations) {
         if (duration > 0) {
            total += duration;
         }
      }

      return total;
   }

   /** 平均帧率（视频轨用）；样本数或时长不足时返回 0。 */
   public int averageFps() {
      int count = this.sampleCount();
      long units = this.sumDurations();
      if (count <= 0 || units <= 0L) {
         return 0;
      }

      return Math.max(1, (int)Math.round(count * (double)this.timescale / units));
   }

   /** 映射到本模组原生视频解码器的 codecId；不是它支持的两类编码时返回 0。 */
   public int videoCodecId() {
      String codec = this.codecFourCc.toLowerCase(Locale.ROOT);
      return switch (codec) {
         case "avc1", "avc3" -> CODEC_ID_H264;
         case "av01" -> CODEC_ID_AV1;
         default -> 0;
      };
   }

   /** 音频只走 AAC（mp4a + esds），其余编码本阶段不接（见 LocalVideoRemuxer 的说明）。 */
   public boolean isAacAudio() {
      return this.isAudio() && "mp4a".equalsIgnoreCase(this.codecFourCc) && containsEsds();
   }

   private boolean containsEsds() {
      byte[] entry = this.sampleEntry;
      if (entry == null) {
         return false;
      }

      for (int i = 0; i + 4 <= entry.length; i++) {
         if (entry[i] == 'e' && entry[i + 1] == 's' && entry[i + 2] == 'd' && entry[i + 3] == 's') {
            return true;
         }
      }

      return false;
   }

   public String describe() {
      return this.isVideo()
         ? "track#%d vide %s %dx%d %d样本 %dHz当量 时长=%dms 关键帧表=%s".formatted(
            this.trackId, this.codecFourCc, this.width, this.height, this.sampleCount(), this.timescale, this.durationMillis(), this.hasSyncSampleTable())
         : "track#%d %s %s %d样本 %dHz/%dch 时长=%dms".formatted(
            this.trackId, this.handlerType, this.codecFourCc, this.sampleCount(), this.sampleRate, this.channelCount, this.durationMillis());
   }
}
