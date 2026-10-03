package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.HandheldArmRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ItemInHandRenderer.class})
public abstract class ItemInHandRendererMixin {
   @Inject(
      method = {"renderArmWithItem"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private void net_music_can_play_bili$renderMp4AsMap(
      AbstractClientPlayer player,
      float partialTick,
      float pitch,
      InteractionHand hand,
      float swingProgress,
      ItemStack stack,
      float equipProgress,
      PoseStack poseStack,
      MultiBufferSource bufferSource,
      int light,
      CallbackInfo ci
   ) {
      if (ControlConsoleRoamingSession.isActive()) {
         ci.cancel();
      } else if (stack.is((Item)ModItems.MP4.get())) {
         MP4ItemScreenRenderer.renderMapLike(
            player,
            partialTick,
            pitch,
            hand,
            stack,
            swingProgress,
            equipProgress,
            poseStack,
            bufferSource,
            light,
            new HandheldArmRenderer() {
               @Override
               public void renderMapHand(PoseStack poseStack, MultiBufferSource bufferSourcex, int lightx, HumanoidArm arm) {
                  ItemInHandRendererMixin.this.net_music_can_play_bili$renderMapHand(poseStack, bufferSourcex, lightx, arm);
               }

               @Override
               public void renderPlayerArm(
                  PoseStack poseStack, MultiBufferSource bufferSourcex, int lightx, float equipProgressx, float swingProgressx, HumanoidArm arm
               ) {
                  ItemInHandRendererMixin.this.net_music_can_play_bili$renderPlayerArm(poseStack, bufferSourcex, lightx, equipProgressx, swingProgressx, arm);
               }
            }
         );
         ci.cancel();
      } else {
         if (stack.is((Item)ModItems.PAD.get())) {
            PadItemScreenRenderer.renderMapLike(
               player,
               partialTick,
               pitch,
               hand,
               stack,
               swingProgress,
               equipProgress,
               poseStack,
               bufferSource,
               light,
               new HandheldArmRenderer() {
                  @Override
                  public void renderMapHand(PoseStack poseStack, MultiBufferSource bufferSource, int light, HumanoidArm arm) {
                     ItemInHandRendererMixin.this.net_music_can_play_bili$renderMapHand(poseStack, bufferSource, light, arm);
                  }

                  @Override
                  public void renderPlayerArm(
                     PoseStack poseStack, MultiBufferSource bufferSource, int light, float equipProgress, float swingProgress, HumanoidArm arm
                  ) {
                     ItemInHandRendererMixin.this.net_music_can_play_bili$renderPlayerArm(poseStack, bufferSource, light, equipProgress, swingProgress, arm);
                  }
               }
            );
            ci.cancel();
         }
      }
   }

   @Invoker(
      value = "renderMapHand",
      remap = false
   )
   protected abstract void net_music_can_play_bili$renderMapHand(PoseStack var1, MultiBufferSource var2, int var3, HumanoidArm var4);

   @Invoker(
      value = "renderPlayerArm",
      remap = false
   )
   protected abstract void net_music_can_play_bili$renderPlayerArm(PoseStack var1, MultiBufferSource var2, int var3, float var4, float var5, HumanoidArm var6);
}
