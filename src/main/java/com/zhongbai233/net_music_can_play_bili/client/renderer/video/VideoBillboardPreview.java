package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleVideoStatePolicy;
import com.zhongbai233.net_music_can_play_bili.media.VideoSurfaceBrightness;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortWorldRenderEvents;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent.Pre;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

@EventBusSubscriber(
   modid = "net_music_can_play_bili",
   value = {Dist.CLIENT}
)
public final class VideoBillboardPreview extends VideoBillboardSessionSupport {
   private static final double VIDEO_PREWARM_DOT_THRESHOLD = VideoPipelineProperties.offscreen().prewarmDotThreshold();

   public static float cameraRelativeBackOffset(Matrix4f screenPose, float configuredOffset) {
      float distance = Math.abs(configuredOffset);
      if (screenPose != null && !(distance <= 0.0F)) {
         Vector4f center = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F).mul(screenPose);
         Vector4f positiveZ = new Vector4f(0.0F, 0.0F, 1.0F, 1.0F).mul(screenPose);
         float normalX = positiveZ.x - center.x;
         float normalY = positiveZ.y - center.y;
         float normalZ = positiveZ.z - center.z;
         float towardCameraDot = normalX * -center.x + normalY * -center.y + normalZ * -center.z;
         if (Math.abs(towardCameraDot) < 1.0E-6F) {
            return configuredOffset;
         } else {
            return towardCameraDot > 0.0F ? -distance : distance;
         }
      } else {
         return 0.0F;
      }
   }

   public static boolean isControlConsoleScreenPotentiallyVisible(BlockPos consolePos, Matrix4f elementTransform, float halfWidth, float halfHeight) {
      Minecraft minecraft = Minecraft.getInstance();
      if (consolePos != null
         && elementTransform != null
         && !(halfWidth <= 0.0F)
         && !(halfHeight <= 0.0F)
         && minecraft.level != null
         && minecraft.player != null) {
         Camera camera = minecraft.gameRenderer.getMainCamera();
         Vec3 cameraPos = camera.getPosition();
         float sampleHalfWidth = (float)(halfWidth * VIEW_SAMPLE_EDGE_SCALE);
         float sampleHalfHeight = (float)(halfHeight * VIEW_SAMPLE_EDGE_SCALE);
         float[][] samples = new float[][]{
            {0.0F, 0.0F},
            {sampleHalfWidth, sampleHalfHeight},
            {sampleHalfWidth, -sampleHalfHeight},
            {-sampleHalfWidth, sampleHalfHeight},
            {-sampleHalfWidth, -sampleHalfHeight}
         };

         for (float[] sample : samples) {
            Vector3f point = elementTransform.transformPosition(new Vector3f(sample[0], sample[1], 0.0F));
            Vec3 worldPoint = new Vec3(consolePos.getX() + 0.5 + point.x, consolePos.getY() + 1.55 + point.y, consolePos.getZ() + 0.5 + point.z);
            if (worldPoint.distanceToSqr(cameraPos) <= MAX_RENDER_DISTANCE_SQR
               && isScreenInView(camera, worldPoint.x, worldPoint.y, worldPoint.z, VIDEO_PREWARM_DOT_THRESHOLD)
               && !isOccluded(minecraft, cameraPos, worldPoint, consolePos)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean isProjectorScreenPotentiallyVisible(BlockPos projectorPos) {
      Minecraft minecraft = Minecraft.getInstance();
      return projectorPos != null
            && minecraft.level != null
            && minecraft.player != null
            && minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector
         ? isProjectorScreenRenderable(minecraft, minecraft.gameRenderer.getMainCamera(), projector, VIDEO_PREWARM_DOT_THRESHOLD)
         : false;
   }

   public static VideoBillboardState.ProjectorFrameSnapshot currentProjectorFrame(BlockPos projectorPos) {
      if (projectorPos != null) {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            VideoBillboardState.ProjectorFrameSnapshot snapshot = instance.frameSnapshot(projectorPos);
            if (snapshot.hasFrame()) {
               return snapshot;
            }
         }
      }

      if (!activeNetworkFailure
         || !NETWORK_ERROR_PLACEHOLDER_ENABLED
         || width <= 0
         || height <= 0
         || projectorPos != null && !LEGACY_PREVIEW.projectors().isEmpty() && !LEGACY_PREVIEW.projectors().contains(projectorPos)) {
         if (!hasFrame || width <= 0 || height <= 0) {
            return VideoBillboardState.ProjectorFrameSnapshot.empty();
         } else if (projectorPos != null && !LEGACY_PREVIEW.projectors().isEmpty() && !LEGACY_PREVIEW.projectors().contains(projectorPos)) {
            return VideoBillboardState.ProjectorFrameSnapshot.empty();
         } else {
            VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
            return shouldRenderYuvFrame() && yuvTextures != null
               ? new VideoBillboardState.ProjectorFrameSnapshot(
                  true, true, TEXTURE_ID, yuvTextures.yId(), yuvTextures.uId(), yuvTextures.vId(), yuvTextures.format(), width, height, false, false, 0.0F
               )
               : new VideoBillboardState.ProjectorFrameSnapshot(
                  true, false, TEXTURE_ID, null, null, null, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, width, height, false, false, 0.0F
               );
         }
      } else {
         return networkErrorSnapshot();
      }
   }

   public static VideoBillboardState.ProjectorFrameSnapshot currentProjectorDisplayFrame(BlockPos projectorPos) {
      if (projectorPos != null) {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            VideoBillboardState.ProjectorFrameSnapshot snapshot = instance.displayFrameSnapshot(projectorPos);
            if (snapshot.hasFrame()) {
               return snapshot;
            }
         }

         PendingVideoSessionRegistry.Snapshot<BlockPos> pending = PENDING_SESSIONS.findByProjector(PendingVideoSessionRegistry.State.LOADING, projectorPos);
         if (pending != null) {
            return VideoPlaybackInstance.loadingPlaceholderSnapshot(pending.startedNanoTime());
         }
      }

      return currentProjectorFrame(projectorPos);
   }

   public static VideoBillboardState.ProjectorFrameSnapshot currentTurntableFrame(BlockPos turntablePos) {
      if (turntablePos != null) {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            VideoBillboardState.ProjectorFrameSnapshot snapshot = instance.turntableFrameSnapshot(turntablePos);
            if (snapshot.hasFrame()) {
               return snapshot;
            }
         }
      }

      return VideoBillboardState.ProjectorFrameSnapshot.empty();
   }

   private VideoBillboardPreview() {
   }

   private static VideoBillboardState.ProjectorFrameSnapshot networkErrorSnapshot() {
      return new VideoBillboardState.ProjectorFrameSnapshot(
         true, false, NETWORK_ERROR_PLACEHOLDER_TEXTURE, null, null, null, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, 320, 180, true, false, 0.0F
      );
   }

   public static void start(String videoUrl, int targetWidth, int targetHeight, int fps) {
      start(videoUrl, targetWidth, targetHeight, fps, null);
   }

   public static void start(String videoUrl, int targetWidth, int targetHeight, int fps, String decoderOverride) {
      start(videoUrl, targetWidth, targetHeight, fps, 7, false, decoderOverride);
   }

   public static void start(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId, boolean preferNative, String decoderOverride) {
      startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, "", 0L, 0L, null, true);
   }

   public static void startBenchPreview(String videoUrl, int targetWidth, int targetHeight, int fps, int codecId, boolean preferNative, String decoderOverride) {
      startInternal(videoUrl, targetWidth, targetHeight, fps, codecId, preferNative, decoderOverride, "", 0L, 0L, null, false);
   }

   public static void startPreviewAt(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      boolean preferNative,
      String decoderOverride
   ) {
      startInternal(
         videoUrl,
         targetWidth,
         targetHeight,
         fps,
         codecId,
         preferNative,
         decoderOverride,
         sessionId == null ? "" : sessionId,
         Math.max(0L, startOffsetMillis),
         Math.max(0L, totalMillis),
         null,
         true,
         false
      );
   }

   public static void startRgbaPreviewAt(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      boolean preferNative,
      String decoderOverride
   ) {
      startRgbaPreviewAt(videoUrl, targetWidth, targetHeight, fps, codecId, sessionId, startOffsetMillis, totalMillis, preferNative, decoderOverride, null);
   }

   public static void startRgbaPreviewAt(
      String videoUrl,
      int targetWidth,
      int targetHeight,
      int fps,
      int codecId,
      String sessionId,
      long startOffsetMillis,
      long totalMillis,
      boolean preferNative,
      String decoderOverride,
      UUID sourceId
   ) {
      if (ClientMemoryProtection.allowMediaStart()) {
         String normalized = sessionId != null ? sessionId : "";
         if (normalized.isBlank()) {
            startInternal(
               videoUrl,
               targetWidth,
               targetHeight,
               fps,
               codecId,
               preferNative,
               decoderOverride,
               normalized,
               Math.max(0L, startOffsetMillis),
               Math.max(0L, totalMillis),
               null,
               true,
               true
            );
         } else {
            PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(normalized).orElse(null);
            if (parsedSessionId != null) {
               normalized = parsedSessionId.value();
               long offset = Math.max(0L, startOffsetMillis);
               VideoPlaybackInstance existing = SESSION_INSTANCES.get(normalized);
               if (existing != null) {
                  existing.setGuiConsumer(true);
                  if (existing.isRunningAtOffset(offset, 250L) || existing.requestSyncedReseek(offset)) {
                     return;
                  }
               }

               VideoPlaybackInstance instance = new VideoPlaybackInstance(
                  videoUrl,
                  targetWidth,
                  targetHeight,
                  fps,
                  codecId,
                  normalized,
                  offset,
                  Math.max(0L, totalMillis),
                  List.of(),
                  (VideoPlaybackAnchor)(sourceId != null
                     ? new PreviewVideoPlaybackAnchor(sourceId, normalized, offset, Math.max(0L, totalMillis))
                     : VideoPlaybackAnchor.turntable(null, normalized, Math.max(0L, totalMillis))),
                  preferNative,
                  decoderOverride
               );
               instance.setGuiConsumer(true);
               startProjectionInstance(instance);
            }
         }
      }
   }

   public static VideoBillboardState.ProjectorFrameSnapshot currentPreviewFrame(String sessionId) {
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
      return instance != null ? instance.previewFrameSnapshot() : VideoBillboardState.ProjectorFrameSnapshot.empty();
   }

   public static VideoBillboardState.ControlConsoleVideoSnapshot currentControlConsoleVideo(BlockPos consolePos, boolean sourcePlaying, boolean videoExpected) {
      if (consolePos == null) {
         return null;
      } else {
         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            if (instance.containsProjector(consolePos)) {
               boolean failed = instance.hasTerminalFailure();
               VideoBillboardState.ProjectorFrameSnapshot realFrame = instance.realFrameSnapshot(consolePos);
               ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(sourcePlaying, videoExpected, failed, realFrame.hasFrame());

               VideoBillboardState.ProjectorFrameSnapshot displayFrame = switch (state) {
                  case ACTIVE -> IrisShaderpackCompat.shouldApplyIrisYuvCompatibility() && realFrame.yuv()
                     ? instance.displayFrameSnapshot(consolePos)
                     : realFrame;
                  case ERROR, BUFFERING, IDLE -> controlConsolePlaceholder(state);
               };
               return new VideoBillboardState.ControlConsoleVideoSnapshot(instance.sessionId(), state, displayFrame);
            }
         }

         PendingVideoSessionRegistry.Snapshot<BlockPos> failure = PENDING_SESSIONS.findByProjector(PendingVideoSessionRegistry.State.FAILURE, consolePos);
         if (failure != null) {
            ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(sourcePlaying, videoExpected, true, false);
            VideoBillboardState.ProjectorFrameSnapshot frame = controlConsolePlaceholder(state);
            return new VideoBillboardState.ControlConsoleVideoSnapshot(failure.sessionId(), state, frame);
         } else {
            PendingVideoSessionRegistry.Snapshot<BlockPos> loading = PENDING_SESSIONS.findByProjector(PendingVideoSessionRegistry.State.LOADING, consolePos);
            if (loading != null) {
               ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(sourcePlaying, videoExpected, false, false);
               VideoBillboardState.ProjectorFrameSnapshot frame = controlConsolePlaceholder(state);
               return new VideoBillboardState.ControlConsoleVideoSnapshot(loading.sessionId(), state, frame);
            } else {
               ControlConsoleVideoStatePolicy.State state = ControlConsoleVideoStatePolicy.resolve(sourcePlaying, videoExpected, false, false);
               return new VideoBillboardState.ControlConsoleVideoSnapshot(null, state, controlConsolePlaceholder(state));
            }
         }
      }
   }

   private static VideoBillboardState.ProjectorFrameSnapshot controlConsolePlaceholder(ControlConsoleVideoStatePolicy.State state) {
      ResourceLocation texture = switch (state) {
         case ACTIVE -> throw new IllegalArgumentException("ACTIVE control-console video requires a real frame");
         case ERROR -> CONTROL_CONSOLE_ERROR_TEXTURE;
         case BUFFERING -> CONTROL_CONSOLE_BUFFERING_TEXTURE;
         case IDLE -> CONTROL_CONSOLE_IDLE_TEXTURE;
      };
      return new VideoBillboardState.ProjectorFrameSnapshot(
         true,
         false,
         texture,
         null,
         null,
         null,
         Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA,
         320,
         180,
         true,
         ControlConsoleVideoArtwork.loadingProgressOverlay(state),
         0.0F
      );
   }

   public static boolean hasTerminalFailure(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
      return instance != null && instance.hasTerminalFailure() || PENDING_SESSIONS.hasFailure(normalized);
   }

   public static boolean hasGuiConsumer(String sessionId) {
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
      return instance != null && instance.hasGuiConsumer();
   }

   public static void markPendingFailure(String sessionId, Collection<BlockPos> projectorPositions) {
      String normalized = sessionId != null ? sessionId : "";
      List<BlockPos> positions = immutablePositions(projectorPositions);
      if (!normalized.isBlank() && !positions.isEmpty()) {
         PENDING_SESSIONS.markFailure(normalized, positions);
      }
   }

   public static void detachControlConsoleConsumer(BlockPos consolePos) {
      if (consolePos != null) {
         PENDING_SESSIONS.detachProjector(consolePos);
         detachPendingProjectionConsumer(consolePos);
         SESSION_INSTANCES.forEach(instance -> instance.removeProjector(consolePos));
         SESSION_INSTANCES.removeIf(instance -> !instance.hasVideoConsumer());
      }
   }

   public static VideoBillboardState.ResourceDiagnostics resourceDiagnostics() {
      VideoZombieCloseSupervisor.Snapshot zombies = VideoZombieCloseSupervisor.global().snapshot();
      VideoResourceDiagnosticsCollector.Snapshot snapshot = RESOURCE_DIAGNOSTICS.collect(
         SESSION_INSTANCES.instances(),
         PENDING_SESSIONS.count(PendingVideoSessionRegistry.State.LOADING),
         PENDING_SESSIONS.count(PendingVideoSessionRegistry.State.FAILURE),
         berManagedProjectorPositions.size(),
         zombies.activeZombies(),
         zombies.lateConvergences()
      );
      return new VideoBillboardState.ResourceDiagnostics(
         snapshot.instances(),
         snapshot.runningInstances(),
         snapshot.failedInstances(),
         snapshot.pendingLoading(),
         snapshot.pendingFailure(),
         snapshot.projectorReferences(),
         snapshot.berManagedProjectors(),
         snapshot.guiConsumers(),
         snapshot.activeCloseZombies(),
         snapshot.lateCloseConvergences()
      );
   }

   public static VideoBillboardState.BenchUploadResources benchUploadResources() {
      return new VideoBillboardState.BenchUploadResources(
         LEGACY_TEXTURES.hasRgbaOrPacked(),
         LEGACY_TEXTURES.hasYuv(),
         MemoryResourceTracker.usage(MemoryResourceTracker.Category.TEXTURE_STAGING).currentBytes(),
         MemoryResourceTracker.usage(MemoryResourceTracker.Category.GPU_PBO).currentBytes()
      );
   }

   public static VideoBillboardState.BenchDecoderState benchDecoderState(String sessionId) {
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId != null ? sessionId : "");
      return instance != null
         ? new VideoBillboardState.BenchDecoderState(
            true,
            instance.generationForBench(),
            instance.decoderStartOffsetMillisForBench(),
            instance.restartStateForBench(),
            instance.prewarmVisibleForBench(),
            instance.offscreenPauseActiveForBench(),
            instance.hasFrame()
         )
         : VideoBillboardState.BenchDecoderState.empty();
   }

   public static boolean isScreenAabbPredictedVisible(AABB bounds) {
      Minecraft minecraft = Minecraft.getInstance();
      if (bounds != null && minecraft.player != null && minecraft.level != null) {
         Vec3 cameraPos = minecraft.gameRenderer.getMainCamera().getPosition();
         Vec3 velocity = minecraft.player.getDeltaMovement();
         return VideoVisibilityTrendPredictor.shouldPrewarm(
            cameraPos.x,
            cameraPos.y,
            cameraPos.z,
            velocity.x,
            velocity.y,
            velocity.z,
            minecraft.player.getYRot(),
            minecraft.player.getXRot(),
            minecraft.player.yRotO,
            minecraft.player.xRotO,
            bounds.minX,
            bounds.minY,
            bounds.minZ,
            bounds.maxX,
            bounds.maxY,
            bounds.maxZ,
            VISIBILITY_PROPERTIES.maxRenderDistance(),
            VIEW_DOT_THRESHOLD
         );
      } else {
         return false;
      }
   }

   public static double maxRenderDistance() {
      return VISIBILITY_PROPERTIES.maxRenderDistance();
   }

   public static List<VideoBillboardPreview.VideoDebugSnapshot> videoDebugSnapshots() {
      Minecraft minecraft = Minecraft.getInstance();
      Camera camera = minecraft.gameRenderer.getMainCamera();
      return SESSION_INSTANCES.instances()
         .stream()
         .map(
            instance -> {
               List<VideoBillboardPreview.ProjectorVideoDebugSnapshot> projectors = instance.consumers.projectors().stream().map(pos -> {
                  boolean berManaged = isProjectorRenderedByBer(pos);
                  boolean submitted = wasProjectorRecentlySubmittedByBer(instance.sessionId(), pos);
                  boolean geometryVisible = false;
                  boolean predicted = false;
                  if (minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
                     geometryVisible = isProjectorScreenRenderable(minecraft, camera, projector, VIEW_DOT_THRESHOLD);
                     predicted = isProjectorScreenPredictedVisible(minecraft, camera, projector);
                  } else if (minecraft.level != null) {
                     geometryVisible = submitted;
                     predicted = ControlConsoleRenderer.isPredictivePrewarmActive(pos);
                  }

                  return new VideoBillboardPreview.ProjectorVideoDebugSnapshot(pos, berManaged, submitted, geometryVisible, predicted);
               }).toList();
               VideoBillboardState.VideoStatus status = instance.status();
               MediaVideoTimeline timeline = instance.anchor.timeline();
               return new VideoBillboardPreview.VideoDebugSnapshot(
                  instance.sessionId(),
                  instance.isRunning(),
                  instance.hasTerminalFailure(),
                  instance.decodeAdmissionGranted,
                  instance.prewarmVisible,
                  instance.offscreenPauseActiveForBench(),
                  instance.visualSyncActiveForBench(),
                  instance.hasFrame(),
                  instance.generationForBench(),
                  instance.restartStateForBench(),
                  instance.mediaMillis(),
                  instance.queuedMediaMillis(),
                  timeline.mediaMillis(),
                  timeline.visualMillis(),
                  timeline.pacingMillis(),
                  status.width(),
                  status.height(),
                  status.fps(),
                  status.backend(),
                  projectors
               );
            }
         )
         .toList();
   }

   public static long uploadFrameOnClientThreadForBench(VideoBillboardState.BenchUploadFormat format, byte[] frame, int frameWidth, int frameHeight) {
      return switch ((VideoBillboardState.BenchUploadFormat)Objects.requireNonNull(format, "format")) {
         case RGBA -> uploadFrameSyncForBench(frame, frameWidth, frameHeight);
         case YUV420P -> uploadYuv420FrameSyncForBench(frame, frameWidth, frameHeight);
         case NV12 -> uploadNv12FrameSyncForBench(frame, frameWidth, frameHeight);
      };
   }

   public static void releaseBenchUploadResources() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.isSameThread()) {
         releaseTexture();
      } else {
         CompletableFuture<Void> released = new CompletableFuture<>();
         minecraft.execute(() -> {
            try {
               releaseTexture();
               released.complete(null);
            } catch (Throwable var2) {
               released.completeExceptionally(var2);
            }
         });

         try {
            released.get();
         } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while releasing video bench resources", var3);
         } catch (ExecutionException var4) {
            throw new IllegalStateException("failed to release video bench resources", var4.getCause());
         }
      }
   }

   public static void pumpPreviewFrame(String sessionId) {
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(sessionId);
      if (instance != null) {
         instance.setGuiConsumer(true);
         instance.pumpUploadOnRenderThread();
      }
   }

   public static boolean hasNetworkFailure(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
      return instance != null ? instance.hasNetworkFailure() : activeNetworkFailure && LEGACY_PREVIEW.matchesSession(normalized);
   }

   public static boolean retryNetworkFailure(String sessionId) {
      String normalized = sessionId != null ? sessionId : "";
      VideoPlaybackInstance instance = SESSION_INSTANCES.get(normalized);
      return instance != null ? instance.retryNetworkFailure() : retryLegacyNetworkFailure(normalized);
   }

   public static int retryAllNetworkFailures() {
      int retried = 0;

      for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
         if (instance.retryNetworkFailure()) {
            retried++;
         }
      }

      if (retryLegacyNetworkFailure(LEGACY_PREVIEW.sessionId())) {
         retried++;
      }

      return retried;
   }

   private static boolean retryLegacyNetworkFailure(String sessionId) {
      VideoBillboardState.PlaybackRequest request = LEGACY_PREVIEW.request();
      if (activeNetworkFailure && request != null && (sessionId == null || sessionId.isBlank() || LEGACY_PREVIEW.matchesSession(sessionId))) {
         long retryOffsetMillis = activeStartOffsetMillis;
         if (activeStartNanoTime > 0L) {
            retryOffsetMillis += Math.max(0L, (System.nanoTime() - activeStartNanoTime) / 1000000L);
         }

         stopForReplace();
         startInternal(
            request.videoUrl(),
            request.targetWidth(),
            request.targetHeight(),
            request.fps(),
            request.codecId(),
            request.preferNative(),
            request.decoderOverride(),
            request.sessionId(),
            retryOffsetMillis,
            request.totalMillis(),
            request.anchorPositions(),
            true,
            request.forceRgbaOutput()
         );
         return true;
      } else {
         return false;
      }
   }

   @SubscribeEvent
   public static void onRenderFrame(Pre event) {
      beginBerVisibilityFrame();
      PROJECTOR_IMMEDIATE_POSES.clear();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null && !SESSION_INSTANCES.isEmpty()) {
         YuvVideoRenderTypes.warmupYuvShaders();
         observeCameraContinuity(minecraft);

         for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
            instance.pumpUploadOnRenderThread();
         }
      }
   }

   @SubscribeEvent
   public static void onSubmitGeometryAtStage(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            PortSubmitNodeCollector collector = PortWorldRenderEvents.begin();

            try {
               if (!SESSION_INSTANCES.isEmpty()) {
                  Camera camera = minecraft.gameRenderer.getMainCamera();
                  SESSION_INSTANCES.removeIf(instance -> {
                     if (instance.hasTerminalFailure()) {
                        return false;
                     } else if (!instance.hasVideoConsumer()) {
                        return true;
                     } else {
                        instance.submit(collector, minecraft, camera);
                        return !instance.isRunning() && !instance.hasFrame() && !instance.hasNetworkFailure();
                     }
                  });
               }

               boolean renderYuvFrame = shouldRenderYuvFrame();
               DynamicTexture rgbaTexture = LEGACY_TEXTURES.rgba();
               VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
               if (!hasFrame || !renderYuvFrame && rgbaTexture == null || width <= 0 || height <= 0) {
                  return;
               }

               if (renderYuvFrame && shouldDrawYuvImmediateWithIris()) {
                  return;
               }

               Camera camera = minecraft.gameRenderer.getMainCamera();
               if (!LEGACY_PREVIEW.requiresProjector()) {
                  VideoProjectorBlockEntity projector = activeVideoProjector(minecraft);
                  if (LEGACY_PREVIEW.requiresProjector() && projector == null) {
                     stop();
                     return;
                  }

                  float scale = projector != null ? Math.abs(projector.getProjectionScale()) : 1.0F;
                  float aspect = (float)width / height;
                  float halfHeight = 1.35F * scale * 0.5F;
                  float halfWidth = halfHeight * aspect;
                  float p0x;
                  float p0y;
                  float p0z;
                  float p1x;
                  float p1y;
                  float p1z;
                  float p2x;
                  float p2y;
                  float p2z;
                  float p3x;
                  float p3y;
                  float p3z;
                  if (!WORLD_ANCHORED && !LEGACY_PREVIEW.requiresProjector()) {
                     Vector3fc forward = camera.getLookVector();
                     Vector3fc left = camera.getLeftVector();
                     Vector3fc up = camera.getUpVector();
                     float cx = (float)(forward.x() * 3.0);
                     float cy = (float)(forward.y() * 3.0);
                     float cz = (float)(forward.z() * 3.0);
                     float lx = left.x() * halfWidth;
                     float ly = left.y() * halfWidth;
                     float lz = left.z() * halfWidth;
                     float ux = up.x() * halfHeight;
                     float uy = up.y() * halfHeight;
                     float uz = up.z() * halfHeight;
                     p0x = cx + lx + ux;
                     p0y = cy + ly + uy;
                     p0z = cz + lz + uz;
                     p1x = cx + lx - ux;
                     p1y = cy + ly - uy;
                     p1z = cz + lz - uz;
                     p2x = cx - lx - ux;
                     p2y = cy - ly - uy;
                     p2z = cz - lz - uz;
                     p3x = cx - lx + ux;
                     p3y = cy - ly + uy;
                     p3z = cz - lz + uz;
                  } else {
                     if (!ensureWorldAnchor(minecraft, camera, projector)) {
                        return;
                     }

                     Vec3 cameraPos = camera.getPosition();
                     double dx = anchorX - cameraPos.x;
                     double dy = anchorY - cameraPos.y;
                     double dz = anchorZ - cameraPos.z;
                     if (projector != null
                        ? !isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)
                        : dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
                        return;
                     }

                     double yawRad = Math.toRadians(anchorYawDeg);
                     double pitchRad = Math.toRadians(projector != null ? projector.getProjectionPitch() : 0.0);
                     float rightX = (float)Math.cos(yawRad);
                     float rightZ = (float)Math.sin(yawRad);
                     float forwardX = (float)(-Math.sin(yawRad));
                     float forwardZ = (float)Math.cos(yawRad);
                     float upX = (float)(forwardX * Math.sin(pitchRad));
                     float upY = (float)Math.cos(pitchRad);
                     float upZ = (float)(forwardZ * Math.sin(pitchRad));
                     float cx = (float)(anchorX - cameraPos.x);
                     float cy = (float)(anchorY - cameraPos.y);
                     float cz = (float)(anchorZ - cameraPos.z);
                     float rx = rightX * halfWidth;
                     float rz = rightZ * halfWidth;
                     float ux = upX * halfHeight;
                     float uy = upY * halfHeight;
                     float uz = upZ * halfHeight;
                     p0x = cx - rx + ux;
                     p0y = cy + uy;
                     p0z = cz - rz + uz;
                     p1x = cx - rx - ux;
                     p1y = cy - uy;
                     p1z = cz - rz - uz;
                     p2x = cx + rx - ux;
                     p2y = cy - uy;
                     p2z = cz + rz - uz;
                     p3x = cx + rx + ux;
                     p3y = cy + uy;
                     p3z = cz + rz + uz;
                  }

                  PoseStack poseStack = new PoseStack();
                  logFirstPreviewSubmit(renderYuvFrame, width, height, camera, anchorX, anchorY, anchorZ, renderYuvFrame ? "preview-yuv" : "preview-rgba");
                  collector.submitCustomGeometry(
                     poseStack,
                     renderYuvFrame ? yuvRenderTypeForCurrentIrisProgram(yuvTextures) : YuvVideoRenderTypes.videoRgbaEntity(TEXTURE_ID),
                     (pose, buffer) -> {
                        emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, false);
                        emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, true);
                     }
                  );
                  return;
               }

               List<VideoProjectorBlockEntity> projectors = activeVideoProjectors(minecraft);
               if (projectors.isEmpty()) {
                  stop();
                  return;
               }

               for (VideoProjectorBlockEntity projectorx : projectors) {
                  if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                     submitProjectorPrivacyOverlay(collector, minecraft, camera, projectorx);
                  } else if (renderYuvFrame) {
                     submitProjectorYuvGeometry(collector, minecraft, camera, projectorx, yuvTextures);
                  } else {
                     submitProjectorGeometry(collector, minecraft, camera, projectorx);
                  }
               }
            } finally {
               PortWorldRenderEvents.end(collector);
            }
         } else {
            cancelAllPendingProjectionStarts();
            SESSION_INSTANCES.clear();
            PENDING_SESSIONS.clear(PendingVideoSessionRegistry.State.LOADING);
            LEGACY_WORKER.requestStop();
            hasFrame = false;
         }
      }
   }

   @SubscribeEvent
   public static void onRenderLevelYuvImmediate(RenderLevelStageEvent event) {
      String stageName;
      String route;
      if (event.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
         stageName = "after_translucent_blocks";
         route = "-after-translucent-blocks";
      } else {
         if (event.getStage() != Stage.AFTER_LEVEL) {
            return;
         }

         stageName = "after_level";
         route = "-after-level";
      }

      if (stageName.equals(YUV_IMMEDIATE_STAGE)) {
         renderInstanceProjectorYuvImmediate(event, "instance-projector-yuv-immediate" + route);
         renderProjectorYuvImmediate(event, "projector-yuv-immediate" + route);
         renderPreviewYuvImmediate(event, "preview-yuv-immediate" + route);
      }
   }

   private static void renderInstanceProjectorYuvImmediate(RenderLevelStageEvent event, String route) {
      if (shouldDrawYuvImmediateWithIris() && !SESSION_INSTANCES.isEmpty()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            for (VideoPlaybackInstance instance : SESSION_INSTANCES.instances()) {
               instance.renderYuvImmediate(event, route);
            }
         }
      }
   }

   private static void renderPreviewYuvImmediate(RenderLevelStageEvent event, String route) {
      if (shouldDrawYuvImmediateWithIris()) {
         if (loggedYuvImmediateStage.compareAndSet(false, true)) {
            LOGGER.debug(
               "Iris/YUV: 启用非投影预览 immediate 绘制，shaderpack=true，阶段='{}'，坐标模式='{}'，pose='{}'，route={}",
               new Object[]{YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, YUV_IMMEDIATE_POSE, route}
            );
         }

         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null && !LEGACY_PREVIEW.requiresProjector()) {
            VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
            if (hasFrame && shouldRenderYuvFrame() && yuvTextures != null && width > 0 && height > 0) {
               Camera camera = minecraft.gameRenderer.getMainCamera();
               boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
                  || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
                  || "relative".equals(YUV_IMMEDIATE_COORDS);
               VideoBillboardQuadSupport.PreviewQuad quad = computePreviewQuad(minecraft, camera, null, width, height, cameraRelative, true);
               if (quad != null) {
                  RenderType renderType = yuvRenderTypeForCurrentIrisProgram(yuvTextures);
                  BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
                  PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
                  Pose pose = poseStack.last();
                  emitQuad(
                     builder,
                     pose,
                     quad.p0x(),
                     quad.p0y(),
                     quad.p0z(),
                     quad.p1x(),
                     quad.p1y(),
                     quad.p1z(),
                     quad.p2x(),
                     quad.p2y(),
                     quad.p2z(),
                     quad.p3x(),
                     quad.p3y(),
                     quad.p3z(),
                     false
                  );
                  emitQuad(
                     builder,
                     pose,
                     quad.p0x(),
                     quad.p0y(),
                     quad.p0z(),
                     quad.p1x(),
                     quad.p1y(),
                     quad.p1z(),
                     quad.p2x(),
                     quad.p2y(),
                     quad.p2z(),
                     quad.p3x(),
                     quad.p3y(),
                     quad.p3z(),
                     true
                  );
                  MeshData mesh = builder.build();
                  if (mesh != null) {
                     logFirstPreviewSubmit(true, width, height, camera, anchorX, anchorY, anchorZ, route);
                     if (YUV_DEBUG_LOG) {
                        logFirstImmediateQuad(quad, camera, cameraRelative, true);
                     }

                     drawWithEventModelView(renderType, mesh, event);
                  }
               }
            }
         }
      }
   }

   static boolean shouldDrawYuvImmediateWithIris() {
      return IrisShaderpackCompat.shouldDrawYuvImmediate();
   }

   private static void renderProjectorYuvImmediate(RenderLevelStageEvent event, String route) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null && LEGACY_PREVIEW.requiresProjector()) {
         VideoYuvTextureSet yuvTextures = LEGACY_TEXTURES.yuv();
         if (hasFrame && shouldRenderYuvFrame() && yuvTextures != null && width > 0 && height > 0) {
            if (shouldDrawYuvImmediateWithIris()) {
               Camera camera = minecraft.gameRenderer.getMainCamera();
               List<VideoProjectorBlockEntity> projectors = activeVideoProjectors(minecraft);
               if (projectors.isEmpty()) {
                  stop();
               } else {
                  boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
                     || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
                     || "relative".equals(YUV_IMMEDIATE_COORDS);
                  if (!loggedProjectorYuvImmediate) {
                     loggedProjectorYuvImmediate = true;
                     LOGGER.debug(
                        "Iris/YUV: shaderpack 下投影仪 YUV 改用 immediate 绘制阶段 '{}'，坐标模式 '{}'，route={}",
                        new Object[]{YUV_IMMEDIATE_STAGE, YUV_IMMEDIATE_COORDS, route}
                     );
                  }

                  for (VideoProjectorBlockEntity projector : projectors) {
                     if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                        drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
                     } else {
                        drawProjectorYuvImmediate(event, minecraft, camera, projector, yuvTextures, route, cameraRelative);
                     }
                  }
               }
            }
         }
      }
   }

   static boolean drawProjectorYuvImmediate(
      RenderLevelStageEvent event, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector, VideoYuvTextureSet textures, String route
   ) {
      boolean cameraRelative = "camera_relative".equals(YUV_IMMEDIATE_COORDS)
         || "camera-relative".equals(YUV_IMMEDIATE_COORDS)
         || "relative".equals(YUV_IMMEDIATE_COORDS);
      return drawProjectorYuvImmediate(event, minecraft, camera, projector, textures, route, cameraRelative);
   }

   public static void captureProjectorImmediatePose(String sessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight) {
      captureProjectorImmediatePose(sessionId, projectorPos, pose, halfHeight, 1.0F, 1.0F);
   }

   public static void captureProjectorImmediatePose(PlaybackSessionId playbackSessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight) {
      captureProjectorImmediatePose(playbackSessionId, projectorPos, pose, halfHeight, 1.0F, 1.0F);
   }

   public static void captureProjectorImmediatePose(String sessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight, float opacity) {
      captureProjectorImmediatePose(sessionId, projectorPos, pose, halfHeight, opacity, 1.0F);
   }

   public static void captureProjectorImmediatePose(String sessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight, float opacity, float brightness) {
      PlaybackSessionId.parse(sessionId)
         .ifPresent(playbackSessionId -> captureProjectorImmediatePose(playbackSessionId, projectorPos, pose, halfHeight, opacity, brightness));
   }

   public static void captureProjectorImmediatePose(PlaybackSessionId playbackSessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight, float opacity) {
      captureProjectorImmediatePose(playbackSessionId, projectorPos, pose, halfHeight, opacity, 1.0F);
   }

   public static void captureProjectorImmediatePose(
      PlaybackSessionId playbackSessionId, BlockPos projectorPos, Matrix4f pose, float halfHeight, float opacity, float brightness
   ) {
      VideoOpacityRoute opacityRoute = VideoOpacityRoute.choose(opacity);
      if (playbackSessionId != null && projectorPos != null && pose != null && !(halfHeight <= 0.0F) && opacityRoute != VideoOpacityRoute.SKIP) {
         PROJECTOR_IMMEDIATE_POSES.put(
            new VideoBillboardState.ProjectorImmediateKey(playbackSessionId, projectorPos.immutable()),
            new VideoBillboardState.ProjectorImmediatePose(
               new Matrix4f(pose), halfHeight, VideoOpacityRoute.normalize(opacity), VideoSurfaceBrightness.normalize(brightness)
            )
         );
      }
   }

   static boolean drawCapturedProjectorYuvImmediate(
      RenderLevelStageEvent event, String sessionId, BlockPos projectorPos, VideoYuvTextureSet textures, String route
   ) {
      PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (event != null && playbackSessionId != null && projectorPos != null && textures != null && textures.width() > 0 && textures.height() > 0) {
         VideoBillboardState.ProjectorImmediatePose captured = PROJECTOR_IMMEDIATE_POSES.remove(
            new VideoBillboardState.ProjectorImmediateKey(playbackSessionId, projectorPos)
         );
         if (captured == null) {
            return false;
         } else {
            float halfWidth = captured.halfHeight() * textures.width() / textures.height();
            VideoBillboardQuadSupport.PreviewQuad quad = transformedLocalQuad(captured.pose(), halfWidth, captured.halfHeight());
            RenderType renderType = yuvRenderTypeForCurrentIrisProgram(textures);
            BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
            Pose identityPose = new PoseStack().last();
            emitQuad(
               builder,
               identityPose,
               quad.p0x(),
               quad.p0y(),
               quad.p0z(),
               quad.p1x(),
               quad.p1y(),
               quad.p1z(),
               quad.p2x(),
               quad.p2y(),
               quad.p2z(),
               quad.p3x(),
               quad.p3y(),
               quad.p3z(),
               false,
               captured.opacity(),
               captured.brightness()
            );
            emitQuad(
               builder,
               identityPose,
               quad.p0x(),
               quad.p0y(),
               quad.p0z(),
               quad.p1x(),
               quad.p1y(),
               quad.p1z(),
               quad.p2x(),
               quad.p2y(),
               quad.p2z(),
               quad.p3x(),
               quad.p3y(),
               quad.p3z(),
               true,
               captured.opacity(),
               captured.brightness()
            );
            MeshData mesh = builder.build();
            if (mesh == null) {
               return false;
            } else {
               if (YUV_DEBUG_LOG) {
                  LOGGER.debug("Iris/YUV: 使用 BER 当帧矩阵绘制投影视频，session={}, projector={}, route={}", new Object[]{sessionId, projectorPos, route});
               }

               drawWithEventModelView(renderType, mesh, event);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   protected static boolean drawProjectorYuvImmediate(
      RenderLevelStageEvent event,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      VideoYuvTextureSet textures,
      String route,
      boolean cameraRelative
   ) {
      if (projector != null && textures != null && textures.width() > 0 && textures.height() > 0) {
         VideoBillboardQuadSupport.PreviewQuad quad = computePreviewQuad(
            minecraft, camera, projector, textures.width(), textures.height(), cameraRelative, true
         );
         if (quad == null) {
            return false;
         } else {
            RenderType renderType = yuvRenderTypeForCurrentIrisProgram(textures);
            BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
            PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
            Pose pose = poseStack.last();
            emitQuad(
               builder,
               pose,
               quad.p0x(),
               quad.p0y(),
               quad.p0z(),
               quad.p1x(),
               quad.p1y(),
               quad.p1z(),
               quad.p2x(),
               quad.p2y(),
               quad.p2z(),
               quad.p3x(),
               quad.p3y(),
               quad.p3z(),
               false,
               1.0F,
               projector.getProjectionBrightness()
            );
            emitQuad(
               builder,
               pose,
               quad.p0x(),
               quad.p0y(),
               quad.p0z(),
               quad.p1x(),
               quad.p1y(),
               quad.p1z(),
               quad.p2x(),
               quad.p2y(),
               quad.p2z(),
               quad.p3x(),
               quad.p3y(),
               quad.p3z(),
               true,
               1.0F,
               projector.getProjectionBrightness()
            );
            MeshData mesh = builder.build();
            if (mesh == null) {
               return false;
            } else {
               logFirstPreviewSubmit(
                  true,
                  textures.width(),
                  textures.height(),
                  camera,
                  projector.getBlockPos().getX() + 0.5 + projector.getProjectionDistanceX(),
                  projector.getBlockPos().getY() + projector.getProjectionHeight(),
                  projector.getBlockPos().getZ() + 0.5 + projector.getProjectionDistanceZ(),
                  route
               );
               drawWithEventModelView(renderType, mesh, event);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   public record ProjectorVideoDebugSnapshot(
      BlockPos projectorPos, boolean berManaged, boolean submittedByFrustum, boolean geometryVisible, boolean predictedVisible
   ) {
   }

   public record VideoDebugSnapshot(
      String sessionId,
      boolean running,
      boolean failed,
      boolean decodeAdmission,
      boolean prewarm,
      boolean offscreenPaused,
      boolean syncActive,
      boolean hasFrame,
      long generation,
      String restartState,
      long mediaMillis,
      long queuedMediaMillis,
      long expectedMediaMillis,
      long visualMillis,
      long pacingMillis,
      int width,
      int height,
      int fps,
      String backend,
      List<VideoBillboardPreview.ProjectorVideoDebugSnapshot> projectors
   ) {
      public VideoDebugSnapshot(
         String sessionId,
         boolean running,
         boolean failed,
         boolean decodeAdmission,
         boolean prewarm,
         boolean offscreenPaused,
         boolean syncActive,
         boolean hasFrame,
         long generation,
         String restartState,
         long mediaMillis,
         long queuedMediaMillis,
         long expectedMediaMillis,
         long visualMillis,
         long pacingMillis,
         int width,
         int height,
         int fps,
         String backend,
         List<VideoBillboardPreview.ProjectorVideoDebugSnapshot> projectors
      ) {
         backend = backend != null && !backend.isBlank() ? backend : "unknown";
         projectors = List.copyOf(projectors);
         this.sessionId = sessionId;
         this.running = running;
         this.failed = failed;
         this.decodeAdmission = decodeAdmission;
         this.prewarm = prewarm;
         this.offscreenPaused = offscreenPaused;
         this.syncActive = syncActive;
         this.hasFrame = hasFrame;
         this.generation = generation;
         this.restartState = restartState;
         this.mediaMillis = mediaMillis;
         this.queuedMediaMillis = queuedMediaMillis;
         this.expectedMediaMillis = expectedMediaMillis;
         this.visualMillis = visualMillis;
         this.pacingMillis = pacingMillis;
         this.width = width;
         this.height = height;
         this.fps = fps;
         this.backend = backend;
         this.projectors = projectors;
      }
   }
}
