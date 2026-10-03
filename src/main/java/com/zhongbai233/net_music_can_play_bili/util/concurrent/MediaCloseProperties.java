package com.zhongbai233.net_music_can_play_bili.util.concurrent;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.concurrent.TimeUnit;

public final class MediaCloseProperties {
   static final String EXECUTOR_THREADS = "ncpb.media.close.threads";
   static final String EXECUTOR_QUEUE_CAPACITY = "ncpb.media.close.queue";
   static final String OPENAL_SOFT_TIMEOUT_MILLIS = "ncpb.close_diag.openal_soft_ms";
   static final String OPENAL_HARD_TIMEOUT_MILLIS = "ncpb.close_diag.openal_hard_ms";
   static final String OPENAL_RETRY_MILLIS = "ncpb.close_diag.openal_retry_ms";
   static final String VIDEO_SOFT_TIMEOUT_MILLIS = "ncpb.close_diag.video_soft_ms";
   static final String VIDEO_HARD_TIMEOUT_MILLIS = "ncpb.close_diag.video_hard_ms";

   private MediaCloseProperties() {
   }

   static MediaCloseProperties.ExecutorConfig executor() {
      return new MediaCloseProperties.ExecutorConfig(
         NcpbSystemProperties.intValue("ncpb.media.close.threads", 2), NcpbSystemProperties.intValue("ncpb.media.close.queue", 32)
      );
   }

   public static MediaCloseProperties.Timeouts openAlTimeouts() {
      return new MediaCloseProperties.Timeouts(
         positiveMillisToNanos(NcpbSystemProperties.longValue("ncpb.close_diag.openal_soft_ms", 500L)),
         positiveMillisToNanos(NcpbSystemProperties.longValue("ncpb.close_diag.openal_hard_ms", 3000L))
      );
   }

   public static long openAlRetryNanos() {
      return positiveMillisToNanos(NcpbSystemProperties.longValue("ncpb.close_diag.openal_retry_ms", 500L));
   }

   public static MediaCloseProperties.Timeouts videoTimeouts() {
      return new MediaCloseProperties.Timeouts(
         positiveMillisToNanos(NcpbSystemProperties.longValue("ncpb.close_diag.video_soft_ms", 3000L)),
         positiveMillisToNanos(NcpbSystemProperties.longValue("ncpb.close_diag.video_hard_ms", 6000L))
      );
   }

   private static long positiveMillisToNanos(long millis) {
      return Math.max(1L, TimeUnit.MILLISECONDS.toNanos(Math.max(0L, millis)));
   }

   record ExecutorConfig(int threads, int queueCapacity) {
      ExecutorConfig(int threads, int queueCapacity) {
         threads = Math.max(1, threads);
         queueCapacity = Math.max(1, queueCapacity);
         this.threads = threads;
         this.queueCapacity = queueCapacity;
      }
   }

   public record Timeouts(long softTimeoutNanos, long hardTimeoutNanos) {
      public Timeouts(long softTimeoutNanos, long hardTimeoutNanos) {
         softTimeoutNanos = Math.max(1L, softTimeoutNanos);
         hardTimeoutNanos = Math.max(softTimeoutNanos, hardTimeoutNanos);
         this.softTimeoutNanos = softTimeoutNanos;
         this.hardTimeoutNanos = hardTimeoutNanos;
      }
   }
}
