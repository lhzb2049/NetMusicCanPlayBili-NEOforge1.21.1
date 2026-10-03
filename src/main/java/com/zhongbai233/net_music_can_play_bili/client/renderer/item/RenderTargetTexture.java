package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

final class RenderTargetTexture extends AbstractTexture {
   private final RenderTarget target;

   RenderTargetTexture(RenderTarget target) {
      this.target = target;
      this.refreshView();
   }

   void refreshView() {
   }

   public int getId() {
      RenderSystem.assertOnRenderThreadOrInit();
      return this.target.getColorTextureId();
   }

   public void bind() {
      if (!RenderSystem.isOnRenderThreadOrInit()) {
         RenderSystem.recordRenderCall(() -> GlStateManager._bindTexture(this.getId()));
      } else {
         GlStateManager._bindTexture(this.getId());
      }
   }

   public void load(ResourceManager resourceManager) {
   }

   public void releaseId() {
   }

   public void close() {
   }
}
