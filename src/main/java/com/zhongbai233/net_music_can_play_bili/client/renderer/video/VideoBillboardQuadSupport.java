package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ProjectorScreenBounds;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3fc;
import org.joml.Vector4f;

abstract class VideoBillboardQuadSupport extends VideoBillboardState {
   protected static VideoBillboardQuadSupport.PreviewQuad transformedLocalQuad(Matrix4f pose, float halfWidth, float halfHeight) {
      Vector4f p0 = new Vector4f(-halfWidth, halfHeight, 0.0F, 1.0F).mul(pose);
      Vector4f p1 = new Vector4f(-halfWidth, -halfHeight, 0.0F, 1.0F).mul(pose);
      Vector4f p2 = new Vector4f(halfWidth, -halfHeight, 0.0F, 1.0F).mul(pose);
      Vector4f p3 = new Vector4f(halfWidth, halfHeight, 0.0F, 1.0F).mul(pose);
      return new VideoBillboardQuadSupport.PreviewQuad(p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z, p3.x, p3.y, p3.z);
   }

   protected static void drawWithEventModelView(RenderType renderType, MeshData mesh, RenderLevelStageEvent event) {
      Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
      modelViewStack.pushMatrix();

      try {
         modelViewStack.set(event.getModelViewMatrix());
         renderType.draw(mesh);
      } finally {
         modelViewStack.popMatrix();
      }
   }

   protected static void logFirstImmediateQuad(VideoBillboardQuadSupport.PreviewQuad quad, Camera camera, boolean cameraRelative, boolean forceWorldAnchored) {
      if (!firstImmediateQuadLogged) {
         firstImmediateQuadLogged = true;
         Vec3 cameraPos = camera.getPosition();
         LOGGER.debug(
            "Iris/YUV immediate quad: cameraRelative={}, forceWorldAnchored={}, pose='{}', anchor=({}, {}, {}), anchorYaw={}, camera=({}, {}, {}), p0=({}, {}, {}), p1=({}, {}, {}), p2=({}, {}, {}), p3=({}, {}, {})",
            new Object[]{
               cameraRelative,
               forceWorldAnchored,
               YUV_IMMEDIATE_POSE,
               fmt(anchorX),
               fmt(anchorY),
               fmt(anchorZ),
               fmt(anchorYawDeg),
               fmt(cameraPos.x),
               fmt(cameraPos.y),
               fmt(cameraPos.z),
               fmt(quad.p0x()),
               fmt(quad.p0y()),
               fmt(quad.p0z()),
               fmt(quad.p1x()),
               fmt(quad.p1y()),
               fmt(quad.p1z()),
               fmt(quad.p2x()),
               fmt(quad.p2y()),
               fmt(quad.p2z()),
               fmt(quad.p3x()),
               fmt(quad.p3y()),
               fmt(quad.p3z())
            }
         );
      }
   }

   protected static String fmt(double value) {
      return String.format(Locale.ROOT, "%.2f", value);
   }

