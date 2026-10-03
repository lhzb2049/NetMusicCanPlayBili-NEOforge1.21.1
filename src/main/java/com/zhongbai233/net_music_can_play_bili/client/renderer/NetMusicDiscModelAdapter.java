package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.github.tartaricacid.netmusic.client.model.ModelMusicPlayer;
import com.github.tartaricacid.netmusic.client.renderer.MusicPlayerRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

final class NetMusicDiscModelAdapter {
   private static final float MODEL_SCALE = 0.75F;
   private static final double ANCHOR_Y = 1.3329083333333334;
   private final ModelMusicPlayer model;

   NetMusicDiscModelAdapter(Context context) {
      ModelPart bakedRoot = context.bakeLayer(ModelMusicPlayer.LAYER);
      this.model = new ModelMusicPlayer(bakedRoot);
      if (bakedRoot.hasChild("root")) {
         bakedRoot.getChild("root").visible = false;
      }
   }

   void submit(
      boolean hasDisc,
      boolean playing,
      Direction facing,
      long gameTime,
      float partialTick,
      int lightCoords,
      PoseStack poseStack,
      MultiBufferSource bufferSource
   ) {
      if (hasDisc) {
         ModelPart disc = this.model.getDiscBone();
         disc.visible = true;
         disc.yRot = playing ? DiscRotationPolicy.rotationAt(gameTime, partialTick) : 0.0F;

         int clockwiseQuarterTurns = switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
         };
         DiscPlacementPolicy.Placement placement = DiscPlacementPolicy.forClockwiseQuarterTurns(clockwiseQuarterTurns);
         poseStack.pushPose();
         poseStack.scale(0.75F, 0.75F, 0.75F);
         poseStack.translate(placement.anchorX(), 1.3329083333333334, placement.anchorZ());
         poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - facing.get2DDataValue() * 90.0F));
         poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
         VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(MusicPlayerRenderer.TEXTURE));
         this.model.renderToBuffer(poseStack, consumer, lightCoords, OverlayTexture.NO_OVERLAY, -1);
         poseStack.popPose();
      }
   }
}
