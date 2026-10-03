package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

final class VideoPlaybackPresentation {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final VideoPipelineProperties.Offscreen OFFSCREEN = VideoPipelineProperties.offscreen();
   private static final long RESUME_RESTART_LAG_NANOS = OFFSCREEN.resumeRestartLagMillis() * 1000000L;
   private static final long STABILIZATION_MILLIS = VideoPipelineProperties.timing().decoderStabilizationMillis();
   private final VideoPlaybackInstance owner;

   VideoPlaybackPresentation(VideoPlaybackInstance owner) {
      this.owner = owner;
   }

   VideoBillboardState.ProjectorFrameSnapshot frameSnapshot(BlockPos projectorPos) {
      if (!this.owns(projectorPos)) {
         return VideoBillboardState.ProjectorFrameSnapshot.empty();
      } else if (this.owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
         return this.placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
      } else {
         return this.owner.hasFrame ? this.current() : VideoBillboardState.ProjectorFrameSnapshot.empty();
      }
   }

   VideoBillboardState.ProjectorFrameSnapshot displayFrameSnapshot(BlockPos projectorPos) {
      if (!this.owns(projectorPos)) {
         return VideoBillboardState.ProjectorFrameSnapshot.empty();
      } else {
         boolean placeholders = VideoPipelineProperties.loadingPlaceholderEnabled();
         if (this.owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
            return this.placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
         } else {
            boolean irisWarning = this.shouldShowIrisWarning();
            if (irisWarning && placeholders) {
               return this.placeholder(VideoPlaceholderFrames.Kind.IRIS_WARNING);
            } else if (this.owner.hasFrame) {
               return irisWarning ? VideoBillboardState.ProjectorFrameSnapshot.empty() : this.current();
            } else {
               return placeholders ? this.placeholder(VideoPlaceholderFrames.Kind.LOADING) : VideoBillboardState.ProjectorFrameSnapshot.empty();
            }
         }
      }
   }

   VideoBillboardState.ProjectorFrameSnapshot realFrameSnapshot(BlockPos projectorPos) {
      return this.owns(projectorPos) && this.owner.hasFrame ? this.current() : VideoBillboardState.ProjectorFrameSnapshot.empty();
   }

   VideoBillboardState.ProjectorFrameSnapshot failurePlaceholderSnapshot() {
      return this.placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
   }

   VideoBillboardState.ProjectorFrameSnapshot turntableFrameSnapshot(BlockPos turntablePos) {
      if (turntablePos == null || !this.owner.anchor.isForTurntable(turntablePos)) {
         return VideoBillboardState.ProjectorFrameSnapshot.empty();
      } else if (this.owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
         return this.placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
      } else {
         return this.owner.hasFrame ? this.current() : VideoBillboardState.ProjectorFrameSnapshot.empty();
      }
   }

   VideoBillboardState.ProjectorFrameSnapshot previewFrameSnapshot() {
      if (this.owner.terminalFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
         return this.placeholder(VideoPlaceholderFrames.Kind.NETWORK_ERROR);
      } else {
         return this.owner.hasFrame ? this.current() : VideoBillboardState.ProjectorFrameSnapshot.empty();
      }
   }

   private boolean owns(BlockPos projectorPos) {
      return projectorPos == null || this.owner.consumers.containsProjector(projectorPos);
   }

   private VideoBillboardState.ProjectorFrameSnapshot placeholder(VideoPlaceholderFrames.Kind kind) {
      return VideoPlaceholderFrames.snapshot(kind, this.owner.startNanoTime);
   }

   private VideoBillboardState.ProjectorFrameSnapshot current() {
      return this.owner.textures.snapshot(this.owner.targetWidth, this.owner.targetHeight);
   }

