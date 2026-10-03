package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

abstract class VideoBillboardSessionSupport extends VideoBillboardDecoderSupport {
   public static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      BlockPos anchorPos,
      String decoderOverride
   ) {
      startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos, false, decoderOverride);
   }

   public static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      BlockPos anchorPos,
      boolean preferNative,
      String decoderOverride
   ) {
      startSynced(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, 0L, anchorPos, preferNative, decoderOverride);
   }

   public static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      BlockPos anchorPos,
      boolean preferNative,
      String decoderOverride
   ) {
      startSynced(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         sessionId,
         startOffsetMillis,
         totalMillis,
         anchorPos != null ? List.of(anchorPos) : List.of(),
         preferNative,
         decoderOverride
      );
   }

   public static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      boolean preferNative,
      String decoderOverride
   ) {
      startSynced(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         sessionId,
         startOffsetMillis,
         totalMillis,
         anchorPositions,
         (BlockPos)null,
         preferNative,
         decoderOverride
      );
   }

   public static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      BlockPos turntablePos,
      boolean preferNative,
      String decoderOverride
   ) {
      VideoPlaybackAnchor anchor = VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis));
      startSynced(
         videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis, anchorPositions, anchor, preferNative, decoderOverride
      );
   }

   public static void startLiveSession(
      String busUrl, int targetWidth, int targetHeight, int fps, String sessionId, Collection<BlockPos> projectorPositions, BlockPos livePos
   ) {
      if (sessionId != null && !sessionId.isBlank() && busUrl != null && !busUrl.isBlank()) {
         VideoPlaybackAnchor anchor = new LiveVideoPlaybackAnchor(livePos, sessionId);
         startOrUpdateInstance(busUrl, targetWidth, targetHeight, fps, 7, sessionId, 0L, 0L, projectorPositions, anchor, true, null);
      }
   }

   public static void startSyncedCandidates(
      List<BiliVideoStreamResolver.VideoCandidate> candidates,
      int targetWidth,
      int targetHeight,
      int fps,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      BlockPos turntablePos,
      boolean preferNative,
      String decoderOverride
   ) {
      if (candidates != null && !candidates.isEmpty()) {
         BiliVideoStreamResolver.VideoCandidate preferred = candidates.get(0);
         VideoPlaybackAnchor anchor = VideoPlaybackAnchor.turntable(turntablePos, sessionId, Math.max(0L, totalMillis));
         startOrUpdateInstance(
            preferred.url(),
            targetWidth,
            targetHeight,
            fps,
            preferred.codecId(),
            sessionId,
            startOffsetMillis,
            totalMillis,
            anchorPositions,
            anchor,
            preferNative,
            decoderOverride,
            candidates
         );
      }
   }

   static void startSynced(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      VideoPlaybackAnchor anchor,
      boolean preferNative,
      String decoderOverride
   ) {
      if (sessionId != null && !sessionId.isBlank()) {
         startOrUpdateInstance(
            videoUrl,
            targetWidth,
            targetHeight,
            fps,
            codecId,
            sessionId,
            startOffsetMillis,
            totalMillis,
            anchorPositions,
            anchor,
            preferNative,
            decoderOverride
         );
      } else {
         startInternal(
            videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, sessionId, startOffsetMillis, totalMillis, anchorPositions, true
         );
      }
   }

   protected static void startOrUpdateInstance(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      VideoPlaybackAnchor anchor,
      boolean preferNative,
      String decoderOverride
   ) {
      startOrUpdateInstance(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         sessionId,
         startOffsetMillis,
         totalMillis,
         anchorPositions,
         anchor,
         preferNative,
         decoderOverride,
         List.of(new BiliVideoStreamResolver.VideoCandidate(videoUrl, codecId, targetWidth, targetHeight, fps, 0))
      );
   }

   protected static void startOrUpdateInstance(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      VideoPlaybackAnchor anchor,
      boolean preferNative,
      String decoderOverride,
      List<BiliVideoStreamResolver.VideoCandidate> candidates
   ) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (parsedSessionId != null) {
         String normalizedSessionId = parsedSessionId.value();
         if (!ClientMemoryProtection.allowMediaStart()) {
            stopIfSession(normalizedSessionId);
         } else {
            List<BlockPos> projectors = immutablePositions(anchorPositions);
            boolean hasHolographicConsumer = hasHolographicTurntableConsumer(anchor);
            if (projectors.isEmpty() && !hasHolographicConsumer) {
               stopIfSession(normalizedSessionId);
            } else {
               VideoPlaybackInstance existing = SESSION_INSTANCES.get(normalizedSessionId);
               long normalizedOffset = Math.max(0L, startOffsetMillis);
               if (existing != null) {
                  existing.replaceProjectors(projectors);
                  if (existing.canChaseToOffset(normalizedOffset) || existing.requestSyncedReseek(normalizedOffset)) {
                     return;
                  }
               }

               VideoPlaybackInstance instance = new VideoPlaybackInstance(
                  videoUrl,
                  targetWidth,
                  targetHeight,
                  fps,
                  codecId,
                  normalizedSessionId,
                  normalizedOffset,
                  Math.max(0L, totalMillis),
                  projectors,
                  anchor,
                  preferNative,
                  decoderOverride,
                  candidates
               );
               startProjectionInstance(instance);
            }
         }
      }
   }

   protected static synchronized void startProjectionInstance(VideoPlaybackInstance instance) {
      String sessionId = instance.sessionId();
      Object ownerKey = instance.replacementOwnerKey();
      VideoBillboardState.PendingProjectionStart duplicate = PENDING_PROJECTION_STARTS.get(sessionId);
      if (duplicate != null && Objects.equals(duplicate.ownerKey(), ownerKey)) {
         List<BlockPos> positions = instance.projectorPositions();
         if (!positions.isEmpty()) {
            duplicate.instance().replaceProjectors(positions);
         }

         if (instance.hasGuiConsumer()) {
            duplicate.instance().setGuiConsumer(true);
         }

         instance.abandonBeforeStart();
      } else {
         for (VideoBillboardState.PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
            if (pending.sessionId().equals(sessionId) || Objects.equals(pending.ownerKey(), ownerKey)) {
               cancelPendingProjectionStart(pending);
            }
         }

         ProjectionReplacementGate.CloseHandoff replacementBarrier = ProjectionReplacementGate.CloseHandoff.completed();

         for (VideoPlaybackInstance current : SESSION_INSTANCES.instances()) {
            if (current.sessionId().equals(sessionId) || Objects.equals(current.replacementOwnerKey(), ownerKey)) {
               replacementBarrier = composeCloseHandoffs(replacementBarrier, current.closeHandoff());
               SESSION_INSTANCES.remove(current.sessionId(), current);
            }
         }

         ProjectionReplacementGate.Intent<Object> intent = PROJECTION_REPLACEMENTS.beginIntent(ownerKey, sessionId, replacementBarrier);
         VideoBillboardState.PendingProjectionStart pendingx = new VideoBillboardState.PendingProjectionStart(sessionId, ownerKey, instance, intent);
         PENDING_PROJECTION_STARTS.put(sessionId, pendingx);
         continueProjectionStart(pendingx, PROJECTION_REPLACEMENTS.evaluate(intent), null);
      }
   }

   protected static synchronized void continueProjectionStart(
      VideoBillboardState.PendingProjectionStart pending, ProjectionReplacementGate.Decision decision, Throwable failure
   ) {
      if (PENDING_PROJECTION_STARTS.get(pending.sessionId()) != pending || !PROJECTION_REPLACEMENTS.isCurrent(pending.intent())) {
         abandonPendingProjectionStart(pending, false, failure);
      } else if (failure != null || decision == ProjectionReplacementGate.Decision.FAIL_CLOSED) {
         abandonPendingProjectionStart(pending, true, failure);
      } else if (decision == ProjectionReplacementGate.Decision.WAIT) {
         PROJECTION_REPLACEMENTS.waitFor(pending.intent(), PROJECTION_REPLACEMENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .whenComplete(
               (next, error) -> executeProjectionContinuation(
                  () -> continueProjectionStart(pending, next != null ? next : ProjectionReplacementGate.Decision.FAIL_CLOSED, error)
               )
            );
      } else {
         AtomicBoolean published = new AtomicBoolean(false);

         boolean committed;
         try {
            committed = PROJECTION_REPLACEMENTS.commitIfOpen(pending.intent(), () -> {
               if (PENDING_PROJECTION_STARTS.get(pending.sessionId()) != pending) {
                  throw new IllegalStateException("projection replacement intent lost before publication");
               } else {
                  PROJECTION_REPLACEMENTS.retainCommitted(pending.intent(), pending.instance().closeHandoff());
                  SESSION_INSTANCES.replace(pending.sessionId(), pending.instance());
                  pending.instance().start();
                  PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
                  PENDING_SESSIONS.clearLoading(pending.sessionId());
                  published.set(true);
               }
            });
         } catch (Error | RuntimeException var6) {
            SESSION_INSTANCES.remove(pending.sessionId(), pending.instance());
            PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
            pending.instance().abandonBeforeStart();
            PENDING_SESSIONS.markFailure(pending.sessionId(), pending.instance().projectorPositions());
            LOGGER.error("投影视频替换实例发布或启动失败，已回滚并保持 fail-closed: session={} owner={}", new Object[]{pending.sessionId(), pending.ownerKey(), var6});
            return;
         }

         if (!committed || !published.get()) {
            abandonPendingProjectionStart(pending, false, null);
         }
      }
   }

   protected static void executeProjectionContinuation(Runnable continuation) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.isSameThread()) {
         continuation.run();
      } else {
         minecraft.execute(continuation);
      }
   }

   protected static void abandonPendingProjectionStart(VideoBillboardState.PendingProjectionStart pending, boolean markFailure, Throwable failure) {
      PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending);
      pending.instance().abandonBeforeStart();
      if (markFailure) {
         PENDING_SESSIONS.markFailure(pending.sessionId(), pending.instance().projectorPositions());
         LOGGER.error("投影视频旧实例未正常物理收敛，禁止启动替换实例: session={} owner={}", new Object[]{pending.sessionId(), pending.ownerKey(), failure});
      }
   }

   protected static synchronized void cancelPendingProjectionStart(VideoBillboardState.PendingProjectionStart pending) {
      if (PENDING_PROJECTION_STARTS.remove(pending.sessionId(), pending)) {
         PROJECTION_REPLACEMENTS.cancelIntent(pending.intent());
         pending.instance().abandonBeforeStart();
      }
   }

   protected static synchronized void cancelPendingProjectionStart(String sessionId) {
      VideoBillboardState.PendingProjectionStart pending = PENDING_PROJECTION_STARTS.get(sessionId);
      if (pending != null) {
         cancelPendingProjectionStart(pending);
      }
   }

   protected static synchronized void cancelAllPendingProjectionStarts() {
      for (VideoBillboardState.PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
         cancelPendingProjectionStart(pending);
      }
   }

   protected static synchronized void detachPendingProjectionConsumer(BlockPos projectorPos) {
      for (VideoBillboardState.PendingProjectionStart pending : List.copyOf(PENDING_PROJECTION_STARTS.values())) {
         pending.instance().removeProjector(projectorPos);
         if (!pending.instance().hasVideoConsumer()) {
            cancelPendingProjectionStart(pending);
         }
      }
   }

   protected static void disposeSessionInstance(VideoPlaybackInstance instance) {
      Object ownerKey = instance.replacementOwnerKey();
      ProjectionReplacementGate.CloseHandoff handoff = instance.closeHandoff();
      PROJECTION_REPLACEMENTS.retainCloseHandoff(ownerKey, instance.sessionId(), handoff);
      instance.stop();
   }

   protected static ProjectionReplacementGate.CloseHandoff composeCloseHandoffs(
      ProjectionReplacementGate.CloseHandoff retained, ProjectionReplacementGate.CloseHandoff proposed
   ) {
      return retained != null && retained != proposed
         ? new ProjectionReplacementGate.CloseHandoff(
            CompletableFuture.allOf(retained.closeReturned(), proposed.closeReturned()),
            CompletableFuture.allOf(retained.nativeTermination(), proposed.nativeTermination()),
            CompletableFuture.allOf(retained.decodeExit(), proposed.decodeExit()),
            CompletableFuture.allOf(retained.renderRelease(), proposed.renderRelease())
         )
         : proposed;
   }

   protected static void startInternal(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      boolean catchUpDropsEnabled
   ) {
      startInternal(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         preferNative,
         decoderOverride,
         sessionId,
         startOffsetMillis,
         totalMillis,
         anchorPositions,
         catchUpDropsEnabled,
         false
      );
   }

   protected static void startInternal(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      boolean preferNative,
      String decoderOverride,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      Collection<BlockPos> anchorPositions,
      boolean catchUpDropsEnabled,
      boolean forceRgbaOutput
   ) {
      if (ClientMemoryProtection.allowMediaStart()) {
         if (videoUrl != null && !videoUrl.isBlank()) {
            String normalizedSession = sessionId != null ? sessionId : "";
            long normalizedOffset = Math.max(0L, startOffsetMillis);
            if (!normalizedSession.isBlank() && LEGACY_WORKER.isRunning() && LEGACY_PREVIEW.matchesSession(normalizedSession)) {
               replaceActiveProjectors(anchorPositions);
               if (isSessionRunningAtOffset(normalizedSession, normalizedOffset)) {
                  return;
               }

               stopForReplace();
            }

            if (!normalizedSession.isBlank() && LEGACY_WORKER.isRunning()) {
               stopForReplace();
            }

            long generation = LEGACY_WORKER.tryBegin();
            if (generation != -1L) {
               hasFrame = false;
               activeNetworkFailure = false;
               width = Math.max(1, targetWidth);
               height = Math.max(1, targetHeight);
               int protectedFps = protectedUploadFps(targetWidth, targetHeight, fps);
               if (protectedFps < Math.max(1, fps)) {
                  LOGGER.warn(
                     "视频分辨率过高，限制上传/显示帧率以保护游戏 FPS: {}x{} @ {}fps -> {}fps。可用 -Dbili.video.protect_game_fps=false 关闭",
                     new Object[]{targetWidth, targetHeight, Math.max(1, fps), protectedFps}
                  );
               }

               activeFps = protectedFps;
               activeStartOffsetMillis = normalizedOffset;
               activeStartNanoTime = System.nanoTime();
               List<BlockPos> projectors = immutablePositions(anchorPositions);
               VideoBillboardState.PlaybackRequest request = new VideoBillboardState.PlaybackRequest(
                  videoUrl,
                  targetWidth,
                  targetHeight,
                  protectedFps,
                  codecId,
                  preferNative,
                  decoderOverride,
                  normalizedSession,
                  startOffsetMillis,
                  totalMillis,
                  projectors,
                  forceRgbaOutput
               );
               LEGACY_PREVIEW.begin(normalizedSession, projectors, request);
               BlockPos primaryProjector = LEGACY_PREVIEW.primaryProjector();
               if (primaryProjector != null) {
                  anchorX = primaryProjector.getX() + 0.5;
                  anchorY = primaryProjector.getY() + 1.8;
                  anchorZ = primaryProjector.getZ() + 0.5;
                  anchorYawDeg = 0.0F;
                  anchorInitialized = true;
               } else {
                  anchorInitialized = false;
               }

               Thread thread = NetMusicThreadFactory.daemonThread(
                  "bili-video-billboard-preview",
                  () -> decodeLoop(
                     videoUrl,
                     targetWidth,
                     targetHeight,
                     protectedFps,
                     codecId,
                     preferNative,
                     decoderOverride,
                     startOffsetMillis,
                     totalMillis,
                     generation,
                     catchUpDropsEnabled,
                     forceRgbaOutput
                  )
               );
               if (LEGACY_WORKER.bindWorker(generation, thread)) {
                  thread.start();
                  LOGGER.info(
                     "视频 billboard 预览已启动: {}x{} @ {}fps, renderBackend={}, decodeFormat={}, catchUpDrops={}",
                     new Object[]{
                        width,
                        height,
                        activeFps,
                        RENDER_BACKEND,
                        YUV_DECODE_BACKEND
                           ? (isCustomYuvShaderAvailable() ? yuvDecodeFormat().name() + "→RGB(shader)" : yuvDecodeFormat().name() + "→RGBA(cpu/iris-fallback)")
                           : "RGBA",
                        catchUpDropsEnabled
                     }
                  );
               }
            }
         }
      }
   }

   protected static int protectedUploadFps(int frameWidth, int frameHeight, int requestedFps) {
      int fps = Math.max(1, requestedFps);
      if (!PROTECT_GAME_FPS) {
         return fps;
      } else {
         long pixels = (long)Math.max(1, frameWidth) * Math.max(1, frameHeight);
         if (pixels >= 28000000L) {
            return Math.min(fps, Math.max(1, PROTECTED_8K_FPS));
         } else {
            return pixels >= 4800000L ? Math.min(fps, Math.max(1, PROTECTED_4K_FPS)) : fps;
         }
      }
   }

   public static void startTestPattern(int targetWidth, int targetHeight, int fps) {
      long generation = LEGACY_WORKER.tryBegin();
      if (generation != -1L) {
         hasFrame = false;
         activeNetworkFailure = false;
         anchorInitialized = false;
         width = Math.max(1, targetWidth);
         height = Math.max(1, targetHeight);
         activeFps = Math.max(1, fps);
         activeStartOffsetMillis = 0L;
         activeStartNanoTime = System.nanoTime();
         Thread thread = NetMusicThreadFactory.daemonThread(
            "bili-video-billboard-test-pattern", () -> decodeTestPatternLoop(targetWidth, targetHeight, fps, generation)
         );
         if (LEGACY_WORKER.bindWorker(generation, thread)) {
            thread.start();
            LOGGER.info("视频 billboard 本地测试图预览已启动: {}x{} @ {}fps, pixelMode={}", new Object[]{targetWidth, targetHeight, fps, VideoFrameUploader.pixelMode()});
         }
      }
   }

   public static void stop() {
      cancelAllPendingProjectionStarts();
      SESSION_INSTANCES.clear();
      PENDING_SESSIONS.clear();
      LegacyPreviewWorkerLifecycle.Detached<Thread, AutoCloseable> detached = LEGACY_WORKER.stopAndDetach();
      hasFrame = false;
      activeNetworkFailure = false;
      anchorInitialized = false;
      activeFps = 0;
      activeStartOffsetMillis = 0L;
      activeStartNanoTime = 0L;
      LEGACY_PREVIEW.clear();
      berManagedProjectorPositions.clear();
      BER_SUBMITTED_PROJECTORS.clear();
      PROJECTOR_VISIBILITY_CACHE.clear();
      resetLocalRenderAnchors();
      closeActiveDecoderAsync(detached.decoder());
      Thread thread = detached.worker();
      if (thread != null) {
         thread.interrupt();
      }

      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.isSameThread()) {
         releaseTexture();
      } else {
         minecraft.execute(VideoBillboardUploadSupport::releaseTexture);
      }
   }

   protected static void stopForReplace() {
      LegacyPreviewWorkerLifecycle.Detached<Thread, AutoCloseable> detached = LEGACY_WORKER.stopAndDetach();
      hasFrame = false;
      activeNetworkFailure = false;
      LEGACY_PREVIEW.clearForReplacement();
      activeFps = 0;
      activeStartOffsetMillis = 0L;
      activeStartNanoTime = 0L;
      closeActiveDecoderAsync(detached.decoder());
      Thread thread = detached.worker();
      if (thread != null) {
         thread.interrupt();
      }

      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.isSameThread()) {
         releaseTexture();
      } else {
         minecraft.execute(VideoBillboardUploadSupport::releaseTexture);
      }
   }

   public static void stopIfSession(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      cancelPendingProjectionStart(normalized);
      PENDING_SESSIONS.clearSession(normalized);
      PlaybackSessionId.parse(normalized)
         .ifPresent(playbackSessionId -> PROJECTOR_IMMEDIATE_POSES.keySet().removeIf(key -> key.playbackSessionId().equals(playbackSessionId)));
      SESSION_INSTANCES.remove(normalized);
      if (!normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized)) {
         stop();
      }
   }

   public static void stopIfProjector(BlockPos projectorPos) {
      if (projectorPos != null) {
         berManagedProjectorPositions.remove(projectorPos);
         BER_SUBMITTED_PROJECTORS.removeIf(key -> key.projectorPos().equals(projectorPos));
         PROJECTOR_VISIBILITY_CACHE.remove(projectorPos);
         PROJECTOR_IMMEDIATE_POSES.keySet().removeIf(key -> key.projectorPos().equals(projectorPos));
         PENDING_SESSIONS.detachProjector(projectorPos);
         detachPendingProjectionConsumer(projectorPos);
         SESSION_INSTANCES.forEach(instance -> instance.removeProjector(projectorPos));
         SESSION_INSTANCES.removeIf(instance -> !instance.hasProjectors() && !instance.hasVideoConsumer());
         boolean requiredProjector = LEGACY_PREVIEW.requiresProjector();
         LEGACY_PREVIEW.detachProjector(projectorPos);
         if (requiredProjector && LEGACY_PREVIEW.projectors().isEmpty()) {
            stop();
         }
      }
   }

   public static void attachProjectorToTurntable(BlockPos turntablePos, BlockPos projectorPos) {
      if (turntablePos != null && projectorPos != null) {
         berManagedProjectorPositions.add(projectorPos.immutable());

         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos)) {
               instance.addProjector(projectorPos);
            }
         }
      }
   }

   public static boolean isProjectorRenderedByBer(BlockPos projectorPos) {
      return projectorPos != null && berManagedProjectorPositions.contains(projectorPos);
   }

   public static void markProjectorSubmittedByBer(String sessionId, BlockPos projectorPos) {
      PlaybackSessionId.parse(sessionId).ifPresent(playbackSessionId -> markProjectorSubmittedByBer(playbackSessionId, projectorPos));
   }

   public static void markProjectorSubmittedByBer(PlaybackSessionId playbackSessionId, BlockPos projectorPos) {
      if (playbackSessionId != null && projectorPos != null) {
         BER_SUBMITTED_PROJECTORS.markSubmitted(new VideoBillboardState.BerProjectorSubmission(playbackSessionId, projectorPos.immutable()));
      }
   }

   static boolean wasProjectorRecentlySubmittedByBer(String sessionId, BlockPos projectorPos) {
      PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      return playbackSessionId != null && projectorPos != null
         ? BER_SUBMITTED_PROJECTORS.wasRecentlySubmitted(new VideoBillboardState.BerProjectorSubmission(playbackSessionId, projectorPos))
         : false;
   }

   static void beginBerVisibilityFrame() {
      BER_SUBMITTED_PROJECTORS.beginFrame();
   }

   public static boolean hasSessionForTurntable(BlockPos turntablePos) {
      if (turntablePos == null) {
         return false;
      } else {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos)) {
               return true;
            }
         }

         return false;
      }
   }

   public static boolean hasSessionForTurntable(BlockPos turntablePos, String sessionId) {
      if (turntablePos != null && sessionId != null && !sessionId.isBlank()) {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.isForTurntable(turntablePos) && instance.isSession(sessionId)) {
               return true;
            }
         }

         return LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted() && LEGACY_PREVIEW.matchesSession(sessionId);
      } else {
         return false;
      }
   }

   public static boolean isSessionRunning(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
      return instance != null
         ? instance.isRunning()
         : LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted() && !normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized);
   }

   public static synchronized void updateSessionProjectors(String sessionId, Collection<BlockPos> projectorPositions) {
      String normalized = sessionId != null ? sessionId : "";
      if (!normalized.isBlank()) {
         List<BlockPos> positions = immutablePositions(projectorPositions);
         PENDING_SESSIONS.updateProjectors(normalized, positions);
         VideoBillboardState.PendingProjectionStart pending = PENDING_PROJECTION_STARTS.get(normalized);
         if (pending != null) {
            pending.instance().replaceProjectors(positions);
            if (!pending.instance().hasVideoConsumer()) {
               cancelPendingProjectionStart(pending);
            }
         } else {
            VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
            if (instance != null) {
               instance.replaceProjectors(projectorPositions);
            } else {
               if (LEGACY_WORKER.isRunning() && LEGACY_PREVIEW.matchesSession(normalized)) {
                  replaceActiveProjectors(projectorPositions);
               }
            }
         }
      }
   }

   public static void beginPendingLoading(String sessionId, Collection<BlockPos> projectorPositions) {
      String normalized = sessionId != null ? sessionId : "";
      List<BlockPos> positions = immutablePositions(projectorPositions);
      if (!normalized.isBlank() && !positions.isEmpty()) {
         PENDING_SESSIONS.beginLoading(normalized, positions);
      }
   }

   public static void clearPendingLoading(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      if (!normalized.isBlank()) {
         PENDING_SESSIONS.clearLoading(normalized);
      }
   }

   protected static boolean hasHolographicTurntableConsumer(VideoPlaybackAnchor anchor) {
      if (anchor == null) {
         return false;
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.level != null) {
            for (HolographicGlassesItem.ScreenBinding binding : HolographicGlassesClient.screenBindings()) {
               if (binding.source() != null
                  && binding.source().isTurntable()
                  && minecraft.level.dimension().equals(binding.source().dimension())
                  && anchor.isForTurntable(binding.source().pos())) {
                  return true;
               }
            }

            return false;
         } else {
            return false;
         }
      }
   }

   public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis) {
      return isSessionRunningAtOffset(sessionId, requestedOffsetMillis, 1500L);
   }

   public static boolean isSessionRunningAtOffset(String sessionId, long requestedOffsetMillis, long toleranceMillis) {
      if (!isSessionRunning(sessionId)) {
         return false;
      } else {
         VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
         if (instance != null) {
            return instance.isRunningAtOffset(Math.max(0L, requestedOffsetMillis), Math.max(0L, toleranceMillis));
         } else {
            long expectedOffset = activeStartOffsetMillis + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1000000L);
            return Math.abs(expectedOffset - Math.max(0L, requestedOffsetMillis)) < Math.max(0L, toleranceMillis);
         }
      }
   }

   public static boolean canSessionChaseToOffset(String sessionId, long requestedOffsetMillis) {
      if (!isSessionRunning(sessionId)) {
         return false;
      } else {
         VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
         return instance != null
            ? instance.canChaseToOffset(Math.max(0L, requestedOffsetMillis))
            : isSessionRunningAtOffset(sessionId, requestedOffsetMillis, VideoPipelineProperties.chaseWindowMillis());
      }
   }

   public static boolean isSessionWaitingForFirstFrame(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      if (normalized.isBlank()) {
         return false;
      } else {
         VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
         return instance != null
            ? instance.ensureFirstFrameProgress()
            : LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted() && LEGACY_PREVIEW.matchesSession(normalized) && !hasFrame;
      }
   }

   public static VideoBillboardState.VideoStatus getStatusForProjector(BlockPos projectorPos) {
      for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
         if (instance.containsProjector(projectorPos)) {
            return instance.status();
         }
      }

      if (!LEGACY_WORKER.isRunning() || !LEGACY_WORKER.isStarted()) {
         return VideoBillboardState.VideoStatus.empty();
      } else {
         return projectorPos != null && !LEGACY_PREVIEW.projectors().isEmpty() && !LEGACY_PREVIEW.projectors().contains(projectorPos)
            ? VideoBillboardState.VideoStatus.empty()
            : new VideoBillboardState.VideoStatus(width, height, activeFps, hasFrame, !LEGACY_PREVIEW.sessionId().isBlank());
      }
   }

   public static VideoBillboardState.VideoSyncStatus getSyncStatus(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
      if (instance != null) {
         return new VideoBillboardState.VideoSyncStatus(
            instance.isRunning(),
            instance.hasFrame(),
            instance.mediaMillis(),
            instance.queuedMediaMillis(),
            instance.status().width(),
            instance.status().height(),
            instance.status().fps()
         );
      } else if (LEGACY_WORKER.isRunning() && LEGACY_WORKER.isStarted() && !normalized.isBlank() && LEGACY_PREVIEW.matchesSession(normalized)) {
         long mediaMillis = activeStartOffsetMillis + Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1000000L);
         return new VideoBillboardState.VideoSyncStatus(true, hasFrame, mediaMillis, -1L, width, height, activeFps);
      } else {
         return VideoBillboardState.VideoSyncStatus.empty();
      }
   }

   protected static void replaceActiveProjectors(Collection<BlockPos> projectorPositions) {
      PROJECTOR_VISIBILITY_CACHE.clear();
      anchorInitialized = false;
      LEGACY_PREVIEW.replaceProjectors(immutablePositions(projectorPositions));
   }
}
