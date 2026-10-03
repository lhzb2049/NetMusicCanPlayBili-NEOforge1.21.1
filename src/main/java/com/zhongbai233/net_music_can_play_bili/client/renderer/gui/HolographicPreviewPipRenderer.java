package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainBlockEntityPreview;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainWireframeMesher;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortPictureInPictureRenderer;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFixedCorePolicy;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HolographicPreviewPipRenderer extends PortPictureInPictureRenderer<HolographicPreviewPipRenderState> {
   private static final Logger LOGGER = LoggerFactory.getLogger(HolographicPreviewPipRenderer.class);
   private static final float SCENE_ORIGIN_Y_RATIO = 0.72F;
   private static final float SCREEN_FACE_EPSILON = 0.0025F;
   private static final float DEFAULT_PREVIEW_SCALE = 38.0F;
   private static final float ORBIT_FOV_DEGREES = 45.0F;
   private static final float ORBIT_DEFAULT_CAMERA_DISTANCE = 6.5F;
   private static final float ORBIT_TARGET_Y = 0.9F;
   private static final float GIZMO_AXIS_WORLD_LEN = 0.72F;
   private static final float GIZMO_ARROW_LEN = 0.12F;
   private static final float GIZMO_ARROW_WING = 0.065F;
   private static final float GIZMO_LABEL_SIZE = 0.055F;
   private static final int GIZMO_RING_SEGMENTS = 48;
   private static final float PLAYER_EYE_Y = 1.62F;
   private static final ThreadLocal<Float> ACTIVE_LINE_WIDTH_SCALE = ThreadLocal.withInitial(() -> 1.0F);
   private static volatile HolographicPreviewPipRenderer instance;
   private final TerrainPreviewGpuCache terrainGpuCache = new TerrainPreviewGpuCache();

   public static HolographicPreviewPipRenderer acquire() {
      HolographicPreviewPipRenderer renderer = instance;
      if (renderer == null) {
         synchronized (HolographicPreviewPipRenderer.class) {
            renderer = instance;
            if (renderer == null) {
               renderer = new HolographicPreviewPipRenderer(null);
               instance = renderer;
            }
         }
      }

      return renderer;
   }

   public HolographicPreviewPipRenderer(BufferSource bufferSource) {
      super(bufferSource);
   }

   public void render(HolographicPreviewPipRenderState state) {
      super.render(state, Math.max(1, state.x1() - state.x0()), Math.max(1, state.y1() - state.y0()));
   }

   @Override
   public Class<HolographicPreviewPipRenderState> getRenderStateClass() {
      return HolographicPreviewPipRenderState.class;
   }

   protected void renderToTexture(HolographicPreviewPipRenderState state, PoseStack poseStack) {
      float previousLineWidthScale = ACTIVE_LINE_WIDTH_SCALE.get();
      ACTIVE_LINE_WIDTH_SCALE.set(lineWidthScale(state));
      if (!state.renderWorldTerrain()) {
         this.terrainGpuCache.releaseSession();
      }

      poseStack.pushPose();

      try {
         if (state.firstPerson()) {
            Lighting.setupForEntityInInventory();
            this.renderFirstPersonPreview(poseStack, state);
         } else {
            Lighting.setupLevel();
            this.renderOrbitPreview(poseStack, state);
            this.bufferSource.endBatch();
         }
      } catch (Throwable var9) {
         if (var9 instanceof VirtualMachineError fatal) {
            throw fatal;
         }

         if (var9 instanceof Error fatal) {
            throw fatal;
         }

         TerrainPreviewRenderDiagnostics.recordFailure();
         LOGGER.warn("PIP holographic preview failed; skipping this frame", var9);
      } finally {
         poseStack.popPose();
         ACTIVE_LINE_WIDTH_SCALE.set(previousLineWidthScale);
      }
   }

   private static float lineWidthScale(HolographicPreviewPipRenderState state) {
      if (state.firstPerson()) {
         return PreviewLineWidthPolicy.firstPerson();
      } else {
         CameraFrame frame = state.cameraFrame();
         if (frame == null) {
            return PreviewLineWidthPolicy.perspective(6.5F);
         } else if (frame.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            float projectionScaleY = Math.abs(frame.matrices().projection().m11());
            float halfHeight = projectionScaleY > 1.0E-6F ? 1.0F / projectionScaleY : 1.0F;
            return PreviewLineWidthPolicy.orthographic(halfHeight);
         } else {
            Vector3f cameraPosition = frame.matrices().view().invert().getTranslation(new Vector3f());
            float dx = cameraPosition.x;
            float dy = cameraPosition.y - 0.9F;
            float dz = cameraPosition.z;
            return PreviewLineWidthPolicy.perspective((float)Math.sqrt(dx * dx + dy * dy + dz * dz));
         }
      }
   }

   private void renderOrbitPreview(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      int width = Math.max(1, state.x1() - state.x0());
      int height = Math.max(1, state.y1() - state.y0());
      CameraFrame cameraFrame = state.cameraFrame();
      Matrix4f projection = cameraFrame != null
         ? cameraFrame.matrices().projection()
         : new Matrix4f().perspective((float)Math.toRadians(45.0), (float)width / height, 0.05F, 100.0F);
      VertexSorting sorting = cameraFrame != null && cameraFrame.mode() == EditorCameraMode.ORTHOGRAPHIC
         ? VertexSorting.ORTHOGRAPHIC_Z
         : VertexSorting.DISTANCE_TO_ORIGIN;
      RenderSystem.backupProjectionMatrix();
      RenderSystem.setProjectionMatrix(projection, sorting);

      try {
         PoseStack orbitPoseStack = new PoseStack();
         if (cameraFrame != null) {
            orbitPoseStack.mulPose(cameraFrame.matrices().view());
         } else {
            float previewScale = state.scale() / Math.max(1.0F, (float)Math.min(width, height)) * 200.0F;
            float cameraDistance = 247.0F / Math.max(1.0F, previewScale);
            orbitPoseStack.translate(0.0F, 0.0F, -cameraDistance);
            orbitPoseStack.mulPose(Axis.XP.rotationDegrees(state.previewPitch()));
            orbitPoseStack.mulPose(Axis.YP.rotationDegrees(state.previewYaw()));
            orbitPoseStack.translate(0.0F, -0.9F, 0.0F);
         }

         if (!state.renderWorldTerrain()) {
            this.submitDebugGarden(orbitPoseStack);
         }

         if (state.renderWorldTerrain()) {
            this.submitTerrainPreview(orbitPoseStack, state);
         }

         if (state.controlConsoleModel()) {
            this.renderControlConsole(orbitPoseStack);
         } else {
            this.renderPlayer(orbitPoseStack, state);
         }

         this.bufferSource.endBatch();
         this.drawGrid(orbitPoseStack);
         if (state.playerGlowing()) {
            this.drawPlayerGlowOutline(orbitPoseStack, state);
         }

         this.drawHolographicScreens(orbitPoseStack, state);
         if (selectedIndex(state) >= 0) {
            this.drawGizmo(orbitPoseStack, state);
         }

         this.bufferSource.endBatch();
      } finally {
         RenderSystem.restoreProjectionMatrix();
      }
   }

   private void renderFirstPersonPreview(PoseStack ignoredPipPoseStack, HolographicPreviewPipRenderState state) {
      int width = Math.max(1, state.x1() - state.x0());
      int height = Math.max(1, state.y1() - state.y0());
      CameraFrame cameraFrame = state.cameraFrame();
      Matrix4f projection = cameraFrame != null
         ? cameraFrame.matrices().projection()
         : new Matrix4f().perspective((float)Math.toRadians(state.fovDegrees()), (float)width / height, 0.05F, 100.0F);
      RenderSystem.backupProjectionMatrix();
      RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

      try {
         PoseStack poseStack = new PoseStack();
         if (cameraFrame != null) {
            poseStack.mulPose(cameraFrame.matrices().view());
         } else {
            poseStack.translate(0.0F, 0.0F, -0.001F);
            poseStack.scale(1.0F, -1.0F, -1.0F);
            poseStack.translate(0.0F, -1.62F, 0.0F);
         }

         this.drawHolographicScreensFrontOnly(poseStack, state);
         this.bufferSource.endBatch();
      } finally {
         RenderSystem.restoreProjectionMatrix();
      }
   }

   @Override
   public void close() {
      super.close();
      this.terrainGpuCache.close();
   }

   private void renderPlayer(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      Minecraft minecraft = Minecraft.getInstance();
      LivingEntity player = state.playerEntity();
      if (player != null) {
         float savedBodyRot = player.yBodyRot;
         float savedYRot = player.getYRot();
         float savedXRot = player.getXRot();
         float savedHeadRotO = player.yHeadRotO;
         float savedHeadRot = player.yHeadRot;
         player.yBodyRot = 0.0F;
         player.setYRot(0.0F);
         player.setXRot(0.0F);
         player.yHeadRot = 0.0F;
         player.yHeadRotO = 0.0F;
         EntityRenderDispatcher entityDispatcher = minecraft.getEntityRenderDispatcher();
         poseStack.pushPose();
         poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
         poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());

         try {
            entityDispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
            entityDispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> entityDispatcher.render(player, 0.0, 0.0, 0.0, 0.0F, 1.0F, poseStack, this.bufferSource, 15728880));
            entityDispatcher.setRenderShadow(true);
         } finally {
            poseStack.popPose();
            player.yBodyRot = savedBodyRot;
            player.setYRot(savedYRot);
            player.setXRot(savedXRot);
            player.yHeadRotO = savedHeadRotO;
            player.yHeadRot = savedHeadRot;
         }
      }
   }

   private void renderControlConsole(PoseStack poseStack) {
      Minecraft minecraft = Minecraft.getInstance();
      poseStack.pushPose();
      poseStack.translate(-0.5F, 0.0F, -0.5F);
      minecraft.getBlockRenderer()
         .renderSingleBlock(((Block)ModBlocks.CONTROL_CONSOLE.get()).defaultBlockState(), poseStack, this.bufferSource, 15728880, OverlayTexture.NO_OVERLAY);
      poseStack.popPose();
   }

   private void submitDebugGarden(PoseStack poseStack) {
      Minecraft minecraft = Minecraft.getInstance();

      for (int z = 0; z < 3; z++) {
         for (int x = 0; x < 3; x++) {
            poseStack.pushPose();
            poseStack.translate(x - 1.5F, -1.0F, z - 1.5F);
            minecraft.getBlockRenderer()
               .renderSingleBlock(Blocks.GRASS_BLOCK.defaultBlockState(), poseStack, this.bufferSource, 15728880, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
         }
      }

      poseStack.pushPose();
      poseStack.translate(-1.5F, 0.0F, -1.5F);
      minecraft.getBlockRenderer().renderSingleBlock(Blocks.DANDELION.defaultBlockState(), poseStack, this.bufferSource, 15728880, OverlayTexture.NO_OVERLAY);
      poseStack.popPose();
   }

   private void submitTerrainPreview(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      TerrainPreviewFrame frame = state.terrainFrame();
      if (frame != null && frame.generation() != 0L) {
         this.drawTerrainBounds(poseStack, frame);
         this.drawUnknownTerrain(poseStack, frame);
         this.bufferSource.endBatch();
         this.terrainGpuCache.updateAndRender(frame, poseStack.last().pose(), state);
         this.submitTerrainBlockEntities(poseStack, state, frame);
      } else {
         this.terrainGpuCache.releaseSession();
      }
   }

   private void submitTerrainBlockEntities(PoseStack poseStack, HolographicPreviewPipRenderState state, TerrainPreviewFrame frame) {
      if (!frame.blockEntities().isEmpty()) {
         Minecraft minecraft = Minecraft.getInstance();
         BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();

         for (TerrainBlockEntityPreview preview : frame.blockEntities()) {
            BlockEntity blockEntity = preview.blockEntity();
            if (blockEntity != null && !blockEntity.isRemoved() && dispatcher.getRenderer(blockEntity) != null) {
               poseStack.pushPose();
               poseStack.translate(
                  preview.worldPos().getX() - frame.originX() - 0.5F,
                  preview.worldPos().getY() - frame.originY(),
                  preview.worldPos().getZ() - frame.originZ() - 0.5F
               );

               try {
                  dispatcher.getRenderer(blockEntity).render(blockEntity, 0.0F, poseStack, this.bufferSource, 15728880, OverlayTexture.NO_OVERLAY);
                  TerrainPreviewRenderDiagnostics.recordBlockEntitySubmission();
               } catch (Throwable var14) {
                  if (var14 instanceof VirtualMachineError fatal) {
                     throw fatal;
                  }

                  LOGGER.debug("Skipping incompatible terrain block-entity renderer at {}", preview.worldPos(), var14);
               } finally {
                  poseStack.popPose();
               }
            }
         }
      }
   }

   private void drawTerrainBounds(PoseStack poseStack, TerrainPreviewFrame frame) {
      float minX = frame.bounds().minX() - frame.originX() - 0.5F;
      float minY = frame.bounds().minY() - frame.originY();
      float minZ = frame.bounds().minZ() - frame.originZ() - 0.5F;
      float maxX = frame.bounds().maxX() - frame.originX() + 0.5F;
      float maxY = frame.bounds().maxY() - frame.originY() + 1.0F;
      float maxZ = frame.bounds().maxZ() - frame.originZ() + 0.5F;
      this.box(poseStack.last(), minX, minY, minZ, maxX, maxY, maxZ, 1883629567, 1.2F);
   }

   private void drawUnknownTerrain(PoseStack poseStack, TerrainPreviewFrame frame) {
      Pose pose = poseStack.last();

      for (TerrainWireframeMesher.Segment segment : frame.wireframeSegments()) {
         boolean unknown = segment.material() == TerrainCellSample.RenderCategory.UNKNOWN;
         double centerDistance = Math.sqrt(
            Math.pow((segment.x1() + segment.x2()) * 0.5 - frame.coreCenterX(), 2.0)
               + Math.pow((segment.y1() + segment.y2()) * 0.5 - frame.coreCenterY(), 2.0)
               + Math.pow((segment.z1() + segment.z2()) * 0.5 - frame.coreCenterZ(), 2.0)
         );
         boolean mapColored = segment.color() != 0;
         int alpha = mapColored ? (int)Math.clamp(216.0 - centerDistance * 0.5, 144.0, 216.0) : (int)Math.clamp(100.0 - centerDistance * 0.35, 48.0, 100.0);
         int color = mapColored ? alpha << 24 | segment.color() & 16777215 : (unknown ? 944455526 : alpha << 24 | 7326584);
         this.line(
            pose,
            segment.x1() - frame.originX() - 0.5F,
            segment.y1() - frame.originY(),
            segment.z1() - frame.originZ() - 0.5F,
            segment.x2() - frame.originX() - 0.5F,
            segment.y2() - frame.originY(),
            segment.z2() - frame.originZ() - 0.5F,
            color,
            mapColored ? 1.2F : (unknown ? 0.7F : 0.9F)
         );
         if (!unknown && !mapColored) {
            this.drawWireBranch(pose, frame, segment);
         }
      }
   }

   private void drawWireBranch(Pose pose, TerrainPreviewFrame frame, TerrainWireframeMesher.Segment segment) {
      long seed = frame.originX() * 341873128712L ^ frame.originY() * 132897987541L ^ frame.originZ() * 42317861L;
      int axis = segment.x1() != segment.x2() ? 0 : (segment.y1() != segment.y2() ? 1 : 2);
      if (TerrainFixedCorePolicy.emitsBranch(seed, segment.x1(), segment.y1(), segment.z1(), axis)) {
         int direction = TerrainFixedCorePolicy.branchDirection(seed, segment.x1(), segment.y1(), segment.z1(), axis);
         float length = (float)TerrainFixedCorePolicy.branchLength(seed, segment.x1(), segment.y1(), segment.z1(), axis);
         float startX = segment.x1();
         float startY = segment.y1();
         float startZ = segment.z1();
         float dx = direction == 0 ? -length : (direction == 1 ? length : 0.0F);
         float dy = direction == 2 ? -length : (direction == 3 ? length : 0.0F);
         float dz = direction == 4 ? -length : (direction == 5 ? length : 0.0F);
         float endX = Math.clamp(startX + dx, (float)frame.bounds().minX(), frame.bounds().maxX() + 1.0F);
         float endY = Math.clamp(startY + dy, (float)frame.bounds().minY(), frame.bounds().maxY() + 1.0F);
         float endZ = Math.clamp(startZ + dz, (float)frame.bounds().minZ(), frame.bounds().maxZ() + 1.0F);
         float localStartX = startX - frame.originX() - 0.5F;
         float localStartY = startY - frame.originY();
         float localStartZ = startZ - frame.originZ() - 0.5F;
         float localEndX = endX - frame.originX() - 0.5F;
         float localEndY = endY - frame.originY();
         float localEndZ = endZ - frame.originZ() - 0.5F;
         this.line(pose, localStartX, localStartY, localStartZ, localEndX, localEndY, localEndZ, 1081068408, 0.75F);
      }
   }

   private void drawGizmo(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      int index = selectedIndex(state);
      poseStack.pushPose();
      poseStack.translate(
         screenOffsetX(state, index) + screenPivotX(state, index),
         1.55F + screenOffsetY(state, index) + screenPivotY(state, index),
         screenDistance(state, index) + screenPivotZ(state, index)
      );
      if (state.localSpace()) {
         poseStack.mulPose(Axis.YP.rotationDegrees(screenYaw(state, index)));
         poseStack.mulPose(Axis.XP.rotationDegrees(screenPitch(state, index)));
         poseStack.mulPose(Axis.ZP.rotationDegrees(screenRoll(state, index)));
      }

      Pose pose = poseStack.last();
      int handle = state.gizmoHandle();
      int encodedTool = state.gizmoTool();
      int tool = encodedTool & 0xFF;
      boolean centerSelected = handle == 1;
      boolean xSelected = handle == 2;
      boolean ySelected = handle == 3;
      boolean zSelected = handle == 4;
      boolean ringXSelected = handle == 5;
      boolean ringYSelected = handle == 6;
      boolean ringZSelected = handle == 7;
      this.line(pose, 0.0F, 0.0F, 0.0F, 0.72F, 0.0F, 0.0F, xSelected ? -34953 : -520139443, xSelected ? 2.4F : 1.5F);
      this.drawArrowHead(pose, 'x', xSelected ? -34953 : -520139443, xSelected ? 2.4F : 1.5F);
      this.line(pose, 0.0F, 0.0F, 0.0F, 0.0F, 0.72F, 0.0F, ySelected ? -8912999 : -531759246, ySelected ? 2.4F : 1.5F);
      this.drawArrowHead(pose, 'y', ySelected ? -8912999 : -531759246, ySelected ? 2.4F : 1.5F);
      this.line(pose, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.72F, zSelected ? -8931329 : -531782657, zSelected ? 2.4F : 1.5F);
      this.drawArrowHead(pose, 'z', zSelected ? -8931329 : -531782657, zSelected ? 2.4F : 1.5F);
      this.box(pose, -0.035F, -0.035F, -0.035F, 0.035F, 0.035F, 0.035F, centerSelected ? -1 : -1513240, 1.4F);
      this.drawAxisLabel(pose, 'X', 0.88F, 0.0F, 0.0F, -34953);
      this.drawAxisLabel(pose, 'Y', 0.0F, 0.88F, 0.0F, -8912999);
      this.drawAxisLabel(pose, (char)(tool == 2 ? 'S' : 'Z'), 0.0F, 0.0F, 0.88F, -8931329);
      if (tool == 1) {
         this.drawGizmoRing(pose, 'x', ringXSelected ? -26215 : -1057010355, ringXSelected ? 2.2F : 1.2F);
         this.drawGizmoRing(pose, 'y', ringYSelected ? -6684724 : -1068630158, ringYSelected ? 2.2F : 1.2F);
         this.drawGizmoRing(pose, 'z', ringZSelected ? -6699009 : -1068653569, ringZSelected ? 2.2F : 1.2F);
      }

      if (tool == 2) {
         this.box(pose, 0.68F, -0.04F, -0.04F, 0.76000005F, 0.04F, 0.04F, -34953, 1.4F);
         this.box(pose, -0.04F, 0.68F, -0.04F, 0.04F, 0.76000005F, 0.04F, -8912999, 1.4F);
         this.box(pose, -0.04F, -0.04F, 0.68F, 0.04F, 0.04F, 0.76000005F, -8931329, 1.4F);
      }

      poseStack.popPose();
   }

   private void drawArrowHead(Pose pose, char axis, int color, float lineWidth) {
      if (axis == 'x') {
         this.line(pose, 0.72F, 0.0F, 0.0F, 0.6F, 0.065F, 0.0F, color, lineWidth);
         this.line(pose, 0.72F, 0.0F, 0.0F, 0.6F, -0.065F, 0.0F, color, lineWidth);
         this.line(pose, 0.72F, 0.0F, 0.0F, 0.6F, 0.0F, 0.065F, color, lineWidth);
         this.line(pose, 0.72F, 0.0F, 0.0F, 0.6F, 0.0F, -0.065F, color, lineWidth);
      } else if (axis == 'y') {
         this.line(pose, 0.0F, 0.72F, 0.0F, 0.065F, 0.6F, 0.0F, color, lineWidth);
         this.line(pose, 0.0F, 0.72F, 0.0F, -0.065F, 0.6F, 0.0F, color, lineWidth);
         this.line(pose, 0.0F, 0.72F, 0.0F, 0.0F, 0.6F, 0.065F, color, lineWidth);
         this.line(pose, 0.0F, 0.72F, 0.0F, 0.0F, 0.6F, -0.065F, color, lineWidth);
      } else {
         this.line(pose, 0.0F, 0.0F, 0.72F, 0.065F, 0.0F, 0.6F, color, lineWidth);
         this.line(pose, 0.0F, 0.0F, 0.72F, -0.065F, 0.0F, 0.6F, color, lineWidth);
         this.line(pose, 0.0F, 0.0F, 0.72F, 0.0F, 0.065F, 0.6F, color, lineWidth);
         this.line(pose, 0.0F, 0.0F, 0.72F, 0.0F, -0.065F, 0.6F, color, lineWidth);
      }
   }

   private void drawAxisLabel(Pose pose, char label, float x, float y, float z, int color) {
      if (label == 'X') {
         this.line(pose, x - 0.055F, y - 0.055F, z, x + 0.055F, y + 0.055F, z, color, 1.7F);
         this.line(pose, x - 0.055F, y + 0.055F, z, x + 0.055F, y - 0.055F, z, color, 1.7F);
      } else if (label == 'Y') {
         this.line(pose, x - 0.055F, y + 0.055F, z, x, y, z, color, 1.7F);
         this.line(pose, x + 0.055F, y + 0.055F, z, x, y, z, color, 1.7F);
         this.line(pose, x, y, z, x, y - 0.055F, z, color, 1.7F);
      } else if (label == 'S') {
         this.line(pose, x + 0.055F, y + 0.055F, z, x - 0.055F, y + 0.055F, z, color, 1.7F);
         this.line(pose, x - 0.055F, y + 0.055F, z, x - 0.055F, y, z, color, 1.7F);
         this.line(pose, x - 0.055F, y, z, x + 0.055F, y, z, color, 1.7F);
         this.line(pose, x + 0.055F, y, z, x + 0.055F, y - 0.055F, z, color, 1.7F);
         this.line(pose, x + 0.055F, y - 0.055F, z, x - 0.055F, y - 0.055F, z, color, 1.7F);
      } else {
         this.line(pose, x - 0.055F, y + 0.055F, z, x + 0.055F, y + 0.055F, z, color, 1.7F);
         this.line(pose, x + 0.055F, y + 0.055F, z, x - 0.055F, y - 0.055F, z, color, 1.7F);
         this.line(pose, x - 0.055F, y - 0.055F, z, x + 0.055F, y - 0.055F, z, color, 1.7F);
      }
   }

   private void drawGizmoRing(Pose pose, char axis, int color, float lineWidth) {
      float radius = 0.47520003F;
      Vector3f previous = ringPoint(axis, radius, 0.0F);

      for (int i = 1; i <= 48; i++) {
         float angle = (float)((Math.PI * 2) * i / 48.0);
         Vector3f current = ringPoint(axis, (float)Math.cos(angle) * radius, (float)Math.sin(angle) * radius);
         this.line(pose, previous.x, previous.y, previous.z, current.x, current.y, current.z, color, lineWidth);
         previous = current;
      }
   }

   private static Vector3f ringPoint(char axis, float a, float b) {
      return switch (axis) {
         case 'x' -> new Vector3f(0.0F, a, b);
         case 'y' -> new Vector3f(a, 0.0F, b);
         default -> new Vector3f(a, b, 0.0F);
      };
   }

   private void drawPlayerGlowOutline(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      LivingEntity player = state.playerEntity();
      if (player != null) {
         poseStack.pushPose();
         poseStack.translate(state.playerTranslation().x, state.playerTranslation().y, state.playerTranslation().z);
         poseStack.scale(state.playerScale(), state.playerScale(), state.playerScale());
         float width = Math.max(0.58F, player.getBbWidth()) * 0.5F + 0.055F;
         float height = Math.max(1.8F, player.getBbHeight()) + 0.08F;
         float minY = -0.03F;
         Pose pose = poseStack.last();
         int outer = -263854081;
         int inner = -1874466817;
         this.box(pose, -width, minY, -width, width, height, width, outer, 2.5F);
         this.box(pose, -width * 0.92F, minY + 0.03F, -width * 0.92F, width * 0.92F, height - 0.03F, width * 0.92F, inner, 1.2F);
         poseStack.popPose();
      }
   }

   private void drawHolographicScreens(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      int count = screenCount(state);

      for (int i = 0; i < count; i++) {
         this.drawHolographicScreen(poseStack, state, i, i == selectedIndex(state));
      }
   }

   private void drawHolographicScreen(PoseStack poseStack, HolographicPreviewPipRenderState state, int index, boolean selected) {
      poseStack.pushPose();
      poseStack.translate(0.0F, 1.55F, 0.0F);
      poseStack.mulPose(screenTransform(state, index, 1.0F));
      float halfH = screenHeight(state, index) * 0.5F;
      float halfW = halfH * screenAspect(state, index);
      Pose pose = poseStack.last();
      int type = elementType(state, index);
      if (type == 1) {
         this.drawSubtitleElement(pose, halfW, halfH, selected);
      } else if (type == 2) {
         this.drawAudioElement(pose, halfW, halfH, selected);
      } else {
         this.drawScreenElement(pose, halfW, halfH, selected);
      }

      poseStack.popPose();
   }

   private void drawScreenElement(Pose pose, float halfW, float halfH, boolean selected) {
      float depth = Math.max(0.018F, Math.min(halfW, halfH) * 0.055F);
      int frontColor = selected ? -263854081 : -1471813633;
      int backColor = selected ? -788553917 : -1996513469;
      float frontWidth = selected ? 2.2F : 1.4F;
      this.screenWire(pose, -halfW, -halfH, halfW, halfH, depth, frontColor, frontWidth);
      this.screenWire(pose, -halfW, -halfH, halfW, halfH, -depth, backColor, selected ? 1.5F : 1.0F);
      this.line(pose, -halfW, -halfH, -depth, -halfW, -halfH, depth, frontColor, 1.0F);
      this.line(pose, halfW, -halfH, -depth, halfW, -halfH, depth, frontColor, 1.0F);
      this.line(pose, -halfW, halfH, -depth, -halfW, halfH, depth, frontColor, 1.0F);
      this.line(pose, halfW, halfH, -depth, halfW, halfH, depth, frontColor, 1.0F);
      float insetX = halfW * 0.12F;
      float insetY = halfH * 0.14F;
      this.screenWire(pose, -halfW + insetX, -halfH + insetY, halfW - insetX, halfH - insetY, depth + 0.0025F, frontColor, 1.0F);
      float arrow = Math.max(0.1F, Math.min(halfW, halfH) * 0.3F);
      float frontZ = depth + arrow * 0.58F;
      this.line(pose, 0.0F, 0.0F, depth, 0.0F, 0.0F, frontZ, frontColor, 2.0F);
      this.line(pose, 0.0F, 0.0F, frontZ, -arrow * 0.32F, 0.0F, frontZ - arrow * 0.34F, frontColor, 1.5F);
      this.line(pose, 0.0F, 0.0F, frontZ, arrow * 0.32F, 0.0F, frontZ - arrow * 0.34F, frontColor, 1.5F);
      this.line(pose, -halfW * 0.72F, -halfH * 0.72F, -depth - 0.0025F, halfW * 0.72F, halfH * 0.72F, -depth - 0.0025F, backColor, 1.2F);
      this.line(pose, -halfW * 0.72F, halfH * 0.72F, -depth - 0.0025F, halfW * 0.72F, -halfH * 0.72F, -depth - 0.0025F, backColor, 1.2F);
   }

   private void drawSubtitleElement(Pose pose, float halfW, float halfH, boolean selected) {
      float textHalfH = Math.max(0.1F, halfH * 0.42F);
      float textHalfW = Math.max(textHalfH * 2.8F, halfW * 0.72F);
      int color = selected ? -8054 : -1191194266;
      this.screenWire(pose, -textHalfW, -textHalfH, textHalfW, textHalfH, 0.0025F, color, selected ? 2.1F : 1.35F);
      float z = 0.005F;
      this.line(pose, -textHalfW * 0.78F, textHalfH * 0.46F, z, textHalfW * 0.78F, textHalfH * 0.46F, z, color, 1.5F);
      this.line(pose, -textHalfW * 0.66F, 0.0F, z, textHalfW * 0.66F, 0.0F, z, color, 1.5F);
      this.line(pose, -textHalfW * 0.48F, -textHalfH * 0.46F, z, textHalfW * 0.48F, -textHalfH * 0.46F, z, color, 1.5F);
      this.line(pose, -textHalfW, -textHalfH * 1.25F, 0.0F, textHalfW, -textHalfH * 1.25F, 0.0F, 1895813478, 1.0F);
   }

   private void drawAudioElement(Pose pose, float halfW, float halfH, boolean selected) {
      float bodyHalfH = Math.max(0.24F, halfH * 0.72F);
      float bodyHalfW = Math.max(0.14F, Math.min(halfW * 0.42F, bodyHalfH * 0.52F));
      float depth = bodyHalfW * 0.55F;
      int color = selected ? -2640897 : -1196131073;
      this.box(pose, -bodyHalfW, -bodyHalfH, -depth, bodyHalfW, bodyHalfH, depth, color, selected ? 2.0F : 1.25F);
      this.drawSpeakerCone(pose, 0.0F, bodyHalfH * 0.38F, depth + 0.0025F, bodyHalfW * 0.3F, color);
      this.drawSpeakerCone(pose, 0.0F, -bodyHalfH * 0.34F, depth + 0.0025F, bodyHalfW * 0.58F, color);
      this.drawSoundWave(pose, bodyHalfW + 0.06F, depth + 0.04F, bodyHalfH * 0.48F, color);
   }

   private void drawSpeakerCone(Pose pose, float centerX, float centerY, float z, float radius, int color) {
      float previousX = centerX + radius;
      float previousY = centerY;

      for (int i = 1; i <= 12; i++) {
         float angle = (float)((Math.PI * 2) * i / 12.0);
         float x = centerX + radius * (float)Math.cos(angle);
         float y = centerY + radius * (float)Math.sin(angle);
         this.line(pose, previousX, previousY, z, x, y, z, color, 1.2F);
         previousX = x;
         previousY = y;
      }
   }

   private void drawSoundWave(Pose pose, float startX, float z, float halfHeight, int color) {
      for (int wave = 0; wave < 2; wave++) {
         float radius = startX + wave * 0.1F;
         float previousX = radius;
         float previousY = -halfHeight * (0.58F + wave * 0.3F);

         for (int i = 1; i <= 8; i++) {
            float angle = (float)((-Math.PI / 2) + Math.PI * i / 8.0);
            float x = radius + (0.06F + wave * 0.04F) * (float)Math.cos(angle);
            float y = halfHeight * (0.58F + wave * 0.3F) * (float)Math.sin(angle);
            this.line(pose, previousX, previousY, z, x, y, z, color, 1.2F);
            previousX = x;
            previousY = y;
         }
      }
   }

   private void drawHolographicScreensFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state) {
      int count = screenCount(state);

      for (int i = 0; i < count; i++) {
         this.drawHolographicScreenFrontOnly(poseStack, state, i, i == selectedIndex(state));
      }
   }

   private void drawHolographicScreenFrontOnly(PoseStack poseStack, HolographicPreviewPipRenderState state, int index, boolean selected) {
      poseStack.pushPose();
      poseStack.translate(0.0F, 1.55F, 0.0F);
      poseStack.mulPose(screenTransform(state, index, -1.0F));
      float halfH = screenHeight(state, index) * 0.5F;
      float halfW = halfH * screenAspect(state, index);
      Pose pose = poseStack.last();
      VertexConsumer front = this.bufferSource.getBuffer(RenderType.debugQuads());
      emitQuad(front, pose, -halfW, -halfH, halfW, halfH, 0.0025F, selected ? -668211201 : -2144606209, false);
      poseStack.popPose();
   }

   private static int selectedIndex(HolographicPreviewPipRenderState state) {
      return state.selectedScreen() >= 0 && state.selectedScreen() < screenCount(state) ? state.selectedScreen() : -1;
   }

   private static int screenCount(HolographicPreviewPipRenderState state) {
      return Math.max(1, state.screenDistances() != null ? state.screenDistances().length : 0);
   }

   private static float screenDistance(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenDistances(), index, state.screenDistance());
   }

   private static float screenOffsetX(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenOffsetXs(), index, state.screenOffsetX());
   }

   private static float screenOffsetY(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenOffsetYs(), index, state.screenOffsetY());
   }

   private static float screenHeight(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenHeights(), index, state.screenHeight());
   }

   private static float screenAspect(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenAspects(), index, state.screenAspect());
   }

   private static float screenRoll(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenRolls(), index, state.screenRoll());
   }

   private static float screenYaw(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenYaws(), index, 0.0F);
   }

   private static float screenPitch(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenPitches(), index, 0.0F);
   }

   private static float screenScaleX(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenScaleXs(), index, 1.0F);
   }

   private static float screenScaleY(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenScaleYs(), index, 1.0F);
   }

   private static float screenScaleZ(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenScaleZs(), index, 1.0F);
   }

   private static float screenPivotX(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenPivotXs(), index, 0.0F);
   }

   private static float screenPivotY(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenPivotYs(), index, 0.0F);
   }

   private static float screenPivotZ(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenPivotZs(), index, 0.0F);
   }

   private static float screenSkewXByY(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenSkewXByYs(), index, 0.0F);
   }

   private static float screenSkewYByX(HolographicPreviewPipRenderState state, int index) {
      return valueAt(state.screenSkewYByXs(), index, 0.0F);
   }

   private static Matrix4f screenTransform(HolographicPreviewPipRenderState state, int index, float offsetYSign) {
      return EditorTransform.fromEulerDegrees(
            new Vector3f(screenOffsetX(state, index), offsetYSign * screenOffsetY(state, index), screenDistance(state, index)),
            screenYaw(state, index),
            screenPitch(state, index),
            screenRoll(state, index),
            new Vector3f(screenScaleX(state, index), screenScaleY(state, index), screenScaleZ(state, index)),
            new Vector3f(screenPivotX(state, index), screenPivotY(state, index), screenPivotZ(state, index)),
            screenSkewXByY(state, index),
            screenSkewYByX(state, index)
         )
         .matrix();
   }

   private static int elementType(HolographicPreviewPipRenderState state, int index) {
      int[] types = state.elementTypes();
      return types != null && index >= 0 && index < types.length ? types[index] : 0;
   }

   private static float valueAt(float[] values, int index, float fallback) {
      return values != null && index >= 0 && index < values.length ? values[index] : fallback;
   }

   private void drawGrid(PoseStack poseStack) {
      Pose pose = poseStack.last();

      for (int i = -4; i <= 4; i++) {
         this.line(pose, i * 0.5F, 0.0F, -2.5F, i * 0.5F, 0.0F, 3.5F, 1314802202);
      }

      for (int i = -5; i <= 7; i++) {
         float z = i * 0.5F;
         int alpha = z <= 0.0F ? 1717455386 : 1247693338;
         this.line(pose, -2.0F, 0.0F, z, 2.0F, 0.0F, z, alpha);
      }

      this.line(pose, -2.0F, 0.01F, 0.0F, 2.0F, 0.01F, 0.0F, -1438259201);
      this.line(pose, 0.0F, 0.01F, -2.5F, 0.0F, 0.01F, 3.5F, -1426083001);
   }

   private void box(Pose pose, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int color, float lineWidth) {
      this.line(pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
      this.line(pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
      this.line(pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
      this.line(pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);
      this.line(pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
      this.line(pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
      this.line(pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
      this.line(pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);
      this.line(pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
      this.line(pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
      this.line(pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
      this.line(pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
   }

   private void screenWire(Pose pose, float minX, float minY, float maxX, float maxY, float z, int color, float lineWidth) {
      this.line(pose, minX, minY, z, maxX, minY, z, color, lineWidth);
      this.line(pose, maxX, minY, z, maxX, maxY, z, color, lineWidth);
      this.line(pose, maxX, maxY, z, minX, maxY, z, color, lineWidth);
      this.line(pose, minX, maxY, z, minX, minY, z, color, lineWidth);
   }

   private static void emitQuad(VertexConsumer buffer, Pose pose, float minX, float minY, float maxX, float maxY, float z, int color, boolean reverse) {
      if (reverse) {
         vertex(buffer, pose, minX, maxY, z, color);
         vertex(buffer, pose, maxX, maxY, z, color);
         vertex(buffer, pose, maxX, minY, z, color);
         vertex(buffer, pose, minX, minY, z, color);
      } else {
         vertex(buffer, pose, minX, minY, z, color);
         vertex(buffer, pose, maxX, minY, z, color);
         vertex(buffer, pose, maxX, maxY, z, color);
         vertex(buffer, pose, minX, maxY, z, color);
      }
   }

   private void line(Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
      this.line(pose, x1, y1, z1, x2, y2, z2, color, 1.0F);
   }

   private void line(Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, int color, float lineWidth) {
      float visibleWidth = Math.clamp(lineWidth * ACTIVE_LINE_WIDTH_SCALE.get(), 1.0F, 8.0F);
      float dx = x2 - x1;
      float dy = y2 - y1;
      float dz = z2 - z1;
      float lengthSquared = dx * dx + dy * dy + dz * dz;
      float normalX;
      float normalY;
      float normalZ;
      if (lengthSquared > 1.0E-12F) {
         float inverseLength = 1.0F / (float)Math.sqrt(lengthSquared);
         normalX = dx * inverseLength;
         normalY = dy * inverseLength;
         normalZ = dz * inverseLength;
      } else {
         normalX = 0.0F;
         normalY = 1.0F;
         normalZ = 0.0F;
      }

      this.applyLineWidth(visibleWidth);
      VertexConsumer buffer = this.bufferSource.getBuffer(RenderType.lines());
      buffer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normalX, normalY, normalZ);
      buffer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normalX, normalY, normalZ);
   }

   private static void vertex(VertexConsumer buffer, Pose pose, float x, float y, float z, int color) {
      buffer.addVertex(pose, x, y, z).setColor(color).setNormal(0.0F, 0.0F, -1.0F);
   }

   @Override
   protected String getTextureLabel() {
      return "ncpb_holographic_preview";
   }

   @Override
   protected float getTranslateY(int textureHeight, int pixelScale) {
      return textureHeight * 0.72F;
   }
}
