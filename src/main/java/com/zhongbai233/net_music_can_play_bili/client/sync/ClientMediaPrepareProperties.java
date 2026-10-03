package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

public final class ClientMediaPrepareProperties {
   static final String AUDIO_PREPARE_THREADS = "ncpb.bili.audio.prepare_threads";
   static final String MODERN_PREPARE_TIMEOUT_SECONDS = "ncpb.bili.audio.prepare_timeout_seconds";
   static final String MP4_PREPARE_TIMEOUT_SECONDS = "ncpb.mp4.client_prepare_timeout_seconds";
   static final String PAD_PREPARE_TIMEOUT_SECONDS = "ncpb.pad.client_prepare_timeout_seconds";

   private ClientMediaPrepareProperties() {
   }

   public static ClientMediaPrepareProperties.Settings settings() {
      return new ClientMediaPrepareProperties.Settings(
         NcpbSystemProperties.intValue("ncpb.bili.audio.prepare_threads", 2),
         NcpbSystemProperties.longValue("ncpb.bili.audio.prepare_timeout_seconds", 20L),
         NcpbSystemProperties.longValue("ncpb.mp4.client_prepare_timeout_seconds", 12L),
         NcpbSystemProperties.longValue("ncpb.pad.client_prepare_timeout_seconds", 12L)
      );
   }

   public record Settings(int audioPrepareThreads, long modernPrepareTimeoutSeconds, long mp4PrepareTimeoutSeconds, long padPrepareTimeoutSeconds) {
      public Settings(int audioPrepareThreads, long modernPrepareTimeoutSeconds, long mp4PrepareTimeoutSeconds, long padPrepareTimeoutSeconds) {
         audioPrepareThreads = Math.max(1, audioPrepareThreads);
         modernPrepareTimeoutSeconds = Math.max(3L, modernPrepareTimeoutSeconds);
         mp4PrepareTimeoutSeconds = Math.max(3L, mp4PrepareTimeoutSeconds);
         padPrepareTimeoutSeconds = Math.max(3L, padPrepareTimeoutSeconds);
         this.audioPrepareThreads = audioPrepareThreads;
         this.modernPrepareTimeoutSeconds = modernPrepareTimeoutSeconds;
         this.mp4PrepareTimeoutSeconds = mp4PrepareTimeoutSeconds;
         this.padPrepareTimeoutSeconds = padPrepareTimeoutSeconds;
      }
   }
}
