package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldMediaProfile;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

final class MP4GuiTexture implements AutoCloseable {
   static final int WIDTH = MP4HandheldMediaProfile.SCREEN.portraitWidth();
   static final int HEIGHT = MP4HandheldMediaProfile.SCREEN.portraitHeight();
   private static final ResourceLocation WHITE_TEXTURE_ID = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/mp4_gui_white");
   private static DynamicTexture sharedWhiteTexture;
   private final MP4OffscreenGuiRenderer offscreenRenderer;

   MP4GuiTexture(String textureKey) {
      this.offscreenRenderer = new MP4OffscreenGuiRenderer(textureKey);
   }

   ResourceLocation textureId() {
      return this.textureId(null);
   }

   ResourceLocation textureId(UUID deviceId) {
      return this.offscreenRenderer.textureId(deviceId);
   }

   void renderFrameStart(UUID deviceId) {
      this.offscreenRenderer.renderFrameStart(deviceId);
   }

   ResourceLocation whiteTextureId() {
      this.ensureWhiteTexture();
      return WHITE_TEXTURE_ID;
   }

   void warmup() {
      MP4FontManager.warmup();
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
