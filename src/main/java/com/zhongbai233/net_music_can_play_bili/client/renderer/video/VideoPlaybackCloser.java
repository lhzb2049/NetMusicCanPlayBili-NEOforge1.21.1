package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

final class VideoPlaybackCloser {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long CLOSE_TIMEOUT_MILLIS = VideoPipelineProperties.timing().decoderRestartCloseTimeoutMillis();
   private final VideoPlaybackInstance owner;

   VideoPlaybackCloser(VideoPlaybackInstance owner) {
      this.owner = owner;
   }

   void stop() {
      if (!this.owner.stopRequested) {
         this.owner.stopRequested = true;
         this.owner.restartState = VideoDecoderRestartState.STOPPED;
         this.owner.restartInProgress = false;
         long now = System.nanoTime();
         AutoCloseable decoder = this.owner.decoder;
         CompletableFuture<Void> threadExit = this.owner.decodeExit;
         CompletableFuture<Void> nativeTermination = decoder instanceof Fmp4NativeVideoDecoder nativeDecoder
            ? nativeDecoder.terminationFuture()
            : CompletableFuture.completedFuture(null);
         CompletableFuture<Void> closeReturned = decoder != null
            ? MediaCloseExecutor.closeAsyncStrict(decoder, "video decoder " + this.owner.sessionId())
            : CompletableFuture.completedFuture(null);
         CompletableFuture<Void> renderRelease = new CompletableFuture<>();
         this.owner.physicalCloseHandoff.attachClose(closeReturned, nativeTermination, threadExit);
         this.owner.physicalCloseHandoff.seal(renderRelease);
         EnumSet<VideoCloseDiagnostics.Phase> required = EnumSet.of(
            VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED, VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED
         );
         if (decoder != null) {
            required.add(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED);
         }

         if (threadExit != null && !threadExit.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED);
         }

         if (!nativeTermination.isDone()) {
            required.add(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED);
         }

         long closeOperation = VideoCloseDiagnostics.global().begin(this.owner.playbackSessionId, required, now);
         this.owner.running = false;
         this.owner.generation.incrementAndGet();
         this.owner.frameQueue.clear();
         VideoCloseDiagnostics.global().complete(closeOperation, VideoCloseDiagnostics.Phase.FRAME_QUEUE_CLEARED, System.nanoTime());
         this.owner.decoder = null;
         observeRequiredSignals(closeOperation, required, closeReturned, threadExit, nativeTermination);
         Thread thread = this.owner.decodeThread;
         if (thread != null) {
            thread.interrupt();
         }

         if (threadExit != null && !threadExit.isDone()) {
            this.scheduleDecodeExitDiagnostic(thread, threadExit);
         }

         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.isSameThread()) {
            this.releaseTextureAndReport(closeOperation, renderRelease);
         } else {
            minecraft.execute(() -> this.releaseTextureAndReport(closeOperation, renderRelease));
         }
      }
   }

   private static void observeRequiredSignals(
      long closeOperation,
      EnumSet<VideoCloseDiagnostics.Phase> required,
      CompletableFuture<Void> closeReturned,
      CompletableFuture<Void> threadExit,
      CompletableFuture<Void> nativeTermination
   ) {
      VideoCloseDiagnostics diagnostics = VideoCloseDiagnostics.global();
      if (required.contains(VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED)) {
         diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODER_CLOSE_RETURNED, closeReturned);
      }

      if (required.contains(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED)) {
         diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, threadExit);
      }

      if (required.contains(VideoCloseDiagnostics.Phase.NATIVE_TERMINATED)) {
         diagnostics.observe(closeOperation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, nativeTermination);
      }
   }

   private void scheduleDecodeExitDiagnostic(Thread thread, CompletableFuture<Void> threadExit) {
      CompletableFuture.delayedExecutor(Math.max(1L, CLOSE_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS)
         .execute(
            () -> {
               if (!threadExit.isDone()) {
                  if (thread == null) {
                     LOGGER.error("视频 decode exit 在 stop 后仍 pending，但实例没有 decode thread: session={}", this.owner.sessionId());
                  } else {
                     StackTraceElement[] trace = thread.getStackTrace();
                     StringBuilder location = new StringBuilder();
                     int limit = Math.min(12, trace.length);

                     for (int index = 0; index < limit; index++) {
                        if (index > 0) {
                           location.append(" <- ");
                        }

                        location.append(trace[index]);
                     }

                     LOGGER.error(
                        "视频 decode exit 在 stop 后仍 pending: session={} thread={} alive={} state={} stack={}",
                        new Object[]{this.owner.sessionId(), thread.getName(), thread.isAlive(), thread.getState(), location}
                     );
                  }
               }
            }
         );
   }

   void abandonBeforeStart() {
      if (!this.owner.running && !this.owner.stopRequested) {
         this.owner.stopRequested = true;
         this.owner.restartState = VideoDecoderRestartState.STOPPED;
         CompletableFuture<Void> renderRelease = new CompletableFuture<>();
         this.owner.physicalCloseHandoff.seal(renderRelease);
         renderRelease.complete(null);
      } else {
         this.stop();
      }
   }

   private void releaseTextureAndReport(long closeOperation, CompletableFuture<Void> renderRelease) {
      try {
         this.owner.textures.release();
         renderRelease.complete(null);
      } catch (Error | RuntimeException var8) {
         renderRelease.completeExceptionally(var8);
         throw var8;
      } finally {
         VideoCloseDiagnostics.global().complete(closeOperation, VideoCloseDiagnostics.Phase.RENDER_RELEASE_RETURNED, System.nanoTime());
      }
   }
}
