package com.zhongbai233.net_music_can_play_bili.media.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 本地 progressive MP4 → 两条**单轨 fMP4**（视频一条、音频一条）的重封装器。
 *
 * <p>为什么拆成两条单轨，而不是一条双轨：本模组的 fMP4 消费者是各读各的 ——
 * 视频侧 {@code Fmp4NativeVideoDecoder} 只认「一个 moof 一个 traf」并只取第一条轨，
 * 音频侧 {@code Fmp4AudioStreamSeeker} 也是单轨流。两条单轨文件各自 Range 播放，
 * 正好复用现成的解码、寻址、错峰逻辑，不需要改动任何消费端。
 *
 * <p>样本字节**原样搬运**、不重编码：只重写容器（盒结构、分片、索引），因此画质与音质与源文件逐字节等价。
 */
public final class LocalVideoRemuxer {
   private LocalVideoRemuxer() {
   }

   /** 重封装参数。 */
   public record Settings(int fragmentMillis, long maxSourceBytes, long maxFragmentBytes, boolean includeAudio) {
      public static Settings defaults() {
         return new Settings(
            Fmp4TrackWriter.DEFAULT_FRAGMENT_MILLIS,
            1024L * 1024L * 1024L,
            Fmp4TrackWriter.DEFAULT_MAX_FRAGMENT_BYTES,
            true
         );
      }
   }

   /** 重封装结果；{@code audioFile} 为 null 表示这条源没有可用/支持的音频。 */
   public record Result(
      Path videoFile,
      Path audioFile,
      Mp4TrackIndex videoTrack,
      Mp4TrackIndex audioTrack,
      Fmp4TrackWriter.Result videoLayout,
      Fmp4TrackWriter.Result audioLayout,
      String notes
   ) {
      public long durationMillis() {
         long video = this.videoLayout != null ? this.videoLayout.durationMillis() : 0L;
         long audio = this.audioLayout != null ? this.audioLayout.durationMillis() : 0L;
         return Math.max(video, audio);
      }

      public int width() {
         return this.videoTrack != null ? this.videoTrack.width() : 0;
      }

      public int height() {
         return this.videoTrack != null ? this.videoTrack.height() : 0;
      }

      public int fps() {
         return this.videoTrack != null ? this.videoTrack.averageFps() : 0;
      }

      public int codecId() {
         return this.videoTrack != null ? this.videoTrack.videoCodecId() : 0;
      }

      public String audioCodec() {
         return this.audioTrack != null ? this.audioTrack.codecFourCc() : "none";
      }

      public long totalBytes() {
         return (this.videoLayout != null ? this.videoLayout.fileSize() : 0L)
            + (this.audioLayout != null ? this.audioLayout.fileSize() : 0L);
      }
   }

   /**
    * 重封装一个文件。
    *
    * @param source   源 MP4（只读）
    * @param workDir  产物目录（不存在会创建）
    * @param key      产物文件名前缀，调用方保证唯一即可
    */
   public static Result remux(Path source, Path workDir, String key, Settings settings) throws IOException {
      Settings effective = settings != null ? settings : Settings.defaults();
      long size = Files.size(source);
      if (size <= 0L) {
         throw new IOException("本地视频为空文件: " + source.getFileName());
      }

      if (size > effective.maxSourceBytes()) {
         throw new IOException(
            "本地视频 %dMB 超过重封装上限 %dMB（可用 -Dncpb.local.video.max_source_bytes= 调整）"
               .formatted(size / 1048576L, effective.maxSourceBytes() / 1048576L)
         );
      }

      ProgressiveMp4Index index = ProgressiveMp4Index.parse(source);
      Mp4TrackIndex video = index.videoTrack();
      if (video == null) {
         throw new IOException("MP4 里没有视频轨（只有音频的文件请按音频方式播放）");
      }

      if (video.videoCodecId() == 0) {
         throw new IOException(
            "不支持的视频编码 '%s'（本地视频目前只支持 H.264(avc1/avc3) 与 AV1(av01)）"
               .formatted(video.codecFourCc().toUpperCase(Locale.ROOT))
         );
      }

      Files.createDirectories(workDir);
      StringBuilder notes = new StringBuilder();
      if (index.hasEditLists()) {
         notes.append("源文件含 edts 编辑列表（未套用）; ");
      }

      Path videoFile = workDir.resolve(key + "-v.mp4");
      Path audioFile = null;

      try {
         Fmp4TrackWriter.Result videoLayout = Fmp4TrackWriter.write(
            video, source, videoFile, effective.fragmentMillis(), effective.maxFragmentBytes()
         );
         Mp4TrackIndex audio = index.audioTrack();
         Mp4TrackIndex usableAudio = null;
         Fmp4TrackWriter.Result audioLayout = null;
         if (audio != null && effective.includeAudio()) {
            if (audio.isAacAudio()) {
               audioFile = workDir.resolve(key + "-a.mp4");
               audioLayout = Fmp4TrackWriter.write(
                  audio, source, audioFile, effective.fragmentMillis(), effective.maxFragmentBytes()
               );
               usableAudio = audio;
            } else {
               notes.append("音频编码 '").append(audio.codecFourCc()).append("' 本阶段不接（仅 AAC）; ");
            }
         } else if (audio != null) {
            notes.append("音频重封装已按配置关闭; ");
         }

         return new Result(videoFile, audioFile, video, usableAudio, videoLayout, audioLayout, notes.toString());
      } catch (IOException | RuntimeException error) {
         deleteQuietly(videoFile);
         deleteQuietly(audioFile);
         throw error;
      }
   }

   private static void deleteQuietly(Path file) {
      if (file != null) {
         try {
            Files.deleteIfExists(file);
         } catch (IOException ignored) {
         }
      }
   }
}
