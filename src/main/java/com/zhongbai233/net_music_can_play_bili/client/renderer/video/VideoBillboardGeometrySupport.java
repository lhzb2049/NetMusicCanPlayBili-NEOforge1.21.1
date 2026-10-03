package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

abstract class VideoBillboardGeometrySupport extends VideoBillboardQuadSupport {
   protected static boolean shouldRenderYuvFrame() {
      return LEGACY_TEXTURES.yuv() != null && isCustomYuvShaderAvailable() && (CUSTOM_YUV_SHADER_BACKEND || LEGACY_TEXTURES.rgba() == null);
   }

   public static boolean isCustomYuvShaderAvailable() {
      return CUSTOM_YUV_SHADER_BACKEND && !IrisShaderpackCompat.shouldDisableCustomYuvShader();
   }

   static Fmp4NativeVideoDecoder.OutputFormat yuvDecodeFormat() {
      if (NV12_DECODE_BACKEND) {
         return Fmp4NativeVideoDecoder.OutputFormat.NV12;
      } else {
         return YUV420_DECODE_BACKEND ? Fmp4NativeVideoDecoder.OutputFormat.YUV420P : Fmp4NativeVideoDecoder.OutputFormat.RGBA;
      }
   }

   protected static boolean isYuvFrameFormat(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
      return format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P || format == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
   }

   protected static void submitProjectorGeometry(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      submitProjectorGeometry(collector, minecraft, camera, projector, TEXTURE_ID, width, height);
   }

   static void submitProjectorGeometry(
      PortSubmitNodeCollector collector,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      ResourceLocation renderTextureId,
      int textureWidth,
      int textureHeight
   ) {
      submitProjectorGeometry(
         collector,
         minecraft,
         camera,
         projector,
         renderTextureId,
         textureWidth,
         textureHeight,
         0.0,
         YuvVideoRenderTypes.videoRgbaEntity(renderTextureId),
         "projector-rgba"
      );
   }

   static void submitProjectorEmissiveGeometry(
      PortSubmitNodeCollector collector,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      ResourceLocation renderTextureId,
      int textureWidth,
      int textureHeight
   ) {
      submitProjectorGeometry(
         collector,
         minecraft,
         camera,
         projector,
         renderTextureId,
         textureWidth,
         textureHeight,
         0.0,
         shaderpackSafeEmissiveRgba(renderTextureId),
         "projector-rgba-placeholder"
      );
   }

   static void submitProjectorPrivacyOverlay(PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      VideoBillboardQuadSupport.PreviewQuad quad = computePreviewQuad(minecraft, camera, projector, 320, 180, true, true);
      if (quad != null) {
         PoseStack poseStack = new PoseStack();
         HolographicPrivacyOverlay.submit(
            collector,
            poseStack,
            quad.p0x(),
            quad.p0y(),
            quad.p0z() + 0.003F,
            quad.p1x(),
            quad.p1y(),
            quad.p1z() + 0.003F,
            quad.p2x(),
            quad.p2y(),
            quad.p2z() + 0.003F,
            quad.p3x(),
            quad.p3y(),
            quad.p3z() + 0.003F
         );
      }
   }

   static boolean drawProjectorPrivacyOverlayImmediate(
      RenderLevelStageEvent event, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector, String route
   ) {
      VideoBillboardQuadSupport.PreviewQuad quad = computePreviewQuad(minecraft, camera, projector, 320, 180, true, true);
      if (quad == null) {
         return false;
      } else {
         RenderType renderType = YuvVideoRenderTypes.videoRgbaEntity(HolographicPrivacyOverlay.textureId());
         BufferBuilder builder = Tesselator.getInstance().begin(renderType.mode(), renderType.format());
         PoseStack poseStack = "identity".equals(YUV_IMMEDIATE_POSE) ? new PoseStack() : event.getPoseStack();
         Pose pose = poseStack.last();
         emitQuad(
            builder,
            pose,
            quad.p0x(),
            quad.p0y(),
            quad.p0z() + 0.003F,
            quad.p1x(),
            quad.p1y(),
            quad.p1z() + 0.003F,
            quad.p2x(),
            quad.p2y(),
            quad.p2z() + 0.003F,
            quad.p3x(),
            quad.p3y(),
            quad.p3z() + 0.003F,
            false
         );
         emitQuad(
            builder,
            pose,
            quad.p0x(),
            quad.p0y(),
            quad.p0z() + 0.003F,
            quad.p1x(),
            quad.p1y(),
            quad.p1z() + 0.003F,
            quad.p2x(),
            quad.p2y(),
            quad.p2z() + 0.003F,
            quad.p3x(),
            quad.p3y(),
            quad.p3z() + 0.003F,
            true
         );
         MeshData mesh = builder.build();
         if (mesh == null) {
            return false;
         } else {
            drawWithEventModelView(renderType, mesh, event);
            return true;
         }
      }
   }

