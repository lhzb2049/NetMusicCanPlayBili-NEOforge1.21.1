package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.Nv12TextureSet;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoYuvTextureSet;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

final class MP4Nv12VideoLayer implements AutoCloseable {
   private static final Map<PlaybackSourceId, MP4Nv12VideoLayer> LAYERS = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, MP4Nv12VideoLayer> HANDHELD_LAYERS = new ConcurrentHashMap<>();
   private final ResourceLocation yTextureId;
   private final ResourceLocation uvTextureId;
   private Nv12TextureSet textureSet;
   private long uploadedSequence = -1L;
   private int uploadedWidth;
   private int uploadedHeight;

   private MP4Nv12VideoLayer(UUID deviceId) {
      this(deviceId, "mp4_video");
   }

   private MP4Nv12VideoLayer(UUID deviceId, String texturePrefix) {
      String suffix = deviceId.toString().replace('-', '_');
      this.yTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/" + texturePrefix + "_" + suffix + "_y");
      this.uvTextureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "dynamic/" + texturePrefix + "_" + suffix + "_uv");
   }

   static MP4Nv12VideoLayer forDevice(UUID deviceId) {
      if (deviceId == null) {
         throw new IllegalArgumentException("MP4 video layer requires a device id");
      } else {
         return LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId), sourceId -> new MP4Nv12VideoLayer(sourceId.value()));
      }
   }

   static MP4Nv12VideoLayer forHandheldDevice(UUID deviceId) {
      if (deviceId == null) {
         throw new IllegalArgumentException("MP4 handheld video layer requires a device id");
      } else {
         return HANDHELD_LAYERS.computeIfAbsent(PlaybackSourceId.of(deviceId), sourceId -> new MP4Nv12VideoLayer(sourceId.value(), "pad_video"));
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
         MP4Nv12VideoLayer layer = LAYERS.remove(PlaybackSourceId.of(deviceId));
         if (layer != null) {
            layer.close();
         }
      }
   }

   static void releaseHandheld(UUID deviceId) {
      if (deviceId != null) {
         MP4Nv12VideoLayer layer = HANDHELD_LAYERS.remove(PlaybackSourceId.of(deviceId));
         if (layer != null) {
            layer.close();
         }
      }
   }

   boolean uploadLatest(UUID deviceId) {
      boolean sequence;
      try (HandheldVideoFrame frame = MP4HandheldVideoClient.acquireLatestFrame(deviceId)) {
         if (frame != null && frame.format() == Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            long sequencex = MP4HandheldVideoClient.frameSequence(deviceId);
            if (this.textureSet != null && sequencex == this.uploadedSequence && this.uploadedWidth == frame.width() && this.uploadedHeight == frame.height()) {
               return true;
            }

            this.ensureTextureSet();
            boolean uploaded = frame.buffer() != null
               ? this.textureSet.upload(frame.buffer(), frame.byteLength(), frame.width(), frame.height())
               : this.textureSet.upload(frame.data(), frame.width(), frame.height());
            if (!uploaded) {
               return false;
            }

            this.uploadedSequence = sequencex;
            this.uploadedWidth = frame.width();
            this.uploadedHeight = frame.height();
            return true;
         }

         sequence = false;
      }

      return sequence;
   }

   VideoYuvTextureSet textureSet() {
      return this.textureSet;
   }

   private void ensureTextureSet() {
      if (this.textureSet == null) {
         this.textureSet = new Nv12TextureSet(this.yTextureId, this.uvTextureId, this.yTextureId, "mp4_video_" + this.yTextureId.getPath().replace('/', '_'));
      }
   }

   @Override
   public void close() {
      if (this.textureSet != null) {
         this.textureSet.close();
         this.textureSet = null;
      }

      this.uploadedSequence = -1L;
      this.uploadedWidth = 0;
      this.uploadedHeight = 0;
   }
}
