package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import org.slf4j.Logger;

public final class PadDocumentSavedData extends SavedData {
   private static final String NAME = "pad_documents";
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Codec<Map<UUID, PadDocument>> DOCUMENTS_CODEC = Codec.unboundedMap(Codec.STRING, PadDocumentSavedData.DocumentCodec.CODEC)
      .xmap(PadDocumentSavedData::decodeDocuments, PadDocumentSavedData::encodeDocuments);
   public static final Codec<PadDocumentSavedData> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(DOCUMENTS_CODEC.optionalFieldOf("documents", Map.of()).forGetter(data -> data.documents))
         .apply(instance, PadDocumentSavedData::new)
   );
   // 【反编译伪影修复】补回菱形推断（同 AudioPlaybackIndexSavedData 的说明）
   public static final Factory<PadDocumentSavedData> FACTORY = new Factory<>(PadDocumentSavedData::new, PadDocumentSavedData::load);
   private final Map<UUID, PadDocument> documents;

   public PadDocumentSavedData() {
      this(new HashMap<>());
   }

   private PadDocumentSavedData(Map<UUID, PadDocument> documents) {
      this.documents = new HashMap<>(documents);
   }

   public static PadDocumentSavedData get(ServerLevel level) {
      return (PadDocumentSavedData)level.getDataStorage().computeIfAbsent(FACTORY, "pad_documents");
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CODEC.encodeStart(NbtOps.INSTANCE, this).resultOrPartial(error -> LOGGER.error("Failed to save pad documents: {}", error)).ifPresent(encoded -> {
         if (encoded instanceof CompoundTag compound) {
            tag.merge(compound);
         }
      });
      return tag;
   }

   private static PadDocumentSavedData load(CompoundTag tag, Provider registries) {
      return CODEC.parse(NbtOps.INSTANCE, tag)
         .resultOrPartial(error -> LOGGER.warn("Failed to load pad documents: {}", error))
         .orElseGet(PadDocumentSavedData::new);
   }

   public Optional<PadDocument> document(UUID deviceId) {
      return deviceId == null ? Optional.empty() : Optional.ofNullable(this.documents.get(deviceId));
   }

   public void put(UUID deviceId, PadDocument document) {
      if (deviceId != null && document != null) {
         this.documents.put(deviceId, document.copyWithLocked(false));
         this.setDirty();
      }
   }

   private static Map<UUID, PadDocument> decodeDocuments(Map<String, PadDocumentSavedData.DocumentCodec> raw) {
      Map<UUID, PadDocument> decoded = new HashMap<>();
      raw.forEach((key, value) -> {
         try {
            decoded.put(UUID.fromString(key), value.toDocument().copyWithLocked(false));
         } catch (IllegalArgumentException var4) {
         }
      });
      return decoded;
   }

   private static Map<String, PadDocumentSavedData.DocumentCodec> encodeDocuments(Map<UUID, PadDocument> documents) {
      Map<String, PadDocumentSavedData.DocumentCodec> encoded = new HashMap<>();
      documents.forEach((key, value) -> encoded.put(key.toString(), PadDocumentSavedData.DocumentCodec.from(value)));
      return encoded;
   }

   private static UUID parseUuid(String value) {
      try {
         return value != null && !value.isBlank() ? UUID.fromString(value) : UUID.randomUUID();
      } catch (IllegalArgumentException var2) {
         return UUID.randomUUID();
      }
   }

   private static boolean boolValue(Boolean value) {
      return boolValue(value, false);
   }

   private static boolean boolValue(Boolean value, boolean fallback) {
      return value == null ? fallback : value;
   }

   private static int intValue(Integer value, int fallback) {
      return value == null ? fallback : value;
   }

   private static long longValue(Long value, long fallback) {
      return value == null ? fallback : value;
   }

   private static float floatValue(Float value, float fallback) {
      return value == null ? fallback : value;
   }

   private static double doubleValue(Double value, double fallback) {
      return value == null ? fallback : value;
   }

   private static String stringValue(String value, String fallback) {
      return value == null ? fallback : value;
   }

   private static ItemStack itemStackValue(ItemStack value) {
      return value == null ? ItemStack.EMPTY : value;
   }

   private record DocumentCodec(
      String title,
      String author,
      long updatedAtMillis,
      long sequence,
      PadDocumentSavedData.MapSettingsCodec mapSettings,
      List<PadDocumentSavedData.MediaEntryCodec> mediaEntries,
      List<PadDocumentSavedData.TriggerPointCodec> triggerPoints
   ) {
      private static final Codec<PadDocumentSavedData.DocumentCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.STRING.optionalFieldOf("title", "").forGetter(value -> value.title()),
               Codec.STRING.optionalFieldOf("author", "").forGetter(value -> value.author()),
               Codec.LONG.optionalFieldOf("updatedAtMillis", 0L).forGetter(value -> value.updatedAtMillis()),
               Codec.LONG.optionalFieldOf("sequence", 0L).forGetter(value -> value.sequence()),
               PadDocumentSavedData.MapSettingsCodec.CODEC
                  .optionalFieldOf("mapSettings", PadDocumentSavedData.MapSettingsCodec.from(PadMapSettings.DEFAULT))
                  .forGetter(value -> value.mapSettings()),
               PadDocumentSavedData.MediaEntryCodec.CODEC.listOf().optionalFieldOf("mediaEntries", List.of()).forGetter(value -> value.mediaEntries()),
               PadDocumentSavedData.TriggerPointCodec.CODEC.listOf().optionalFieldOf("triggerPoints", List.of()).forGetter(value -> value.triggerPoints())
            )
            .apply(
               instance,
               (title, author, updatedAtMillis, sequence, mapSettings, mediaEntries, triggerPoints) -> new PadDocumentSavedData.DocumentCodec(
                  PadDocumentSavedData.stringValue(title, ""),
                  PadDocumentSavedData.stringValue(author, ""),
                  PadDocumentSavedData.longValue(updatedAtMillis, 0L),
                  PadDocumentSavedData.longValue(sequence, 0L),
                  mapSettings == null ? PadDocumentSavedData.MapSettingsCodec.from(PadMapSettings.DEFAULT) : mapSettings,
                  mediaEntries == null ? List.of() : List.copyOf(mediaEntries),
                  triggerPoints == null ? List.of() : List.copyOf(triggerPoints)
               )
            )
      );

      private PadDocument toDocument() {
         return new PadDocument(
            this.title,
            this.author,
            false,
            this.updatedAtMillis,
            this.sequence,
            this.mapSettings.toSettings(),
            this.mediaEntries.stream().map(value -> value.toEntry()).toList(),
            this.triggerPoints.stream().map(value -> value.toPoint()).toList()
         );
      }

      private static PadDocumentSavedData.DocumentCodec from(PadDocument document) {
         PadDocument safe = document == null ? PadDocument.DEFAULT : document;
         return new PadDocumentSavedData.DocumentCodec(
            safe.title(),
            safe.author(),
            safe.updatedAtMillis(),
            safe.sequence(),
            PadDocumentSavedData.MapSettingsCodec.from(safe.mapSettings()),
            safe.mediaEntries().stream().map(PadDocumentSavedData.MediaEntryCodec::from).toList(),
            safe.triggerPoints().stream().map(PadDocumentSavedData.TriggerPointCodec::from).toList()
         );
      }
   }

   private record MapSettingsCodec(String dimension, int centerX, int centerZ, int radiusBlocks, float zoom, boolean autoFollowPlayer) {
      private static final Codec<PadDocumentSavedData.MapSettingsCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.STRING.optionalFieldOf("dimension", PadMapSettings.DEFAULT.dimension()).forGetter(value -> value.dimension()),
               Codec.INT.optionalFieldOf("centerX", 0).forGetter(value -> value.centerX()),
               Codec.INT.optionalFieldOf("centerZ", 0).forGetter(value -> value.centerZ()),
               Codec.INT.optionalFieldOf("radiusBlocks", 64).forGetter(value -> value.radiusBlocks()),
               Codec.FLOAT.optionalFieldOf("zoom", 1.0F).forGetter(value -> value.zoom()),
               Codec.BOOL.optionalFieldOf("autoFollowPlayer", true).forGetter(value -> value.autoFollowPlayer())
            )
            .apply(
               instance,
               (dimension, centerX, centerZ, radiusBlocks, zoom, autoFollowPlayer) -> new PadDocumentSavedData.MapSettingsCodec(
                  PadDocumentSavedData.stringValue(dimension, PadMapSettings.DEFAULT.dimension()),
                  PadDocumentSavedData.intValue(centerX, 0),
                  PadDocumentSavedData.intValue(centerZ, 0),
                  PadDocumentSavedData.intValue(radiusBlocks, 64),
                  PadDocumentSavedData.floatValue(zoom, 1.0F),
                  PadDocumentSavedData.boolValue(autoFollowPlayer, true)
               )
            )
      );

      private PadMapSettings toSettings() {
         return new PadMapSettings(this.dimension, this.centerX, this.centerZ, this.radiusBlocks, this.zoom, this.autoFollowPlayer);
      }

      private static PadDocumentSavedData.MapSettingsCodec from(PadMapSettings settings) {
         PadMapSettings safe = settings == null ? PadMapSettings.DEFAULT : settings;
         return new PadDocumentSavedData.MapSettingsCodec(
            safe.dimension(), safe.centerX(), safe.centerZ(), safe.radiusBlocks(), safe.zoom(), safe.autoFollowPlayer()
         );
      }
   }

   private record MediaEntryCodec(int mediaId, ItemStack disc) {
      private static final Codec<PadDocumentSavedData.MediaEntryCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.INT.optionalFieldOf("mediaId", 0).forGetter(value -> value.mediaId()),
               ItemStack.OPTIONAL_CODEC.optionalFieldOf("disc", ItemStack.EMPTY).forGetter(value -> value.disc())
            )
            .apply(
               instance,
               (mediaId, disc) -> new PadDocumentSavedData.MediaEntryCodec(PadDocumentSavedData.intValue(mediaId, 0), PadDocumentSavedData.itemStackValue(disc))
            )
      );

      private PadMediaEntry toEntry() {
         return new PadMediaEntry(this.mediaId, PadItem.isNetMusicDisc(this.disc) ? this.disc : ItemStack.EMPTY);
      }

      private static PadDocumentSavedData.MediaEntryCodec from(PadMediaEntry entry) {
         return new PadDocumentSavedData.MediaEntryCodec(entry.mediaId(), entry.disc());
      }
   }

   private record TriggerPointCodec(
      String pointId,
      String name,
      double x,
      double y,
      double z,
      int radiusBlocks,
      int mediaId,
      String triggerMode,
      boolean loop,
      int volumePerMille,
      boolean visible
   ) {
      private static final Codec<PadDocumentSavedData.TriggerPointCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.STRING.optionalFieldOf("pointId", "").forGetter(value -> value.pointId()),
               Codec.STRING.optionalFieldOf("name", "").forGetter(value -> value.name()),
               Codec.DOUBLE.optionalFieldOf("x", 0.0).forGetter(value -> value.x()),
               Codec.DOUBLE.optionalFieldOf("y", 0.0).forGetter(value -> value.y()),
               Codec.DOUBLE.optionalFieldOf("z", 0.0).forGetter(value -> value.z()),
               Codec.INT.optionalFieldOf("radiusBlocks", 8).forGetter(value -> value.radiusBlocks()),
               Codec.INT.optionalFieldOf("mediaId", 0).forGetter(value -> value.mediaId()),
               Codec.STRING.optionalFieldOf("triggerMode", PadTriggerMode.MANUAL.name()).forGetter(value -> value.triggerMode()),
               Codec.BOOL.optionalFieldOf("loop", false).forGetter(value -> value.loop()),
               Codec.INT.optionalFieldOf("volumePerMille", 1000).forGetter(value -> value.volumePerMille()),
               Codec.BOOL.optionalFieldOf("visible", true).forGetter(value -> value.visible())
            )
            .apply(
               instance,
               (pointId, name, x, y, z, radiusBlocks, mediaId, triggerMode, loop, volumePerMille, visible) -> new PadDocumentSavedData.TriggerPointCodec(
                  PadDocumentSavedData.stringValue(pointId, ""),
                  PadDocumentSavedData.stringValue(name, ""),
                  PadDocumentSavedData.doubleValue(x, 0.0),
                  PadDocumentSavedData.doubleValue(y, 0.0),
                  PadDocumentSavedData.doubleValue(z, 0.0),
                  PadDocumentSavedData.intValue(radiusBlocks, 8),
                  PadDocumentSavedData.intValue(mediaId, 0),
                  PadDocumentSavedData.stringValue(triggerMode, PadTriggerMode.MANUAL.name()),
                  PadDocumentSavedData.boolValue(loop),
                  PadDocumentSavedData.intValue(volumePerMille, 1000),
                  PadDocumentSavedData.boolValue(visible, true)
               )
            )
      );

      private PadTriggerPoint toPoint() {
         return new PadTriggerPoint(
            PadDocumentSavedData.parseUuid(this.pointId),
            this.name,
            this.x,
            this.y,
            this.z,
            this.radiusBlocks,
            this.mediaId,
            PadTriggerMode.byName(this.triggerMode),
            this.loop,
            this.volumePerMille,
            this.visible
         );
      }

      private static PadDocumentSavedData.TriggerPointCodec from(PadTriggerPoint point) {
         return new PadDocumentSavedData.TriggerPointCodec(
            point.pointId().toString(),
            point.name(),
            point.x(),
            point.y(),
            point.z(),
            point.radiusBlocks(),
            point.mediaId(),
            point.triggerMode().name(),
            point.loop(),
            point.volumePerMille(),
            point.visible()
         );
      }
   }
}
