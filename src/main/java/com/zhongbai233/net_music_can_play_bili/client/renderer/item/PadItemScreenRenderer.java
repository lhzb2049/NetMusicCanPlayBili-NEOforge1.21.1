package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.PadRenderProperties;
import com.zhongbai233.net_music_can_play_bili.client.renderer.RenderVertexUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;

public final class PadItemScreenRenderer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int FULL_BRIGHT = 15728880;
   private static final boolean VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
   private static final boolean VIDEO_RENDERDOC_PROBE = PadRenderProperties.videoRenderdocProbeEnabled();
   private static final float HELD_SURFACE_X = 0.53F;
   private static final float HELD_SURFACE_Y = 0.08F;
   private static final float HELD_SURFACE_Z = -1.08F;
   private static final float HELD_SURFACE_SCALE = 1.56F;
   private static final float HELD_SURFACE_LEFT_SHIFT = PadRenderProperties.handheldLeftShift();
   private static final float HOVER_TILT_DEGREES = 3.0F;
   private static final float DEVICE_BORDER = 0.04F;
   private static final float DEVICE_THICKNESS = 0.06F;
   private static final float SCREEN_FACE_Z_OFFSET = 0.02F;
   private static final float SCREEN_TEXTURE_Z_OFFSET = 0.024F;
   private static final float VIDEO_UNDERLAY_Z_OFFSET = 0.023F;
   private static final float VIDEO_TEXTURE_Z_OFFSET = 0.026F;
   private static final int MAP_LAYER_TICK_INTERVAL_TICKS = PadRenderProperties.mapLayer().tickIntervalTicks();
   private static final PlaybackSourceId FALLBACK_SOURCE_ID = PlaybackSourceId.of(new UUID(0L, 0L));
   private static final Map<PlaybackSourceId, PadGuiTexture> GUI_TEXTURES = new ConcurrentHashMap<>();
   private static int mapLayerTickCountdown;

   private PadItemScreenRenderer() {
   }

   public static void warmup() {
      textureFor(null).warmup();
   }

   public static void releaseAll() {
      GUI_TEXTURES.values().forEach(texture -> texture.close());
      GUI_TEXTURES.clear();
      MP4Nv12VideoLayer.releaseAllHandheld();
      MP4RgbaVideoLayer.releaseAllHandheld();
   }

   public static void releaseVideoLayers(UUID deviceId) {
      MP4Nv12VideoLayer.releaseHandheld(deviceId);
      MP4RgbaVideoLayer.releaseHandheld(deviceId);
   }

   public static void releaseDeviceResources(UUID deviceId) {
      if (deviceId != null) {
         PadGuiTexture guiTexture = GUI_TEXTURES.remove(PlaybackSourceId.of(deviceId));
         if (guiTexture != null) {
            guiTexture.close();
         }

         releaseVideoLayers(deviceId);
      }
   }

   private static boolean isLockedPad(UUID deviceId) {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null && deviceId != null ? PadClient.hasLockedIndexedPad(deviceId) : false;
   }

   public static void renderHeldOffscreenGuiFrameStart() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null) {
         renderHeldOffscreenGuiFrameStart(minecraft.player.getMainHandItem());
         renderHeldOffscreenGuiFrameStart(minecraft.player.getOffhandItem());
      }
   }

   public static void tickHeldMapLayers() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         mapLayerTickCountdown = 0;
      } else if (mapLayerTickCountdown > 0) {
         mapLayerTickCountdown--;
      } else {
         mapLayerTickCountdown = MAP_LAYER_TICK_INTERVAL_TICKS - 1;
         tickHeldMapLayer(minecraft.player.getMainHandItem());
         tickHeldMapLayer(minecraft.player.getOffhandItem());
      }
   }

   private static void tickHeldMapLayer(ItemStack stack) {
      if (stack.getItem() instanceof PadItem) {
         UUID deviceId = PadItem.readDeviceId(stack);
         textureFor(deviceId).tickMapLayer(deviceId);
      }
   }

   private static void renderHeldOffscreenGuiFrameStart(ItemStack stack) {
      if (stack.getItem() instanceof PadItem) {
         UUID deviceId = PadItem.readDeviceId(stack);
         markVideoSurfaceVisible(deviceId);
         textureFor(deviceId).renderFrameStart(deviceId);
      }
   }

   public static void renderMapLike(
      AbstractClientPlayer player,
      float partialTick,
      float pitch,
      InteractionHand hand,
      ItemStack stack,
      float swingProgress,
      float equipProgress,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      int light,
      HandheldArmRenderer armRenderer
   ) {
      PortSubmitNodeCollector collector = new PortSubmitNodeCollector(bufferSource);

      try {
         renderMapLikeInternal(player, partialTick, pitch, hand, stack, swingProgress, equipProgress, poseStack, bufferSource, light, armRenderer, collector);
      } finally {
         collector.end();
      }
   }

   private static void renderMapLikeInternal(
      AbstractClientPlayer player,
      float partialTick,
      float pitch,
      InteractionHand hand,
      ItemStack stack,
      float swingProgress,
      float equipProgress,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      int light,
      HandheldArmRenderer armRenderer,
      PortSubmitNodeCollector collector
   ) {
      boolean mainHand = hand == InteractionHand.MAIN_HAND;
      HumanoidArm arm = mainHand ? player.getMainArm() : player.getMainArm().getOpposite();
      UUID deviceId = stack.getItem() instanceof PadItem ? PadItem.readDeviceId(stack) : null;
      if (deviceId != null) {
         markVideoSurfaceVisible(deviceId);
         textureFor(deviceId).renderFrameStart(deviceId, partialTick);
      }

      poseStack.pushPose();
      if (PadFocusState.activeFor(hand)) {
         applyFocusedMapPose(arm, poseStack);
         renderFocusedHand(player, partialTick, poseStack, bufferSource, light, arm, armRenderer);
      } else {
         applyOneHandedMapPose(arm, swingProgress, equipProgress, poseStack, bufferSource, light, armRenderer);
      }

      poseStack.translate(-HELD_SURFACE_LEFT_SHIFT, 0.0F, 0.0F);
      submitTexturedSurface(poseStack, collector, deviceId);
      poseStack.popPose();
   }

   private static void renderFocusedHand(
      AbstractClientPlayer player,
      float partialTick,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      int light,
      HumanoidArm arm,
      HandheldArmRenderer armRenderer
   ) {
      if (!player.isInvisible()) {
         poseStack.pushPose();
         float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
         poseStack.translate(side * -0.085F, -0.083F, 0.255F);
         poseStack.mulPose(Axis.ZP.rotationDegrees(side * 3.0F));
         armRenderer.renderPlayerArm(poseStack, bufferSource, light, 0.0F, 0.0F, arm);
         poseStack.popPose();
      }
   }

   private static void applyFocusedMapPose(HumanoidArm arm, PoseStack poseStack) {
      float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
      poseStack.translate(side * 0.055F, -0.105F, 0.0F);
      poseStack.translate(side * 0.53F, 0.08F, -1.08F);
      poseStack.scale(1.56F, 1.56F, 1.56F);
   }

   private static void applyOneHandedMapPose(
      HumanoidArm arm,
      float swingProgress,
      float equipProgress,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      int light,
      HandheldArmRenderer armRenderer
   ) {
      float side = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
      poseStack.translate(side * 0.055F, -0.105F, 0.0F);
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && !minecraft.player.isInvisible()) {
         poseStack.pushPose();
         poseStack.mulPose(Axis.ZP.rotationDegrees(side * 6.0F));
         armRenderer.renderPlayerArm(poseStack, bufferSource, light, equipProgress, swingProgress, arm);
         poseStack.popPose();
      }

      poseStack.translate(side * 0.53F, 0.08F + equipProgress * -0.85F, -1.08F);
      poseStack.scale(1.56F, 1.56F, 1.56F);
   }

   private static void submitTexturedSurface(PoseStack poseStack, PortSubmitNodeCollector collector, UUID deviceId) {
      PadFocusState.renderTick();
      PadGuiTexture guiTexture = textureFor(deviceId);
      ResourceLocation textureId = guiTexture.textureId(deviceId);
      ResourceLocation bodyTextureId = guiTexture.whiteTextureId();
      boolean locked = isLockedPad(deviceId);
      markVideoSurfaceVisible(deviceId);
      boolean videoUnderlay = locked && hasVideoFrame(deviceId);
      float aspect = 1.75F;
      float halfHeight = 0.39F;
      float halfWidth = halfHeight * aspect;
      float bx0 = -halfWidth - 0.04F;
      float by0 = halfHeight + 0.04F;
      float bx1 = halfWidth + 0.04F;
      float by1 = -halfHeight - 0.04F;
      float backZ = -0.06F;
      float bodyDepthBoost = 0.02F;
      float screenFaceZ = 0.02F;
      float screenTextureZ = 0.024F;
      float flatBackZ = -0.04F;
      poseStack.pushPose();
      poseStack.scale(0.66F, 0.66F, 0.66F);
      float hoverX = PadFocusState.hoverX();
      float hoverY = PadFocusState.hoverY();
      poseStack.mulPose(Axis.XP.rotationDegrees(hoverY * 3.0F * PadFocusState.progress(1.0F)));
      poseStack.mulPose(Axis.YP.rotationDegrees(hoverX * 3.0F * PadFocusState.progress(1.0F)));
      publishProjectedQuad(poseStack.last().pose(), -halfWidth, halfHeight, 0.024F, halfWidth, -halfHeight, 0.024F);
      collector.submitCustomGeometry(poseStack, RenderType.entityCutout(bodyTextureId), (pose, buffer) -> {
         emitSolidQuad(buffer, pose, bx1, by0, -0.04F, bx0, by0, -0.04F, -halfWidth, halfHeight, -0.04F, halfWidth, halfHeight, -0.04F, -14538442);
         emitSolidQuad(buffer, pose, bx0, by1, -0.04F, bx1, by1, -0.04F, halfWidth, -halfHeight, -0.04F, -halfWidth, -halfHeight, -0.04F, -15196889);
         emitSolidQuad(buffer, pose, bx0, by0, -0.04F, -halfWidth, halfHeight, -0.04F, -halfWidth, -halfHeight, -0.04F, bx0, by1, -0.04F, -14011582);
         emitSolidQuad(buffer, pose, bx1, by0, -0.04F, halfWidth, halfHeight, -0.04F, halfWidth, -halfHeight, -0.04F, bx1, by1, -0.04F, -12498081);
         emitSolidQuad(buffer, pose, bx0, by0, 0.02F, bx0, by1, 0.02F, bx1, by1, 0.02F, bx1, by0, 0.02F, -16447733);
         emitSolidQuad(buffer, pose, bx0, by0, 0.02F, bx0, by1, 0.02F, bx1, by1, 0.02F, bx1, by0, 0.02F, -16448249);
         emitCameraDot(buffer, pose, bx0 + 0.05F, by0 - 0.022F, 0.023F);
         emitSideButton(buffer, pose, bx1 + 0.012F, halfHeight * 0.5F, 0.04F, 0.13F, -9734776);
         emitSideButton(buffer, pose, bx1 + 0.012F, halfHeight * 0.22F, 0.04F, 0.13F, -10590085);
      });
      if (videoUnderlay) {
         submitVideoLayer(poseStack, collector, deviceId, -halfWidth, halfHeight, halfWidth, -halfHeight, 0.023F, true, bodyTextureId);
      } else {
         logVideoSkip(deviceId, locked, "underlay-disabled");
      }

      collector.submitCustomGeometry(
         poseStack,
         videoUnderlay ? RenderType.entityTranslucent(textureId) : RenderType.entityCutout(textureId),
         (pose, buffer) -> emitTexturedQuad(
            buffer, pose, -halfWidth, halfHeight, 0.024F, -halfWidth, -halfHeight, 0.024F, halfWidth, -halfHeight, 0.024F, halfWidth, halfHeight, 0.024F, true
         )
      );
      if (!videoUnderlay) {
         submitVideoLayer(poseStack, collector, deviceId, -halfWidth, halfHeight, halfWidth, -halfHeight, 0.026F, locked, bodyTextureId);
      }

      if (VIDEO_RENDERDOC_PROBE && !hasVideoFrame(deviceId)) {
         submitRenderDocProbe(poseStack, collector, bodyTextureId, -halfWidth, halfHeight, halfWidth, -halfHeight, 0.032F, -1711341313);
      }

      poseStack.popPose();
   }

   private static void markVideoSurfaceVisible(UUID deviceId) {
      if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
         MP4HandheldVideoClient.markVisible(deviceId);
      }
   }

   private static boolean hasVideoFrame(UUID deviceId) {
      // 阶段 3：本地图片是静态帧，没有「正在播放」也应当有画面
      return deviceId != null && MP4HandheldVideoClient.latestFrame(deviceId) != null;
   }

   private static void submitVideoLayer(
      PoseStack poseStack,
      PortSubmitNodeCollector collector,
      UUID deviceId,
      float x0,
      float y0,
      float x1,
      float y1,
      float z,
      boolean fullSurface,
      ResourceLocation probeTextureId
   ) {
      if (deviceId == null) {
         logVideoSkip(null, fullSurface, "missing-device-id");
      } else if (MP4HandheldVideoClient.latestFrame(deviceId) == null) {
         logVideoSkip(deviceId, fullSurface, "no-latest-frame");
      } else {
         MP4HandheldVideoClient.markVisible(deviceId);
         float insetX = fullSurface ? 0.0F : (x1 - x0) * 0.025F;
         float insetY = fullSurface ? 0.0F : (y0 - y1) * 0.045F;
         float vx0 = x0 + insetX;
         float vy0 = y0 - insetY;
         float vx1 = x1 - insetX;
         float vy1 = y1 + insetY;
         boolean useRgbaFallback = IrisShaderpackCompat.isShaderPackInUse() || MP4HandheldVideoClient.hasStaticImage(deviceId);
         MP4RgbaVideoLayer rgbaLayer = MP4RgbaVideoLayer.forHandheldDevice(deviceId);
         boolean rgba = useRgbaFallback && rgbaLayer.uploadLatest(deviceId);
         MP4Nv12VideoLayer nv12Layer = MP4Nv12VideoLayer.forHandheldDevice(deviceId);
         if (rgba || nv12Layer.uploadLatest(deviceId) && nv12Layer.textureSet() != null) {
            logVideoSubmit(deviceId, fullSurface, rgba ? "rgba" : "nv12");
            collector.submitCustomGeometry(
               poseStack,
               rgba
                  ? YuvVideoRenderTypes.padVideoRgbaEntity(rgbaLayer.textureId())
                  : YuvVideoRenderTypes.padNv12Entity(nv12Layer.textureSet().yId(), nv12Layer.textureSet().uId(), nv12Layer.textureSet().vId()),
               (pose, buffer) -> emitTexturedQuad(buffer, pose, vx0, vy0, z, vx0, vy1, z, vx1, vy1, z, vx1, vy0, z, false)
            );
            if (VIDEO_RENDERDOC_PROBE) {
               submitRenderDocProbe(poseStack, collector, probeTextureId, vx0, vy0, vx1, vy1, z + 0.004F, -1711341313);
            }
         } else {
            logVideoSkip(deviceId, fullSurface, "upload-failed");
         }
      }
   }

   private static void submitRenderDocProbe(
      PoseStack poseStack, PortSubmitNodeCollector collector, ResourceLocation probeTextureId, float x0, float y0, float x1, float y1, float z, int color
   ) {
      collector.submitCustomGeometry(
         poseStack,
         RenderType.entityTranslucent(probeTextureId),
         (pose, buffer) -> emitSolidQuad(buffer, pose, x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z, color)
      );
   }

   private static void logVideoSkip(UUID deviceId, boolean fullSurface, String reason) {
      if (VIDEO_DEBUG_LOG) {
         LOGGER.info(
            "Pad video layer skipped: reason={} device={} fullSurface={} hasPlayback={} hasFrame={}",
            new Object[]{
               reason,
               deviceId,
               fullSurface,
               deviceId != null && ClientMediaPlayback.hasPlayback(deviceId),
               deviceId != null && MP4HandheldVideoClient.latestFrame(deviceId) != null
            }
         );
      }
   }

   private static void logVideoSubmit(UUID deviceId, boolean fullSurface, String mode) {
      if (VIDEO_DEBUG_LOG) {
         LOGGER.info("Pad video layer submitted: mode={} device={} fullSurface={}", new Object[]{mode, deviceId, fullSurface});
      }
   }

   private static void publishProjectedQuad(Matrix4f modelMatrix, float x0, float y0, float z0, float x1, float y1, float z1) {
      if (PadFocusState.active()) {
         Minecraft minecraft = Minecraft.getInstance();
         Window window = minecraft.getWindow();
         int physicalWidth = Math.max(1, window.getWidth());
         int physicalHeight = Math.max(1, window.getHeight());
         int guiWidth = Math.max(1, window.getGuiScaledWidth());
         int guiHeight = Math.max(1, window.getGuiScaledHeight());
         PadItemScreenRenderer.ScreenPoint topLeft = projectToGui(modelMatrix, x0, y0, z0, physicalWidth, physicalHeight, guiWidth, guiHeight);
         PadItemScreenRenderer.ScreenPoint bottomLeft = projectToGui(modelMatrix, x0, y1, z1, physicalWidth, physicalHeight, guiWidth, guiHeight);
         PadItemScreenRenderer.ScreenPoint bottomRight = projectToGui(modelMatrix, x1, y1, z1, physicalWidth, physicalHeight, guiWidth, guiHeight);
         PadItemScreenRenderer.ScreenPoint topRight = projectToGui(modelMatrix, x1, y0, z0, physicalWidth, physicalHeight, guiWidth, guiHeight);
         if (topLeft != null && bottomLeft != null && bottomRight != null && topRight != null) {
            PadFocusState.updateProjectedQuad(
               topLeft.x(), topLeft.y(), topRight.x(), topRight.y(), bottomRight.x(), bottomRight.y(), bottomLeft.x(), bottomLeft.y(), guiWidth, guiHeight
            );
         }
      }
   }

   private static PadItemScreenRenderer.ScreenPoint projectToGui(
      Matrix4f modelMatrix, float x, float y, float z, int physicalWidth, int physicalHeight, int guiWidth, int guiHeight
   ) {
      Matrix4f projection = firstPersonProjection(physicalWidth, physicalHeight);
      Vector4f clip = new Vector4f(x, y, z, 1.0F).mul(modelMatrix).mul(RenderSystem.getModelViewMatrix()).mul(projection);
      if (Math.abs(clip.w()) < 1.0E-5F) {
         return null;
      } else {
         float ndcX = clip.x() / clip.w();
         float ndcY = clip.y() / clip.w();
         float screenX = (ndcX * 0.5F + 0.5F) * physicalWidth;
         float screenY = (0.5F - ndcY * 0.5F) * physicalHeight;
         return new PadItemScreenRenderer.ScreenPoint(
            screenX * guiWidth / Math.max(1.0F, (float)physicalWidth), screenY * guiHeight / Math.max(1.0F, (float)physicalHeight)
         );
      }
   }

   private static Matrix4f firstPersonProjection(int physicalWidth, int physicalHeight) {
      float aspect = (float)physicalWidth / Math.max(1, physicalHeight);
      return new Matrix4f().perspective((float)Math.toRadians(70.0), aspect, 0.05F, Minecraft.getInstance().gameRenderer.getDepthFar());
   }

   private static void emitCameraDot(VertexConsumer buffer, Pose pose, float x, float y, float z) {
      float r = 0.01F;
      emitSolidQuad(buffer, pose, x - r, y + r, z, x - r, y - r, z, x + r, y - r, z, x + r, y + r, z, -15657700);
   }

   private static void emitSideButton(VertexConsumer buffer, Pose pose, float x, float centerY, float width, float height, int color) {
      float sign = x > 0.0F ? 1.0F : -1.0F;
      float innerX = x - sign * width;
      float y0 = centerY + height * 0.5F;
      float y1 = centerY - height * 0.5F;
      emitSolidQuad(buffer, pose, innerX, y0, 0.026F, x, y0, 0.026F, x, y1, 0.026F, innerX, y1, 0.026F, color);
   }

   private static void emitTexturedQuad(
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
      boolean flipV
   ) {
      float v0 = flipV ? 1.0F : 0.0F;
      float v1 = flipV ? 0.0F : 1.0F;
      texturedVertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
      texturedVertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
      texturedVertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
      texturedVertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
      texturedVertex(buffer, pose, p3x, p3y, p3z, 1.0F, v0);
      texturedVertex(buffer, pose, p2x, p2y, p2z, 1.0F, v1);
      texturedVertex(buffer, pose, p1x, p1y, p1z, 0.0F, v1);
      texturedVertex(buffer, pose, p0x, p0y, p0z, 0.0F, v0);
   }

   private static void emitSolidQuad(
      VertexConsumer buffer,
      Pose pose,
      float ax,
      float ay,
      float az,
      float bx,
      float by,
      float bz,
      float cx,
      float cy,
      float cz,
      float dx,
      float dy,
      float dz,
      int color
   ) {
      solidVertex(buffer, pose, ax, ay, az, color);
      solidVertex(buffer, pose, bx, by, bz, color);
      solidVertex(buffer, pose, cx, cy, cz, color);
      solidVertex(buffer, pose, dx, dy, dz, color);
   }

   private static void solidVertex(VertexConsumer buffer, Pose pose, float x, float y, float z, int color) {
      buffer.addVertex(pose, x, y, z)
         .setColor(color)
         .setUv(0.01F, 0.01F)
         .setOverlay(OverlayTexture.NO_OVERLAY)
         .setLight(15728880)
         .setNormal(pose, 0.0F, 0.0F, 1.0F);
   }

   private static void texturedVertex(VertexConsumer buffer, Pose pose, float x, float y, float z, float u, float v) {
      RenderVertexUtils.texturedVertex(buffer, pose, x, y, z, u, v);
   }

   private static PadGuiTexture textureFor(UUID deviceId) {
      PlaybackSourceId key = deviceId != null ? PlaybackSourceId.of(deviceId) : FALLBACK_SOURCE_ID;
      return GUI_TEXTURES.computeIfAbsent(key, id -> new PadGuiTexture(id.toString().replace('-', '_')));
   }

   private record ScreenPoint(float x, float y) {
   }
}
