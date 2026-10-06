package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.media.ClientLocalImageProjection;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoSurfacePrivacyPolicy;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public class VideoProjectorRenderer implements BlockEntityRenderer<VideoProjectorBlockEntity> {
   private static final ProjectorRenderProperties.VideoBounds RENDER_BOUNDS = ProjectorRenderProperties.videoBounds();

   public VideoProjectorRenderer(Context context) {
   }

   public void render(
      VideoProjectorBlockEntity projector, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay
   ) {
      VideoProjectorRenderer.State state = this.extract(projector);
      this.submit(state, poseStack, new PortSubmitNodeCollector(bufferSource));
   }

   private VideoProjectorRenderer.State extract(VideoProjectorBlockEntity projector) {
      VideoProjectorRenderer.State state = new VideoProjectorRenderer.State();
      state.projectorPos = projector.getBlockPos().immutable();
      state.linkedPos = null;
      state.projectionYaw = projector.getProjectionYaw();
      state.projectionPitch = projector.getProjectionPitch();
      state.projectionScale = projector.getProjectionScale();
      state.projectionHeight = projector.getProjectionHeight();
      state.projectionDistanceX = projector.getProjectionDistanceX();
      state.projectionDistanceZ = projector.getProjectionDistanceZ();
      state.projectionBrightness = projector.getProjectionBrightness();
      state.frame = VideoBillboardState.ProjectorFrameSnapshot.empty();
      state.playbackSessionId = Optional.empty();
      state.hideVideoForPrivacy = VideoSurfacePrivacyPolicy.hideVideo(
         HolographicGlassesClient.shouldHideProjectorVideos(), VideoSurfacePrivacyPolicy.SurfaceKind.PUBLIC_PROJECTOR
      );
      state.visible = false;
      BlockPos linkedPos = projector.getLinkedTurntablePos();
      if (linkedPos != null && projector.getLevel() != null) {
         state.linkedPos = linkedPos.immutable();
         Level level = projector.getLevel();
         BlockEntity linkedBlockEntity = level.getBlockEntity(linkedPos);
         if (linkedBlockEntity instanceof LiveStreamerBlockEntity live) {
            ClientLinkRegistry.link(projector.getBlockPos(), linkedPos);
            state.visible = live.isPlaying();
            if (state.visible) {
               VideoBillboardPreview.attachProjectorToTurntable(linkedPos, projector.getBlockPos());
               String liveSessionId = ModernTurntablePlaybackTracker.currentSessionId(linkedPos);
               state.setPlaybackSessionId(PlaybackSessionId.parse(liveSessionId));
               state.frame = VideoBillboardPreview.currentProjectorDisplayFrame(projector.getBlockPos());
            } else {
               VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            }

            syncActivatedState(projector, state.visible);
            return state;
         } else if (!(linkedBlockEntity instanceof ModernTurntableBlockEntity turntable)) {
            ClientLinkRegistry.unlink(projector.getBlockPos());
            VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            projector.unlink();
            syncActivatedState(projector, false);
            return state;
         } else {
            ClientLinkRegistry.link(projector.getBlockPos(), linkedPos);
            // 本地图片：海报式静态画面，与唱片机是否在播放无关。同步只做查表 + TTL 判定，
            // 真正的纹理加载排在客户端线程队列里（避免在方块实体渲染过程中做 GL 上传）。
            ClientLocalImageProjection.syncFromTurntable(turntable, projector.getBlockPos());
            VideoBillboardState.ProjectorFrameSnapshot localImage = ClientLocalImageProjection.frameForConsumer(
               projector.getBlockPos()
            );
            state.visible = localImage != null || turntable.isPlaying();
            if (localImage != null) {
               state.frame = localImage;
            } else if (state.visible) {
               VideoBillboardPreview.attachProjectorToTurntable(linkedPos, projector.getBlockPos());
               PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
               state.setPlaybackSessionId(sync.playbackSessionId());
               if (!sync.hasSession() || !VideoBillboardPreview.hasSessionForTurntable(linkedPos, sync.sessionId())) {
                  ModernTurntableVideoClient.syncFromTurntableForProjectorIfPossible(turntable, projector);
               }

               state.frame = VideoBillboardPreview.currentProjectorDisplayFrame(projector.getBlockPos());
            }

            if (!state.visible) {
               VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
            }

            syncActivatedState(projector, state.visible);
            return state;
         }
      } else {
         ClientLinkRegistry.unlink(projector.getBlockPos());
         VideoBillboardPreview.stopIfProjector(projector.getBlockPos());
         syncActivatedState(projector, false);
         return state;
      }
   }

   private void submit(VideoProjectorRenderer.State state, PoseStack poseStack, PortSubmitNodeCollector collector) {
      try {
         if (!state.visible || state.linkedPos == null || state.projectorPos == null) {
            return;
         }

         if (!state.hideVideoForPrivacy && VideoBillboardPreview.isProjectorScreenPotentiallyVisible(state.projectorPos)) {
            state.playbackSessionId().ifPresent(sessionId -> VideoBillboardPreview.markProjectorSubmittedByBer(sessionId, state.projectorPos));
         }

         VideoBillboardState.ProjectorFrameSnapshot frame = state.frame;
         if (frame != null && frame.hasFrame() && frame.width() > 0 && frame.height() > 0) {
            float scale = Math.abs(state.projectionScale);
            float aspect = (float)frame.width() / frame.height();
            float halfHeight = 1.35F * scale * 0.5F;
            float halfWidth = halfHeight * aspect;
            poseStack.pushPose();
            poseStack.translate(0.5 + state.projectionDistanceX, state.projectionHeight, 0.5 + state.projectionDistanceZ);
            poseStack.mulPose(Axis.YP.rotationDegrees(state.projectionYaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(-state.projectionPitch));
            Matrix4f screenPose = new Matrix4f(poseStack.last().pose());
            if (state.playbackSessionId().isPresent() && !state.hideVideoForPrivacy) {
               VideoBillboardPreview.captureProjectorImmediatePose(
                  state.playbackSessionId().orElseThrow(), state.projectorPos, screenPose, halfHeight, 1.0F, state.projectionBrightness
               );
            }

            if (state.hideVideoForPrivacy) {
               VideoBillboardPreview.submitProjectorPrivacyOverlayOnPose(collector, poseStack, halfWidth, halfHeight);
            } else {
               VideoBillboardPreview.submitProjectorFrameOnPose(
                  collector,
                  poseStack,
                  frame,
                  halfWidth,
                  halfHeight,
                  VideoBillboardPreview.cameraRelativeBackOffset(screenPose, frame.rgbaDepthOffset()),
                  1.0F,
                  state.projectionBrightness
               );
            }

            poseStack.popPose();
            return;
         }
      } finally {
         collector.end();
      }
   }

   public AABB getRenderBoundingBox(VideoProjectorBlockEntity blockEntity) {
      VideoBillboardState.ProjectorFrameSnapshot localImage = ClientLocalImageProjection.frameForConsumer(
         blockEntity.getBlockPos()
      );
      VideoBillboardState.ProjectorFrameSnapshot frame = localImage != null
         ? localImage
         : VideoBillboardPreview.currentProjectorDisplayFrame(blockEntity.getBlockPos());
      double aspect = frame.width() > 0 && frame.height() > 0 ? (double)frame.width() / frame.height() : 1.7777777777777777;
      aspect = Math.min(RENDER_BOUNDS.maxAspect(), Math.max(0.125, aspect));
      return ProjectorScreenBounds.aroundBlock(
         blockEntity.getBlockPos(),
         blockEntity.getProjectionDistanceX(),
         blockEntity.getProjectionHeight(),
         blockEntity.getProjectionDistanceZ(),
         blockEntity.getProjectionYaw(),
         blockEntity.getProjectionPitch(),
         blockEntity.getProjectionScale(),
         aspect,
         RENDER_BOUNDS.margin()
      );
   }

   public boolean shouldRender(VideoProjectorBlockEntity blockEntity, Vec3 cameraPos) {
      double viewDistance = this.getViewDistance();
      return ProjectorScreenBounds.distanceToSqr(this.getRenderBoundingBox(blockEntity), cameraPos) < viewDistance * viewDistance;
   }

   private static void syncActivatedState(VideoProjectorBlockEntity projector, boolean visible) {
      Level level = projector.getLevel();
      if (level != null && level == Minecraft.getInstance().level) {
         BlockPos pos = projector.getBlockPos();
         BlockState currentState = level.getBlockState(pos);
         if (currentState.hasProperty(VideoProjectorBlock.ACTIVATED)) {
            boolean currentlyActivated = (Boolean)currentState.getValue(VideoProjectorBlock.ACTIVATED);
            if (visible != currentlyActivated) {
               Minecraft.getInstance().execute(() -> {
                  Level lvl = projector.getLevel();
                  if (lvl != null) {
                     BlockState bs = lvl.getBlockState(pos);
                     if (bs.hasProperty(VideoProjectorBlock.ACTIVATED)) {
                        lvl.setBlock(pos, (BlockState)bs.setValue(VideoProjectorBlock.ACTIVATED, visible), 3);
                     }
                  }
               });
            }
         }
      }
   }

   public static class State {
      public boolean visible;
      public BlockPos projectorPos;
      public BlockPos linkedPos;
      public float projectionYaw;
      public float projectionPitch;
      public float projectionScale;
      public float projectionHeight;
      public float projectionDistanceX;
      public float projectionDistanceZ;
      public float projectionBrightness = 1.0F;
      public boolean hideVideoForPrivacy;
      private Optional<PlaybackSessionId> playbackSessionId = Optional.empty();
      public VideoBillboardState.ProjectorFrameSnapshot frame = VideoBillboardState.ProjectorFrameSnapshot.empty();

      public Optional<PlaybackSessionId> playbackSessionId() {
         return this.playbackSessionId;
      }

      void setPlaybackSessionId(Optional<PlaybackSessionId> playbackSessionId) {
         this.playbackSessionId = playbackSessionId;
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }
   }
}
