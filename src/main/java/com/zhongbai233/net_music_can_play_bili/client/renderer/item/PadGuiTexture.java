package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

final class PadGuiTexture implements AutoCloseable {
   static final int WIDTH = 448;
   static final int HEIGHT = 256;
   private static final ResourceLocation WHITE_TEXTURE_ID = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/pad_gui_white");
   private static DynamicTexture sharedWhiteTexture;
   private final PadOffscreenGuiRenderer offscreenRenderer;

   PadGuiTexture(String textureKey) {
      this.offscreenRenderer = new PadOffscreenGuiRenderer(textureKey);
   }

   ResourceLocation textureId(UUID deviceId) {
      return this.offscreenRenderer.textureId(deviceId);
   }

   void renderFrameStart(UUID deviceId) {
      this.offscreenRenderer.renderFrameStart(deviceId, 1.0F);
   }

   void renderFrameStart(UUID deviceId, float partialTick) {
      this.offscreenRenderer.renderFrameStart(deviceId, partialTick);
   }

   void tickMapLayer(UUID deviceId) {
      this.offscreenRenderer.tickMapLayer(deviceId);
   }

   ResourceLocation whiteTextureId() {
      this.ensureWhiteTexture();
      return WHITE_TEXTURE_ID;
   }

   void warmup() {
      this.ensureWhiteTexture();
   }

   private void ensureWhiteTexture() {
      if (sharedWhiteTexture == null) {
         sharedWhiteTexture = new DynamicTexture(1, 1, false);
         NativeImage image = sharedWhiteTexture.getPixels();
         if (image != null) {
            image.setPixelRGBA(0, 0, -1);
            sharedWhiteTexture.upload();
         }

         Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE_ID, sharedWhiteTexture);
      }
   }

   @Override
   public void close() {
      this.offscreenRenderer.close();
   }
}