   void submit(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera) {
      boolean renderable = false;
      boolean prewarm = false;
      List<BlockPos> projectorPositions = this.owner.consumers.projectors();
      List<VideoProjectorBlockEntity> renderableProjectors = new ArrayList<>();

      for (BlockPos pos : projectorPositions) {
         boolean berManaged = VideoBillboardPreview.isProjectorRenderedByBer(pos);
         boolean submittedByBer = VideoBillboardPreview.wasProjectorRecentlySubmittedByBer(this.owner.sessionId(), pos);
         if (VideoBerConsumerVisibilityPolicy.usesBerSubmission(berManaged, submittedByBer)) {
            renderable |= submittedByBer;
            boolean predicted = ControlConsoleRenderer.isPredictivePrewarmActive(pos)
               || minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector && isPredictedProjectorVisibleSoon(minecraft, projector);
            prewarm |= submittedByBer || predicted;
         } else if (!(minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector)) {
            prewarm |= ControlConsoleRenderer.isPredictivePrewarmActive(pos);
         } else {
            boolean projectorRenderable = VideoBillboardPreview.isProjectorScreenRenderable(
               minecraft, camera, projector, VideoBillboardPreview.viewDotThreshold()
            );
            boolean projectorPrewarm = projectorRenderable || isPredictedProjectorVisibleSoon(minecraft, projector);
            renderable |= projectorRenderable;
            prewarm |= projectorPrewarm;
            if (projectorRenderable) {
               renderableProjectors.add(projector);
            }
         }
      }

      boolean holographicVisible = this.owner.hasHolographicTurntableConsumer();
      this.markVisibility(renderable || holographicVisible, prewarm || holographicVisible);
      this.owner.pumpUploadOnRenderThread();

      for (VideoProjectorBlockEntity projector : renderableProjectors) {
         this.submitProjector(collector, minecraft, camera, projector);
      }
   }

   private void submitProjector(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      if (HolographicGlassesClient.shouldHideProjectorVideos()) {
         VideoBillboardPreview.submitProjectorPrivacyOverlay(collector, minecraft, camera, projector);
      } else if (this.owner.networkFailure && VideoPlaceholderFrames.NETWORK_ERROR_ENABLED) {
         VideoBillboardPreview.submitProjectorEmissiveGeometry(
            collector, minecraft, camera, projector, this.placeholderTexture(VideoPlaceholderFrames.Kind.NETWORK_ERROR), 320, 180
         );
      } else if (this.owner.hasFrame && this.owner.textures.hasRgbaTexture()) {
         VideoBillboardPreview.submitProjectorGeometry(
            collector, minecraft, camera, projector, this.owner.textures.rgbaTextureId(), this.owner.targetWidth, this.owner.targetHeight
         );
      } else if (this.owner.hasFrame
         && this.owner.textures.hasYuvTexture()
         && VideoBillboardPreview.isCustomYuvShaderAvailable()
         && !VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
         VideoBillboardPreview.submitProjectorYuvGeometry(collector, minecraft, camera, projector, this.owner.textures.yuvTextureSet());
      } else if (VideoPipelineProperties.loadingPlaceholderEnabled()) {
         this.submitLoadingPlaceholder(collector, minecraft, camera, projector);
      }
   }

   private void submitLoadingPlaceholder(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      VideoPlaceholderFrames.Kind kind = this.shouldShowIrisWarning() ? VideoPlaceholderFrames.Kind.IRIS_WARNING : VideoPlaceholderFrames.Kind.LOADING;
      if (kind == VideoPlaceholderFrames.Kind.IRIS_WARNING && VideoPlaceholderFrames.IRIS_VIEW_DEPTH_OFFSET > 0.0) {
         VideoBillboardPreview.submitProjectorViewDepthOffsetGeometry(
            collector, minecraft, camera, projector, this.placeholderTexture(kind), 320, 180, VideoPlaceholderFrames.IRIS_VIEW_DEPTH_OFFSET
         );
      } else {
         VideoBillboardPreview.submitProjectorEmissiveGeometry(collector, minecraft, camera, projector, this.placeholderTexture(kind), 320, 180);
      }
   }

   private void markVisibility(boolean renderable, boolean prewarm) {
      long nowNs = System.nanoTime();
      this.owner.prewarmVisible = prewarm;
      if (renderable || prewarm) {
         this.owner.grantDecodeAdmission();
         long offscreenSince = this.owner.offscreenSinceNanoTime;
         this.owner.lastVisibleNanoTime = nowNs;
         this.owner.offscreenSinceNanoTime = 0L;
         if (offscreenSince > 0L) {
            this.owner.resetFirstFrameWatchdogAfterVisibilityResume(nowNs);
            this.maybeRestartForVisibleResume(nowNs - offscreenSince);
         }

         this.owner.loggedOffscreenPause = false;
      } else if (this.owner.offscreenSinceNanoTime == 0L) {
         this.owner.offscreenSinceNanoTime = nowNs;
      }
   }