   protected static VideoBillboardQuadSupport.PreviewQuad computePreviewQuad(
      Minecraft minecraft,
      Camera camera,
      VideoProjectorBlockEntity projector,
      int textureWidth,
      int textureHeight,
      boolean cameraRelative,
      boolean forceWorldAnchored
   ) {
      float scale = projector != null ? Math.abs(projector.getProjectionScale()) : 1.0F;
      float aspect = (float)textureWidth / textureHeight;
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
      if (!forceWorldAnchored && !WORLD_ANCHORED && !LEGACY_PREVIEW.requiresProjector()) {
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
            return null;
         }

         Vec3 cameraPos = camera.getPosition();
         double dx = anchorX - cameraPos.x;
         double dy = anchorY - cameraPos.y;
         double dz = anchorZ - cameraPos.z;
         if (projector != null
            ? !isProjectorWithinRenderDistance(cameraPos, projector, anchorX, anchorY, anchorZ, aspect)
            : dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQR) {
            return null;
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
         float cx = cameraRelative ? (float)(anchorX - cameraPos.x) : (float)anchorX;
         float cy = cameraRelative ? (float)(anchorY - cameraPos.y) : (float)anchorY;
         float cz = cameraRelative ? (float)(anchorZ - cameraPos.z) : (float)anchorZ;
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

      return new VideoBillboardQuadSupport.PreviewQuad(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z);
   }

   protected static boolean ensureWorldAnchor(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      if (projector != null) {
         BlockPos pos = projector.getBlockPos();
         anchorX = pos.getX() + 0.5 + projector.getProjectionDistanceX();
         anchorY = pos.getY() + projector.getProjectionHeight();
         anchorZ = pos.getZ() + 0.5 + projector.getProjectionDistanceZ();
         anchorYawDeg = projector.getProjectionYaw();
         anchorInitialized = true;
         return true;
      } else if (LEGACY_PREVIEW.requiresProjector()) {
         anchorInitialized = false;
         return false;
      } else if (anchorInitialized) {
         return true;
      } else {
         Player player = minecraft.player;
         if (player == null) {
            Vec3 pos = camera.getPosition();
            anchorX = pos.x;
            anchorY = pos.y;
            anchorZ = pos.z;
            anchorYawDeg = 0.0F;
            anchorInitialized = true;
            return true;
         } else {
            double yawRad = Math.toRadians(player.getYRot());
            double forwardX = -Math.sin(yawRad);
            double forwardZ = Math.cos(yawRad);
            anchorX = player.getX() + forwardX * WORLD_ANCHOR_DISTANCE;
            anchorY = player.getEyeY();
            anchorZ = player.getZ() + forwardZ * WORLD_ANCHOR_DISTANCE;
            anchorYawDeg = player.getYRot();
            anchorInitialized = true;
            LOGGER.debug(
               "视频投影测试面已锚定到世界坐标: ({}, {}, {}), yaw={}",
               new Object[]{
                  String.format(Locale.ROOT, "%.2f", anchorX),
                  String.format(Locale.ROOT, "%.2f", anchorY),
                  String.format(Locale.ROOT, "%.2f", anchorZ),
                  String.format(Locale.ROOT, "%.1f", anchorYawDeg)
               }
            );
            return true;
         }
      }
   }

   protected static void observeCameraContinuity(Minecraft minecraft) {
      if (minecraft != null && minecraft.level != null) {
         Camera camera = minecraft.gameRenderer.getMainCamera();
         Vec3 pos = camera.getPosition();
         String dimension = minecraft.level.dimension().location().toString();
         if (!cameraContinuityInitialized) {
            rememberCameraPosition(pos, dimension);
         } else {
            double dx = pos.x - lastCameraX;
            double dy = pos.y - lastCameraY;
            double dz = pos.z - lastCameraZ;
            if (!dimension.equals(lastCameraDimension) || dx * dx + dy * dy + dz * dz > CAMERA_TELEPORT_RESET_DISTANCE_SQR) {
               resetLocalRenderAnchors();
            }

            rememberCameraPosition(pos, dimension);
         }
      } else {
         cameraContinuityInitialized = false;
      }
   }

   protected static void rememberCameraPosition(Vec3 pos, String dimension) {
      lastCameraX = pos.x;
      lastCameraY = pos.y;
      lastCameraZ = pos.z;
      lastCameraDimension = dimension;
      cameraContinuityInitialized = true;
   }

   protected static void resetLocalRenderAnchors() {
      anchorInitialized = false;
      firstImmediateQuadLogged = false;
      PROJECTOR_VISIBILITY_CACHE.clear();
   }

   protected static VideoProjectorBlockEntity activeVideoProjector(Minecraft minecraft) {
      BlockPos projectorPos = LEGACY_PREVIEW.primaryProjector();
      if (projectorPos != null && minecraft.level != null) {
         return minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector ? projector : null;
      } else {
         return null;
      }
   }

   protected static List<VideoProjectorBlockEntity> activeVideoProjectors(Minecraft minecraft) {
      if (minecraft.level == null) {
         return List.of();
      } else {
         List<VideoProjectorBlockEntity> projectors = new ArrayList<>();

         for (BlockPos pos : LEGACY_PREVIEW.projectors()) {
            if (minecraft.level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
               projectors.add(projector);
            }
         }

         LEGACY_PREVIEW.removeProjectorsIf(
            posx -> !(minecraft.level.getBlockEntity(posx) instanceof VideoProjectorBlockEntity) && !berManagedProjectorPositions.contains(posx)
         );
         LEGACY_PREVIEW.setPrimaryProjector(projectors.isEmpty() ? null : projectors.get(0).getBlockPos().immutable());
         return projectors;
      }
   }

   protected static void emitQuad(
      VertexConsumer buffer,
      Pose pose,
      float p0x,
      float p0y,
      float p0z,
      float p1x,
      float p1y,
      float p1z,
      float p2x,
      float p2y,
      float p2z,
      float p3x,
      float p3y,
      float p3z,
      boolean reverse,
      float opacity
   ) {
      emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, reverse, opacity, 1.0F);
   }

