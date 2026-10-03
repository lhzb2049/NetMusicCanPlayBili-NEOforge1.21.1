package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.platform.NativeImage;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor.ABGR32;

final class MP4RgbaVideoLayer implements AutoCloseable {
   private static final Map<PlaybackSourceId, MP4RgbaVideoLayer> LAYERS = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, MP4RgbaVideoLayer> HANDHELD_LAYERS = new ConcurrentHashMap<>();
   private final ResourceLocation textureId;
   private DynamicTexture texture;
   private long uploadedSequence = -1L;
   private int uploadedWidth;
   private int uploadedHeight;

   private MP4RgbaVideoLayer(UUID deviceId) {
      this(deviceId, "mp4_video");
   }

   private MP4RgbaVideoLayer(UUID deviceId, String texturePrefix) {
      String suffix = deviceId.toString().replace('-', '_');
      this.textureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/" + texturePrefix + "_" + suffix + "_rgba");
   }

   static MP4RgbaVideoLayer forDevice(UUID deviceId) {
      if (deviceId == null) {
         throw new IllegalArgumentException("MP4 RGBA video layer requires a device id");
      } else {
         return LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId), sourceId -> new MP4RgbaVideoLayer(sourceId.value()));
      }
   }

   static MP4RgbaVideoLayer forHandheldDevice(UUID deviceId) {
      if (deviceId == null) {
         throw new IllegalArgumentException("MP4 handheld RGBA video layer requires a device id");
      } else {
         return HANDHELD_LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId), sourceId -> new MP4RgbaVideoLayer(sourceId.value(), "pad_video"));
      }
   }

   static void releaseAll() {
      LAYERS.values().forEach(layer -> layer.close());
      LAYERS.clear();
      releaseAllHandheld();
   }

   static void releaseAllHandheld() {
      HANDHELD_LAYERS.values().forEach(layer -> layer.close());
      HANDHELD_LAYERS.clear();
   }

   static void release(UUID deviceId) {
      if (deviceId != null) {
         MP4RgbaVideoLayer layer = LAYERS.remove(PlaybackSourceId.of(deviceId));
         if (layer != null) {
            layer.close();
         }
      }
   }

   static void releaseHandheld(UUID deviceId) {
      if (deviceId != null) {
         MP4RgbaVideoLayer layer = HANDHELD_LAYERS.remove(PlaybackSourceId.of(deviceId));
         if (layer != null) {
            layer.close();
         }
      }
   }

   boolean uploadLatest(UUID deviceId) {
      boolean sequence;
      try (HandheldVideoFrame frame = MP4HandheldVideoClient.acquireLatestFrame(deviceId)) {
         if (frame != null && frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            long sequencex = MP4HandheldVideoClient.frameSequence(deviceId);
            if (this.texture != null && sequencex == this.uploadedSequence && this.uploadedWidth == frame.width() && this.uploadedHeight == frame.height()) {
               return true;
            }

            this.ensureTexture(frame.width(), frame.height());
            NativeImage image = this.texture.getPixels();
            if (image == null) {
               return false;
            }

            uploadPixels(image, frame);
            this.texture.upload();
            this.uploadedSequence = sequencex;
            this.uploadedWidth = frame.width();
            this.uploadedHeight = frame.height();
            return true;
         }

         sequence = false;
      }

      return sequence;
   }

   ResourceLocation textureId() {
      return this.textureId;
   }

   private void ensureTexture(int width, int height) {
      if (this.texture == null || this.uploadedWidth != width || this.uploadedHeight != height) {
         if (this.texture != null) {
            Minecraft.getInstance().getTextureManager().release(this.textureId);
            this.texture = null;
         }

         this.texture = new DynamicTexture(width, height, false);
         Minecraft.getInstance().getTextureManager().register(this.textureId, this.texture);
         this.uploadedSequence = -1L;
         this.uploadedWidth = width;
         this.uploadedHeight = height;
      }
   }

   private static void uploadPixels(NativeImage image, HandheldVideoFrame frame) {
      byte[] data = frame.data();
      ByteBuffer buffer = frame.buffer();
      int width = frame.width();
      int height = frame.height();
      int i = 0;

      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int r;
            int g;
            int b;
            int a;
            if (buffer != null) {
               r = buffer.get(i) & 255;
               g = buffer.get(i + 1) & 255;
               b = buffer.get(i + 2) & 255;
               a = buffer.get(i + 3) & 255;
            } else {
               r = data[i] & 255;
               g = data[i + 1] & 255;
               b = data[i + 2] & 255;
               a = data[i + 3] & 255;
            }

            image.setPixelRGBA(x, y, ABGR32.color(a, r, g, b));
            i += 4;
         }
      }
   }

   @Override
   public void close() {
      if (this.texture != null) {
         Minecraft.getInstance().getTextureManager().release(this.textureId);
         this.texture = null;
      }

      this.uploadedSequence = -1L;
      this.uploadedWidth = 0;
      this.uploadedHeight = 0;
   }
}