   static void submitProjectorViewDepthOffsetGeometry(
      PortSubmitNodeCollector collector,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      ResourceLocation renderTextureId,
      int textureWidth,
      int textureHeight,
      double viewDepthOffset
   ) {
      submitProjectorGeometry(
         collector,
         minecraft,
         camera,
         projector,
         renderTextureId,
         textureWidth,
         textureHeight,
         Math.max(0.0, viewDepthOffset),
         YuvVideoRenderTypes.videoRgbaEntity(renderTextureId),
         "projector-rgba-depth-offset"
      );
   }

   protected static void submitProjectorGeometry(
      PortSubmitNodeCollector collector,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      ResourceLocation renderTextureId,
      int textureWidth,
      int textureHeight,
      double viewDepthOffset,
      RenderType renderType,
      String route
   ) {
      float scale = Math.abs(projector.getProjectionScale());
      float aspect = (float)textureWidth / textureHeight;
      float halfHeight = 1.35F * scale * 0.5F;
      float halfWidth = halfHeight * aspect;
      if (ensureWorldAnchor(minecraft, camera, projector)) {
         Vec3 cameraPos = camera.getPosition();
         if (isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)) {
            double dx = anchorX - cameraPos.x;
            double dy = anchorY - cameraPos.y;
            double dz = anchorZ - cameraPos.z;
            double yawRad = Math.toRadians(anchorYawDeg);
            double pitchRad = Math.toRadians(projector.getProjectionPitch());
            float rightX = (float)Math.cos(yawRad);
            float rightZ = (float)Math.sin(yawRad);
            float forwardX = (float)(-Math.sin(yawRad));
            float forwardZ = (float)Math.cos(yawRad);
            float upX = (float)(forwardX * Math.sin(pitchRad));
            float upY = (float)Math.cos(pitchRad);
            float upZ = (float)(forwardZ * Math.sin(pitchRad));
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double depthOffsetScale = viewDepthOffset > 0.0 && distance > 1.0E-4 ? viewDepthOffset / distance : 0.0;
            float cx = (float)(anchorX - cameraPos.x + dx * depthOffsetScale);
            float cy = (float)(anchorY - cameraPos.y + dy * depthOffsetScale);
            float cz = (float)(anchorZ - cameraPos.z + dz * depthOffsetScale);
            float rx = rightX * halfWidth;
            float rz = rightZ * halfWidth;
            float ux = upX * halfHeight;
            float uy = upY * halfHeight;
            float uz = upZ * halfHeight;
            float p0x = cx - rx + ux;
            float p0y = cy + uy;
            float p0z = cz - rz + uz;
            float p1x = cx - rx - ux;
            float p1y = cy - uy;
            float p1z = cz - rz - uz;
            float p2x = cx + rx - ux;
            float p2y = cy - uy;
            float p2z = cz + rz - uz;
            float p3x = cx + rx + ux;
            float p3y = cy + uy;
            float p3z = cz + rz + uz;
            PoseStack poseStack = new PoseStack();
            logFirstPreviewSubmit(false, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ, route);
            collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
               emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, false, 1.0F, projector.getProjectionBrightness());
               emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, true, 1.0F, projector.getProjectionBrightness());
            });
         }
      }
   }

   static void submitProjectorYuvGeometry(
      PortSubmitNodeCollector collector, Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector, VideoYuvTextureSet textures
   ) {
      submitProjectorGeometry(collector, minecraft, camera, projector, yuvRenderTypeForCurrentIrisProgram(textures), textures.width(), textures.height());
   }

   public static boolean submitProjectorFrameOnPose(
      PortSubmitNodeCollector collector, PoseStack poseStack, VideoBillboardState.ProjectorFrameSnapshot frame, float halfWidth, float halfHeight
   ) {
      return submitProjectorFrameOnPose(collector, poseStack, frame, halfWidth, halfHeight, frame != null ? frame.rgbaDepthOffset() : 0.0F);
   }

   public static boolean submitProjectorFrameOnPose(
      PortSubmitNodeCollector collector,
      PoseStack poseStack,
      VideoBillboardState.ProjectorFrameSnapshot frame,
      float halfWidth,
      float halfHeight,
      float rgbaDepthOffset
   ) {
      if (collector == null
         || poseStack == null
         || frame == null
         || !frame.hasFrame()
         || frame.width() <= 0
         || frame.height() <= 0
         || halfWidth <= 0.0F
         || halfHeight <= 0.0F) {
         return false;
      } else if (frame.yuv()) {
         if (isCustomYuvShaderAvailable() && frame.yTexture() != null && frame.uTexture() != null && frame.vTexture() != null) {
            submitLocalTexturedQuadSingle(collector, poseStack, yuvRenderTypeForSnapshot(frame), halfWidth, halfHeight, 0.0F, 1.0F);
            return true;
         } else {
            return false;
         }
      } else if (frame.rgbaTexture() == null) {
         return false;
      } else {
         submitLocalTexturedQuad(
            collector,
            poseStack,
            frame.emissiveRgba() ? shaderpackSafeEmissiveRgba(frame.rgbaTexture()) : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture()),
            -halfWidth,
            halfHeight,
            halfWidth,
            -halfHeight,
            rgbaDepthOffset,
            1.0F
         );
         if (frame.loadingProgressOverlay()) {
            submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight);
         }

         return true;
      }
   }

   public static boolean submitProjectorFrameOnPose(
      PortSubmitNodeCollector collector,
      PoseStack poseStack,
      VideoBillboardState.ProjectorFrameSnapshot frame,
      float halfWidth,
      float halfHeight,
      float rgbaDepthOffset,
      float opacity
   ) {
      return submitProjectorFrameOnPose(collector, poseStack, frame, halfWidth, halfHeight, rgbaDepthOffset, opacity, 1.0F);
   }

   public static boolean submitProjectorFrameOnPose(
      PortSubmitNodeCollector collector,
      PoseStack poseStack,
      VideoBillboardState.ProjectorFrameSnapshot frame,
      float halfWidth,
      float halfHeight,
      float rgbaDepthOffset,
      float opacity,
      float brightness
   ) {
      VideoOpacityRoute opacityRoute = VideoOpacityRoute.choose(opacity);
      if (opacityRoute == VideoOpacityRoute.SKIP) {
         return false;
      } else {
         float normalizedOpacity = VideoOpacityRoute.normalize(opacity);
         if (frame == null || !frame.hasFrame() || frame.width() <= 0 || frame.height() <= 0 || halfWidth <= 0.0F || halfHeight <= 0.0F) {
            return false;
         } else if (frame.yuv()) {
            if (isCustomYuvShaderAvailable() && frame.yTexture() != null && frame.uTexture() != null && frame.vTexture() != null) {
               submitLocalTexturedQuadSingle(collector, poseStack, yuvRenderTypeForSnapshot(frame), halfWidth, halfHeight, 0.0F, normalizedOpacity, brightness);
               return true;
            } else {
               return false;
            }
         } else if (frame.rgbaTexture() == null) {
            return false;
         } else {
            submitLocalTexturedQuad(
               collector,
               poseStack,
               frame.emissiveRgba()
                  ? (
                     opacityRoute == VideoOpacityRoute.TRANSLUCENT
                        ? shaderpackSafeTranslucentRgba(frame.rgbaTexture())
                        : shaderpackSafeEmissiveRgba(frame.rgbaTexture())
                  )
                  : (
                     opacityRoute == VideoOpacityRoute.TRANSLUCENT
                        ? shaderpackSafeTranslucentRgba(frame.rgbaTexture())
                        : YuvVideoRenderTypes.videoRgbaEntity(frame.rgbaTexture())
                  ),
               -halfWidth,
               halfHeight,
               halfWidth,
               -halfHeight,
               rgbaDepthOffset,
               normalizedOpacity,
               brightness
            );
            if (frame.loadingProgressOverlay()) {
               submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight, normalizedOpacity, brightness);
            }

            return true;
         }
      }
   }

   public static boolean submitLoadingProgressOnPose(PortSubmitNodeCollector collector, PoseStack poseStack, float halfWidth, float halfHeight) {
      return submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight, 1.0F);
   }

   protected static boolean submitLoadingProgressOnPose(
      PortSubmitNodeCollector collector, PoseStack poseStack, float halfWidth, float halfHeight, float opacity
   ) {
      return submitLoadingProgressOnPose(collector, poseStack, halfWidth, halfHeight, opacity, 1.0F);
   }

   protected static boolean submitLoadingProgressOnPose(
      PortSubmitNodeCollector collector, PoseStack poseStack, float halfWidth, float halfHeight, float opacity, float brightness
   ) {
      if (collector != null && poseStack != null && !(halfWidth <= 0.0F) && !(halfHeight <= 0.0F)) {
         VideoOpacityRoute route = VideoOpacityRoute.choose(opacity);
         if (route == VideoOpacityRoute.SKIP) {
            return false;
         } else {
            float normalizedOpacity = VideoOpacityRoute.normalize(opacity);
            RenderType frameRenderType = route == VideoOpacityRoute.TRANSLUCENT
               ? shaderpackSafeTranslucentRgba(LOADING_PROGRESS_FRAME_TEXTURE)
               : shaderpackSafeEmissiveRgba(LOADING_PROGRESS_FRAME_TEXTURE);
            RenderType segmentRenderType = route == VideoOpacityRoute.TRANSLUCENT
               ? shaderpackSafeTranslucentRgba(LOADING_PROGRESS_SEGMENT_TEXTURE)
               : shaderpackSafeEmissiveRgba(LOADING_PROGRESS_SEGMENT_TEXTURE);
            submitLocalTexturedQuad(
               collector,
               poseStack,
               frameRenderType,
               pixelLeft(58, halfWidth),
               pixelTop(126, halfHeight),
               pixelRight(262, halfWidth),
               pixelBottom(136, halfHeight),
               0.004F,
               normalizedOpacity,
               brightness
            );
            submitLocalTexturedQuad(
               collector,
               poseStack,
               frameRenderType,
               pixelLeft(58, halfWidth),
               pixelTop(126, halfHeight),
               pixelRight(262, halfWidth),
               pixelBottom(136, halfHeight),
               -0.004F,
               normalizedOpacity,
               brightness
            );
            int movingX = 60 + (int)(System.nanoTime() / 12000000L % Math.max(1, 158));
            submitLocalTexturedQuad(
               collector,
               poseStack,
               segmentRenderType,
               pixelLeft(movingX, halfWidth),
               pixelTop(128, halfHeight),
               pixelRight(movingX + 42, halfWidth),
               pixelBottom(134, halfHeight),
               0.006F,
               normalizedOpacity,
               brightness
            );
            submitLocalTexturedQuad(
               collector,
               poseStack,
               segmentRenderType,
               pixelLeft(movingX, halfWidth),
               pixelTop(128, halfHeight),
               pixelRight(movingX + 42, halfWidth),
               pixelBottom(134, halfHeight),
               -0.006F,
               normalizedOpacity,
               brightness
            );
            return true;
         }
      } else {
         return false;
      }
   }

   protected static RenderType shaderpackSafeEmissiveRgba(ResourceLocation texture) {
      return YuvVideoRenderTypes.videoRgbaEmissiveEntity(texture);
   }

   protected static RenderType shaderpackSafeTranslucentRgba(ResourceLocation texture) {
      return YuvVideoRenderTypes.videoRgbaTranslucentEntity(texture);
   }

   public static boolean submitProjectorPrivacyOverlayOnPose(PortSubmitNodeCollector collector, PoseStack poseStack, float halfWidth, float halfHeight) {
      if (collector != null && poseStack != null && !(halfWidth <= 0.0F) && !(halfHeight <= 0.0F)) {
         HolographicPrivacyOverlay.submit(
            collector,
            poseStack,
            -halfWidth,
            halfHeight,
            0.003F,
            -halfWidth,
            -halfHeight,
            0.003F,
            halfWidth,
            -halfHeight,
            0.003F,
            halfWidth,
            halfHeight,
            0.003F
         );
         return true;
      } else {
         return false;
      }
   }

   protected static void submitLocalTexturedQuad(
      PortSubmitNodeCollector collector, PoseStack poseStack, RenderType renderType, float left, float top, float right, float bottom, float z, float opacity
   ) {
      submitLocalTexturedQuad(collector, poseStack, renderType, left, top, right, bottom, z, opacity, 1.0F);
   }

   protected static void submitLocalTexturedQuad(
      PortSubmitNodeCollector collector,
      PoseStack poseStack,
      RenderType renderType,
      float left,
      float top,
      float right,
      float bottom,
      float z,
      float opacity,
      float brightness
   ) {
      collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
         emitQuad(buffer, pose, left, top, z, left, bottom, z, right, bottom, z, right, top, z, false, opacity, brightness);
         emitQuad(buffer, pose, left, top, z, left, bottom, z, right, bottom, z, right, top, z, true, opacity, brightness);
      });
   }

   protected static void submitLocalTexturedQuadSingle(
      PortSubmitNodeCollector collector, PoseStack poseStack, RenderType renderType, float halfWidth, float halfHeight, float z, float opacity
   ) {
      submitLocalTexturedQuadSingle(collector, poseStack, renderType, halfWidth, halfHeight, z, opacity, 1.0F);
   }

   protected static void submitLocalTexturedQuadSingle(
      PortSubmitNodeCollector collector,
      PoseStack poseStack,
      RenderType renderType,
      float halfWidth,
      float halfHeight,
      float z,
      float opacity,
      float brightness
   ) {
      collector.submitCustomGeometry(
         poseStack,
         renderType,
         (pose, buffer) -> emitQuad(
            buffer,
            pose,
            -halfWidth,
            halfHeight,
            z,
            -halfWidth,
            -halfHeight,
            z,
            halfWidth,
            -halfHeight,
            z,
            halfWidth,
            halfHeight,
            z,
            false,
            opacity,
            brightness
         )
      );
   }

   protected static float pixelLeft(int x, float halfWidth) {
      return -halfWidth + x * (halfWidth * 2.0F) / 320.0F;
   }

   protected static float pixelRight(int x, float halfWidth) {
      return pixelLeft(x, halfWidth);
   }

   protected static float pixelTop(int y, float halfHeight) {
      return halfHeight - y * (halfHeight * 2.0F) / 180.0F;
   }

   protected static float pixelBottom(int y, float halfHeight) {
      return pixelTop(y, halfHeight);
   }

   protected static RenderType yuvRenderTypeForSnapshot(VideoBillboardState.ProjectorFrameSnapshot frame) {
      if (!IrisShaderpackCompat.shouldForceSafeProbeRenderType()
         && !IrisShaderpackCompat.shouldUseSingleSamplerProbe()
         && !IrisShaderpackCompat.isTexturedProbeProgram()) {
         if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", frame.format());
         }

         return frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12
            ? YuvVideoRenderTypes.nv12Entity(frame.yTexture(), frame.uTexture(), frame.vTexture())
            : YuvVideoRenderTypes.yuv420pEntity(frame.yTexture(), frame.uTexture(), frame.vTexture());
      } else {
         if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
         }

         return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(frame.yTexture());
      }
   }

   protected static RenderType yuvRenderTypeForCurrentIrisProgram(VideoYuvTextureSet textures) {
      if (!IrisShaderpackCompat.shouldForceSafeProbeRenderType()
         && !IrisShaderpackCompat.shouldUseSingleSamplerProbe()
         && !IrisShaderpackCompat.isTexturedProbeProgram()) {
         if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 {} RenderType", textures.format());
         }

         return textures.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12
            ? YuvVideoRenderTypes.nv12Entity(textures.yId(), textures.uId(), textures.vId())
            : YuvVideoRenderTypes.yuv420pEntity(textures.yId(), textures.uId(), textures.vId());
      } else {
         if (loggedIrisYuvRenderType.compareAndSet(false, true)) {
            LOGGER.debug("Iris/YUV: 首个视频 YUV draw 使用 TEXTURED probe RenderType，绑定 Sampler0/1/2=Y plane，占位规避 shaderpack sampler 校验；非真彩 YUV");
         }

         return YuvVideoRenderTypes.yOnlyTexturedProbeEntity(textures.yId());
      }
   }

   protected static void submitProjectorGeometry(
      PortSubmitNodeCollector collector,
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      RenderType renderType,
      int textureWidth,
      int textureHeight
   ) {
      float scale = Math.abs(projector.getProjectionScale());
      float aspect = (float)textureWidth / textureHeight;
      float halfHeight = 1.35F * scale * 0.5F;
      float halfWidth = halfHeight * aspect;
      if (ensureWorldAnchor(minecraft, camera, projector)) {
         Vec3 cameraPos = camera.getPosition();
         if (isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)) {
            double yawRad = Math.toRadians(anchorYawDeg);
            double pitchRad = Math.toRadians(projector.getProjectionPitch());
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
            float p0x = cx - rx + ux;
            float p0y = cy + uy;
            float p0z = cz - rz + uz;
            float p1x = cx - rx - ux;
            float p1y = cy - uy;
            float p1z = cz - rz - uz;
            float p2x = cx + rx - ux;
            float p2y = cy - uy;
            float p2z = cz + rz - uz;
            float p3x = cx + rx + ux;
            float p3y = cy + uy;
            float p3z = cz + rz + uz;
            PoseStack poseStack = new PoseStack();
            logFirstPreviewSubmit(true, textureWidth, textureHeight, camera, anchorX, anchorY, anchorZ, "projector-yuv");
            collector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
               emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, false, 1.0F, projector.getProjectionBrightness());
               emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, true, 1.0F, projector.getProjectionBrightness());
            });
         }
      }
   }

   protected static void logFirstPreviewSubmit(
      boolean yuv, int textureWidth, int textureHeight, Camera camera, double centerX, double centerY, double centerZ, String route
   ) {
      if (!firstPreviewSubmitLogged) {
         firstPreviewSubmitLogged = true;
         Vec3 cameraPos = camera.getPosition();
         double dx = centerX - cameraPos.x;
         double dy = centerY - cameraPos.y;
         double dz = centerZ - cameraPos.z;
         LOGGER.debug(
            "视频 quad 已提交: route={}, yuv={}, size={}x{}, distance={}, anchor=({}, {}, {}), camera=({}, {}, {}), shaderAvailable={}, yuvTextureSet={}",
            new Object[]{
               route,
               yuv,
               textureWidth,
               textureHeight,
               String.format(Locale.ROOT, "%.2f", Math.sqrt(dx * dx + dy * dy + dz * dz)),
               String.format(Locale.ROOT, "%.2f", centerX),
               String.format(Locale.ROOT, "%.2f", centerY),
               String.format(Locale.ROOT, "%.2f", centerZ),
               String.format(Locale.ROOT, "%.2f", cameraPos.x),
               String.format(Locale.ROOT, "%.2f", cameraPos.y),
               String.format(Locale.ROOT, "%.2f", cameraPos.z),
               isCustomYuvShaderAvailable(),
               LEGACY_TEXTURES.yuv() != null
            }
         );
      }
   }
}