   protected static void emitQuad(
      VertexConsumer buffer,
      Pose pose,
      float p0x,
      float p0y,
      float p0z,
      float p1x,
      float p1y,
      float p1z,
      float p2x,
      float p2y,
      float p2z,
      float p3x,
      float p3y,
      float p3z,
      boolean reverse,
      float opacity,
      float brightness
   ) {
      if (reverse) {
         vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F, opacity, brightness);
         vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F, opacity, brightness);
         vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F, opacity, brightness);
         vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F, opacity, brightness);
      } else {
         vertex(buffer, pose, p0x, p0y, p0z, 0.0F, 0.0F, opacity, brightness);
         vertex(buffer, pose, p1x, p1y, p1z, 0.0F, 1.0F, opacity, brightness);
         vertex(buffer, pose, p2x, p2y, p2z, 1.0F, 1.0F, opacity, brightness);
         vertex(buffer, pose, p3x, p3y, p3z, 1.0F, 0.0F, opacity, brightness);
      }
   }

   protected static void emitQuad(
      VertexConsumer buffer,
      Pose pose,
      float p0x,
      float p0y,
      float p0z,
      float p1x,
      float p1y,
      float p1z,
      float p2x,
      float p2y,
      float p2z,
      float p3x,
      float p3y,
      float p3z,
      boolean reverse
   ) {
      emitQuad(buffer, pose, p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z, p3x, p3y, p3z, reverse, 1.0F);
   }

   protected static void vertex(VertexConsumer buffer, Pose pose, float x, float y, float z, float u, float v, float opacity) {
      vertex(buffer, pose, x, y, z, u, v, opacity, 1.0F);
   }

   protected static void vertex(VertexConsumer buffer, Pose pose, float x, float y, float z, float u, float v, float opacity, float brightness) {
      RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v, opacity, brightness);
   }

   static boolean isProjectorScreenRenderable(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector, double dotThreshold) {
      if (minecraft != null && camera != null && projector != null) {
         BlockPos projectorPos = projector.getBlockPos().immutable();
         long nowNs = System.nanoTime();
         int thresholdKey = (int)Math.round(dotThreshold * 1000.0);
         VideoBillboardState.VisibilitySample cached = PROJECTOR_VISIBILITY_CACHE.get(projectorPos);
         if (cached != null && cached.thresholdKey() == thresholdKey && nowNs - cached.createdNanoTime() <= Math.max(0L, VIEW_OCCLUSION_CACHE_NANOS)) {
            return cached.visible();
         } else {
            boolean visible = computeProjectorScreenRenderable(minecraft, camera, projector, dotThreshold);
            PROJECTOR_VISIBILITY_CACHE.put(projectorPos, new VideoBillboardState.VisibilitySample(nowNs, thresholdKey, visible));
            return visible;
         }
      } else {
         return false;
      }
   }

   protected static boolean computeProjectorScreenRenderable(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector, double dotThreshold) {
      BlockPos pos = projector.getBlockPos();
      double centerX = pos.getX() + 0.5 + projector.getProjectionDistanceX();
      double centerY = pos.getY() + projector.getProjectionHeight();
      double centerZ = pos.getZ() + 0.5 + projector.getProjectionDistanceZ();
      Vec3 cameraPos = camera.getPosition();
      VideoBillboardState.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentProjectorDisplayFrame(pos);
      double aspect = frame.width() > 0 && frame.height() > 0 ? (double)frame.width() / frame.height() : 1.7777777777777777;
      if (!isProjectorWithinRenderDistance(cameraPos, projector, centerX, centerY, centerZ, aspect)) {
         return false;
      } else {
         for (Vec3 sample : projectorVisibilitySamples(projector, centerX, centerY, centerZ, aspect)) {
            if (isScreenInView(camera, sample.x, sample.y, sample.z, dotThreshold) && !isOccluded(minecraft, cameraPos, sample, pos)) {
               return true;
            }
         }

         return false;
      }
   }

   protected static boolean isProjectorWithinRenderDistance(
      Vec3 cameraPos, VideoProjectorBlockEntity projector, double centerX, double centerY, double centerZ, double aspect
   ) {
      AABB bounds = ProjectorScreenBounds.aroundCenter(
         centerX, centerY, centerZ, projector.getProjectionYaw(), projector.getProjectionPitch(), projector.getProjectionScale(), aspect, 0.0
      );
      return ProjectorScreenBounds.distanceToSqr(bounds, cameraPos) <= MAX_RENDER_DISTANCE_SQR;
   }

   protected static List<Vec3> projectorVisibilitySamples(VideoProjectorBlockEntity projector, double centerX, double centerY, double centerZ, double aspect) {
      float scale = Math.abs(projector.getProjectionScale());
      double halfHeight = 1.35F * scale * 0.5 * VIEW_SAMPLE_EDGE_SCALE;
      double halfWidth = halfHeight * Math.max(0.125, Math.min(8.0, aspect));
      double yawRad = Math.toRadians(projector.getProjectionYaw());
      double pitchRad = Math.toRadians(projector.getProjectionPitch());
      double rightX = Math.cos(yawRad);
      double rightZ = Math.sin(yawRad);
      double forwardX = -Math.sin(yawRad);
      double forwardZ = Math.cos(yawRad);
      double upX = forwardX * Math.sin(pitchRad);
      double upY = Math.cos(pitchRad);
      double upZ = forwardZ * Math.sin(pitchRad);
      Vec3 center = new Vec3(centerX, centerY, centerZ);
      Vec3 right = new Vec3(rightX * halfWidth, 0.0, rightZ * halfWidth);
      Vec3 up = new Vec3(upX * halfHeight, upY * halfHeight, upZ * halfHeight);
      return List.of(center, center.add(right).add(up), center.add(right).subtract(up), center.subtract(right).add(up), center.subtract(right).subtract(up));
   }

   protected static boolean isOccluded(Minecraft minecraft, Vec3 cameraPos, Vec3 target, BlockPos projectorPos) {
      return VIEW_OCCLUSION_CHECK && minecraft.level != null ? VideoScreenOcclusion.isOccluded(minecraft.level, cameraPos, target, projectorPos) : false;
   }

   protected static boolean isProjectorScreenPredictedVisible(Minecraft minecraft, Camera camera, VideoProjectorBlockEntity projector) {
      if (minecraft != null && camera != null && projector != null && minecraft.player != null) {
         BlockPos pos = projector.getBlockPos();
         double centerX = pos.getX() + 0.5 + projector.getProjectionDistanceX();
         double centerY = pos.getY() + projector.getProjectionHeight();
         double centerZ = pos.getZ() + 0.5 + projector.getProjectionDistanceZ();
         AABB bounds = ProjectorScreenBounds.aroundCenter(
            centerX, centerY, centerZ, projector.getProjectionYaw(), projector.getProjectionPitch(), projector.getProjectionScale(), 1.7777777777777777, 0.0
         );
         Vec3 cameraPos = camera.getPosition();
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

   static double viewDotThreshold() {
      return VIEW_DOT_THRESHOLD;
   }

   protected static boolean isScreenInView(Camera camera, double centerX, double centerY, double centerZ, double dotThreshold) {
      Vec3 cameraPos = camera.getPosition();
      double dx = centerX - cameraPos.x;
      double dy = centerY - cameraPos.y;
      double dz = centerZ - cameraPos.z;
      double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (len <= 1.0E-4) {
         return true;
      } else {
         Vector3fc forward = camera.getLookVector();
         double dot = dx / len * forward.x() + dy / len * forward.y() + dz / len * forward.z();
         return dot > dotThreshold;
      }
   }

   protected record PreviewQuad(
      float p0x, float p0y, float p0z, float p1x, float p1y, float p1z, float p2x, float p2y, float p2z, float p3x, float p3y, float p3z
   ) {
   }
}
