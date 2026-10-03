package com.zhongbai233.net_music_can_play_bili.link;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class MediaBindingData {
   public static final String TAG_SOURCE_KIND = "source_kind";
   public static final String TAG_MP4_DEVICE_ID = "mp4_device_id";
   public static final String TAG_MEDIA_DEVICE_ID = "media_device_id";
   public static final String TAG_DIMENSION = "dimension";
   public static final String TAG_POS_X = "x";
   public static final String TAG_POS_Y = "y";
   public static final String TAG_POS_Z = "z";

   private MediaBindingData() {
   }

   public static MediaBindingData.MediaSource mp4(UUID deviceId) {
      return MediaBindingData.MediaSource.mp4(deviceId);
   }

   public static MediaBindingData.MediaSource pad(UUID deviceId) {
      return MediaBindingData.MediaSource.pad(deviceId);
   }

   public static MediaBindingData.MediaSource projector(Level level, BlockPos pos) {
      return level != null && pos != null ? MediaBindingData.MediaSource.projector(level.dimension(), pos) : null;
   }

   public static MediaBindingData.MediaSource turntable(Level level, BlockPos pos) {
      return level != null && pos != null ? MediaBindingData.MediaSource.turntable(level.dimension(), pos) : null;
   }

   public static void writeSource(CompoundTag tag, MediaBindingData.MediaSource source) {
      if (tag != null && source != null) {
         tag.putString("source_kind", source.kind().serializedName());
         if (source.isMediaDevice() && source.deviceId() != null) {
            tag.putString("mp4_device_id", source.deviceId().toString());
            tag.putString("media_device_id", source.deviceId().toString());
         } else if (source.isBlockSource() && source.dimension() != null && source.pos() != null) {
            tag.putString("dimension", source.dimension().toString());
            tag.putInt("x", source.pos().getX());
            tag.putInt("y", source.pos().getY());
            tag.putInt("z", source.pos().getZ());
         }
      }
   }

   public static MediaBindingData.MediaSource readSource(CompoundTag tag) {
      if (tag == null) {
         return null;
      } else {
         MediaBindingData.SourceKind kind = MediaBindingData.SourceKind.byName(tag.getString("source_kind"));
         if (kind == MediaBindingData.SourceKind.MP4 || kind == MediaBindingData.SourceKind.PAD) {
            UUID deviceId = parseUuid(tag.getString("media_device_id"));
            if (deviceId == null) {
               deviceId = parseUuid(tag.getString("mp4_device_id"));
            }

            if (deviceId == null) {
               return null;
            } else {
               return kind == MediaBindingData.SourceKind.PAD ? MediaBindingData.MediaSource.pad(deviceId) : MediaBindingData.MediaSource.mp4(deviceId);
            }
         } else if (kind != MediaBindingData.SourceKind.VIDEO_PROJECTOR && kind != MediaBindingData.SourceKind.TURNTABLE) {
            return null;
         } else {
            ResourceKey<Level> dimension = parseDimension(tag.getString("dimension"));
            if (dimension == null) {
               return null;
            } else {
               BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
               return kind == MediaBindingData.SourceKind.VIDEO_PROJECTOR
                  ? MediaBindingData.MediaSource.projector(dimension, pos)
                  : MediaBindingData.MediaSource.turntable(dimension, pos);
            }
         }
      }
   }

   public static int indexOf(List<MediaBindingData.MediaSource> sources, MediaBindingData.MediaSource source) {
      if (sources != null && source != null) {
         for (int i = 0; i < sources.size(); i++) {
            if (source.equals(sources.get(i))) {
               return i;
            }
         }

         return -1;
      } else {
         return -1;
      }
   }

   public static boolean contains(List<MediaBindingData.MediaSource> sources, MediaBindingData.MediaSource source) {
      return indexOf(sources, source) >= 0;
   }

   public static List<UUID> mp4DeviceIds(List<MediaBindingData.MediaSource> sources) {
      return mediaDeviceIds(sources);
   }

   public static List<UUID> mediaDeviceIds(List<MediaBindingData.MediaSource> sources) {
      List<UUID> result = new ArrayList<>();
      if (sources != null) {
         for (MediaBindingData.MediaSource source : sources) {
            if (source != null && source.isMediaDevice() && source.deviceId() != null) {
               result.add(source.deviceId());
            }
         }
      }

      return List.copyOf(result);
   }

   public static UUID parseUuid(String value) {
      if (value != null && !value.isBlank()) {
         try {
            return UUID.fromString(value);
         } catch (IllegalArgumentException var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static ResourceKey<Level> parseDimension(String value) {
      if (value != null && !value.isBlank()) {
         int separator = value.lastIndexOf(" / ");
         if (separator >= 0) {
            value = value.substring(separator + 3, value.endsWith("]") ? value.length() - 1 : value.length());
         }

         ResourceLocation id = ResourceLocation.tryParse(value);
         return id != null ? ResourceKey.create(Registries.DIMENSION, id) : null;
      } else {
         return null;
      }
   }

   public record MediaSource(MediaBindingData.SourceKind kind, UUID deviceId, ResourceKey<Level> dimension, BlockPos pos) {
      public static MediaBindingData.MediaSource mp4(UUID deviceId) {
         return deviceId != null ? new MediaBindingData.MediaSource(MediaBindingData.SourceKind.MP4, deviceId, null, null) : null;
      }

      public static MediaBindingData.MediaSource pad(UUID deviceId) {
         return deviceId != null ? new MediaBindingData.MediaSource(MediaBindingData.SourceKind.PAD, deviceId, null, null) : null;
      }

      public static MediaBindingData.MediaSource projector(ResourceKey<Level> dimension, BlockPos pos) {
         return dimension != null && pos != null
            ? new MediaBindingData.MediaSource(MediaBindingData.SourceKind.VIDEO_PROJECTOR, null, dimension, pos.immutable())
            : null;
      }

      public static MediaBindingData.MediaSource turntable(ResourceKey<Level> dimension, BlockPos pos) {
         return dimension != null && pos != null
            ? new MediaBindingData.MediaSource(MediaBindingData.SourceKind.TURNTABLE, null, dimension, pos.immutable())
            : null;
      }

      public boolean isMp4() {
         return this.kind == MediaBindingData.SourceKind.MP4;
      }

      public boolean isPad() {
         return this.kind == MediaBindingData.SourceKind.PAD;
      }

      public boolean isMediaDevice() {
         return this.isMp4() || this.isPad();
      }

      public UUID mp4DeviceId() {
         return this.isMp4() ? this.deviceId : null;
      }

      public boolean isProjector() {
         return this.kind == MediaBindingData.SourceKind.VIDEO_PROJECTOR;
      }

      public boolean isTurntable() {
         return this.kind == MediaBindingData.SourceKind.TURNTABLE;
      }

      public boolean isBlockSource() {
         return this.isProjector() || this.isTurntable();
      }

      public String shortName() {
         if (this.isMp4() && this.deviceId != null) {
            String value = this.deviceId.toString();
            return "MP4 " + (value.length() > 8 ? value.substring(0, 8) : value);
         } else if (this.isPad() && this.deviceId != null) {
            String value = this.deviceId.toString();
            return "Pad " + (value.length() > 8 ? value.substring(0, 8) : value);
         } else if (this.isProjector() && this.pos != null) {
            return "投影仪 " + this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ();
         } else {
            return this.isTurntable() && this.pos != null ? "唱片机 " + this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ() : "媒体源";
         }
      }
   }

   public static enum SourceKind {
      MP4("mp4"),
      PAD("pad"),
      VIDEO_PROJECTOR("video_projector"),
      TURNTABLE("turntable");

      private final String serializedName;

      private SourceKind(String serializedName) {
         this.serializedName = serializedName;
      }

      public String serializedName() {
         return this.serializedName;
      }

      static MediaBindingData.SourceKind byName(String value) {
         for (MediaBindingData.SourceKind kind : values()) {
            if (kind.serializedName.equals(value)) {
               return kind;
            }
         }

         return null;
      }
   }
}
