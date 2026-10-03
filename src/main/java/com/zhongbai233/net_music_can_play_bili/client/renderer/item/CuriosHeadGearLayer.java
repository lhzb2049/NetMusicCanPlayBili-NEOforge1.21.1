package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin.Model;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers;

public final class CuriosHeadGearLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
   private CuriosHeadGearLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
      super(parent);
   }

   public static void register(AddLayers event) {
      for (Model skin : event.getSkins()) {
         if (event.getSkin(skin) instanceof PlayerRenderer playerRenderer) {
            playerRenderer.addLayer(new CuriosHeadGearLayer(playerRenderer));
         }
      }
   }

   public void render(
      PoseStack poseStack,
      MultiBufferSource buffer,
      int light,
      AbstractClientPlayer player,
      float limbSwing,
      float limbSwingAmount,
      float partialTick,
      float ageInTicks,
      float netHeadYaw,
      float headPitch
   ) {
      ItemStack stack = curiosHeadGear(player);
      if (!stack.isEmpty()) {
         poseStack.pushPose();
         ((PlayerModel)this.getParentModel()).getHead().translateAndRotate(poseStack);
         CustomHeadLayer.translateToHead(poseStack, false);
         Minecraft.getInstance()
            .getItemRenderer()
            .renderStatic(
               player,
               stack,
               ItemDisplayContext.HEAD,
               false,
               poseStack,
               buffer,
               player.level(),
               light,
               LivingEntityRenderer.getOverlayCoords(player, 0.0F),
               player.getId()
            );
         poseStack.popPose();
      }
   }

   private static ItemStack curiosHeadGear(AbstractClientPlayer player) {
      return EquippedMediaItems.firstCuriosEquipped(
         player, stack -> stack.getItem() == ModItems.CAT_HEADPHONES.get() || stack.getItem() == ModItems.HOLOGRAPHIC_GLASSES.get()
      );
   }
}
