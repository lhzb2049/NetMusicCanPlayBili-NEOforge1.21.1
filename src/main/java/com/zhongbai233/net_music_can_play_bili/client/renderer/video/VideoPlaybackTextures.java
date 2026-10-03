package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

final class VideoPlaybackTextures {
   private final String sessionId;
   private final ResourceLocation firstTextureId;
   private final ResourceLocation secondTextureId;
   private final ResourceLocation yTextureId;
   private final ResourceLocation uTextureId;
   private final ResourceLocation vTextureId;
   private DynamicTexture frontTexture;
   private DynamicTexture backTexture;
   private VideoYuvTextureSet yuvTextureSet;
   private ResourceLocation frontTextureId;
   private ResourceLocation backTextureId;

   VideoPlaybackTextures(String sessionId) {
      this.sessionId = sessionId;
      String suffix = Integer.toUnsignedString(sessionId.hashCode(), 16);
      this.firstTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/bili_video_preview_" + suffix + "_a");
      this.secondTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/bili_video_preview_" + suffix + "_b");
      this.yTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/bili_video_preview_" + suffix + "_y");
      this.uTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/bili_video_preview_" + suffix + "_u");
      this.vTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/bili_video_preview_" + suffix + "_v");
      this.frontTextureId = this.firstTextureId;
      this.backTextureId = this.secondTextureId;
   }

   boolean uploadDecodedFrame(VideoBillboardState.DecodedFrame frame, int width, int height) {
      return VideoBillboardPreview.isCustomYuvShaderAvailable() && isYuvFrameFormat(frame.format())
         ? this.uploadYuv(frame, width, height)
         : this.uploadRgba(Yuv420pConverter.toUploadRgba(frame, width, height), width, height);
   }

   boolean uploadRgba(byte[] rgba, int width, int height) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && rgba.length >= width * height * 4) {
         this.ensureRgbaTextures(width, height);
         NativeImage image = this.backTexture.getPixels();
         if (image == null) {
            return false;
         } else {
            VideoFrameUploader.uploadRgba(image, rgba, width, height);
            this.backTexture.upload();
            this.swapTextures();
            this.releaseYuvTextures();
            return true;
         }
      } else {
         return false;
      }
   }

   boolean uploadYuv(VideoBillboardState.DecodedFrame frame, int width, int height) {
      if (Minecraft.getInstance().level == null) {
         return false;
      } else {
         this.ensureYuvTextureSet(frame.format());
         if (!this.uploadYuvFrameData(frame, width, height)) {
            return false;
         } else {
            this.releaseRgbaTextures();
            return true;
         }
      }
   }

   VideoBillboardState.ProjectorFrameSnapshot snapshot(int width, int height) {
      if (this.frontTexture != null) {
         return new VideoBillboardState.ProjectorFrameSnapshot(
            true, false, this.frontTextureId, null, null, null, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, width, height, false, false, 0.0F
         );
      } else {
         return this.yuvTextureSet != null
            ? new VideoBillboardState.ProjectorFrameSnapshot(
               true,
               true,
               null,
               this.yuvTextureSet.yId(),
               this.yuvTextureSet.uId(),
               this.yuvTextureSet.vId(),
               this.yuvTextureSet.format(),
               this.yuvTextureSet.width(),
               this.yuvTextureSet.height(),
               false,
               false,
               0.0F
            )
            : VideoBillboardState.ProjectorFrameSnapshot.empty();
      }
   }

   boolean hasRgbaTexture() {
      return this.frontTexture != null;
   }

   ResourceLocation rgbaTextureId() {
      return this.frontTextureId;
   }

   VideoYuvTextureSet yuvTextureSet() {
      return this.yuvTextureSet;
   }

   boolean hasYuvTexture() {
      return this.yuvTextureSet != null;
   }

   void release() {
      this.releaseRgbaTextures();
      this.releaseYuvTextures();
   }

   private boolean uploadYuvFrameData(VideoBillboardState.DecodedFrame frame, int width, int height) {
      if (this.yuvTextureSet != null && frame != null && this.yuvTextureSet.format() == frame.format()) {
         ByteBuffer buffer = frame.buffer();
         return buffer != null ? this.yuvTextureSet.upload(buffer, frame.byteLength(), width, height) : this.yuvTextureSet.upload(frame.data(), width, height);
      } else {
         return false;
      }
   }

   private void ensureYuvTextureSet(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
      Fmp4NativeVideoDecoder.DecodedFrame.Format normalized = format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
         ? Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
         : Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
      if (this.yuvTextureSet == null || this.yuvTextureSet.format() != normalized) {
         this.releaseYuvTextures();
         this.yuvTextureSet = (VideoYuvTextureSet)(normalized == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P
            ? new Yuv420pTextureSet(this.yTextureId, this.uTextureId, this.vTextureId, "bili_video_" + this.sessionId + "_yuv420p")
            : new Nv12TextureSet(this.yTextureId, this.uTextureId, this.yTextureId, "bili_video_" + this.sessionId + "_nv12"));
      }
   }

   private static boolean isYuvFrameFormat(Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
      return format == Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P || format == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12;
   }

   private void ensureRgbaTextures(int width, int height) {
      if (this.frontTexture != null && this.backTexture != null) {
         NativeImage image = this.frontTexture.getPixels();
         NativeImage backImage = this.backTexture.getPixels();
         if (image != null
            && image.getWidth() == width
            && image.getHeight() == height
            && backImage != null
            && backImage.getWidth() == width
            && backImage.getHeight() == height) {
            return;
         }
      }

      this.release();
      this.frontTexture = new DynamicTexture(width, height, false);
      this.backTexture = new DynamicTexture(width, height, false);
      this.frontTextureId = this.firstTextureId;
      this.backTextureId = this.secondTextureId;
      Minecraft.getInstance().getTextureManager().register(this.frontTextureId, this.frontTexture);
      Minecraft.getInstance().getTextureManager().register(this.backTextureId, this.backTexture);
   }

   private void releaseRgbaTextures() {
      if (this.frontTexture != null) {
         Minecraft.getInstance().getTextureManager().release(this.frontTextureId);
         this.frontTexture.close();
         this.frontTexture = null;
      }

      if (this.backTexture != null && !this.backTextureId.equals(this.frontTextureId)) {
         Minecraft.getInstance().getTextureManager().release(this.backTextureId);
         this.backTexture.close();
         this.backTexture = null;
      } else if (this.backTexture != null) {
         this.backTexture.close();
         this.backTexture = null;
      }

      this.frontTextureId = this.firstTextureId;
      this.backTextureId = this.secondTextureId;
   }

   private void releaseYuvTextures() {
      if (this.yuvTextureSet != null) {
         this.yuvTextureSet.close();
         this.yuvTextureSet = null;
      }
   }

   private void swapTextures() {
      DynamicTexture oldFront = this.frontTexture;
      this.frontTexture = this.backTexture;
      this.backTexture = oldFront;
      ResourceLocation oldFrontId = this.frontTextureId;
      this.frontTextureId = this.backTextureId;
      this.backTextureId = oldFrontId;
   }
}