   private void maybeRestartForVisibleResume(long offscreenDurationNs) {
      if (this.owner.running && this.restartAllowed() && RESUME_RESTART_LAG_NANOS > 0L) {
         long masterMillis = this.owner.anchor.timeline().mediaMillis();
         if (masterMillis >= 0L) {
            long bestVideoMillis = Math.max(this.owner.queuedMediaMillis(), this.owner.mediaMillis());
            long lagNs = bestVideoMillis >= 0L ? (masterMillis - bestVideoMillis) * 1000000L : offscreenDurationNs;
            if (lagNs >= RESUME_RESTART_LAG_NANOS) {
               long restartOffsetMillis = this.owner.totalMillis > 0L ? Math.min(this.owner.totalMillis, masterMillis) : masterMillis;
               LOGGER.debug(
                  "视频会话离屏恢复重定位: session={}, offscreen={}ms, master={}ms, video={}ms, offset={}ms",
                  new Object[]{this.owner.sessionId(), offscreenDurationNs / 1000000L, masterMillis, bestVideoMillis, restartOffsetMillis}
               );
               this.owner.restartDecoder(this.owner.targetWidth, this.owner.targetHeight, restartOffsetMillis, true);
            }
         }
      }
   }

   boolean restartAllowed() {
      long generationStart = this.owner.decoderGenerationStartedNanoTime;
      long sinceStartMillis = generationStart > 0L ? Math.max(0L, (System.nanoTime() - generationStart) / 1000000L) : 0L;
      return VideoRestartSuppressionPolicy.allowsRestart(this.owner.liveSource, this.owner.restartInProgress, sinceStartMillis, STABILIZATION_MILLIS);
   }

   void renderYuvImmediate(RenderLevelStageEvent event, String route) {
      if ((!this.owner.networkFailure || !VideoPlaceholderFrames.NETWORK_ERROR_ENABLED)
         && this.owner.hasFrame
         && this.owner.textures.hasYuvTexture()
         && VideoBillboardPreview.isCustomYuvShaderAvailable()
         && VideoBillboardPreview.shouldDrawYuvImmediateWithIris()) {
         this.owner.pumpUploadOnRenderThread();
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.level != null && minecraft.player != null) {
            Camera camera = minecraft.gameRenderer.getMainCamera();
            boolean drew = false;
            boolean prewarm = false;

            for (BlockPos pos : this.owner.consumers.projectors()) {
               if (VideoBillboardPreview.drawCapturedProjectorYuvImmediate(event, this.owner.sessionId(), pos, this.owner.textures.yuvTextureSet(), route)) {
                  drew = true;
                  prewarm = true;
               } else if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
                  prewarm |= isPredictedProjectorVisibleSoon(minecraft, projector);
                  if (HolographicGlassesClient.shouldHideProjectorVideos()) {
                     VideoBillboardPreview.drawProjectorPrivacyOverlayImmediate(event, minecraft, camera, projector, route);
                     drew = true;
                  } else {
                     drew |= VideoBillboardPreview.drawProjectorYuvImmediate(event, minecraft, camera, projector, this.owner.textures.yuvTextureSet(), route);
                  }
               } else {
                  prewarm |= ControlConsoleRenderer.isPredictivePrewarmActive(pos);
               }
            }

            this.markVisibility(drew, prewarm);
            if (drew && !this.owner.firstYuvImmediateLogged) {
               this.owner.firstYuvImmediateLogged = true;
               LOGGER.debug(
                  "Iris/YUV: session={} 的投影仪 YUV 使用实例纹理 immediate 绘制，route={}, texture={}x{}",
                  new Object[]{this.owner.sessionId(), route, this.owner.textures.yuvTextureSet().width(), this.owner.textures.yuvTextureSet().height()}
               );
            }
         }
      }
   }

   private static boolean isPredictedProjectorVisibleSoon(Minecraft minecraft, VideoProjectorBlockEntity projector) {
      return minecraft != null && VideoBillboardPreview.isProjectorScreenPredictedVisible(minecraft, minecraft.gameRenderer.getMainCamera(), projector);
   }

   private ResourceLocation placeholderTexture(VideoPlaceholderFrames.Kind kind) {
      return VideoPlaceholderFrames.texture(kind, this.owner.startNanoTime);
   }

   private boolean shouldShowIrisWarning() {
      return this.owner.hasFrame && this.owner.textures.hasYuvTexture() && IrisShaderpackCompat.shouldApplyIrisYuvCompatibility();
   }

   boolean isWithinAudioRange(Minecraft minecraft) {
      return minecraft.player != null
         && this.owner.hasVideoConsumer()
         && this.owner.anchor.isWithinAudioRange(minecraft, this.owner.consumers.projectors(), VideoBillboardPreview.AUDIO_SYNC_RANGE_SQR);
   }
}
