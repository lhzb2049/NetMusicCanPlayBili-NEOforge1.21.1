package com.zhongbai233.net_music_can_play_bili.link;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.media.audio.IndexedAudioEndpoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import org.slf4j.Logger;

public final class AudioPlaybackIndexSavedData extends SavedData {
   private static final String NAME = "audio_playback_index";
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Codec<Map<Long, AudioPlaybackIndexSavedData.SourceEntry>> SOURCES_CODEC = Codec.unboundedMap(
         Codec.STRING, AudioPlaybackIndexSavedData.SourceEntry.CODEC
      )
      .xmap(AudioPlaybackIndexSavedData::decodeLongKeys, AudioPlaybackIndexSavedData::encodeLongKeys);
   private static final Codec<Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry>> ENDPOINTS_CODEC = Codec.unboundedMap(
         Codec.STRING, AudioPlaybackIndexSavedData.EndpointEntry.CODEC
      )
      .xmap(AudioPlaybackIndexSavedData::decodeUuidKeys, AudioPlaybackIndexSavedData::encodeUuidKeys);
   public static final Codec<AudioPlaybackIndexSavedData> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
            SOURCES_CODEC.optionalFieldOf("sources", Map.of()).forGetter(data -> data.sources),
            ENDPOINTS_CODEC.optionalFieldOf("endpoints", Map.of()).forGetter(data -> data.endpoints)
         )
         .apply(instance, AudioPlaybackIndexSavedData::new)
   );
   // 【反编译伪影修复】原字节码是 new Factory<>(…)（菱形推断）；反编译器写成了 raw 的 new Factory(…)，
   // 于是参数按擦除类型检查 → "Object cannot be converted to CompoundTag"。补回菱形即可，字节码不变。
   public static final Factory<AudioPlaybackIndexSavedData> FACTORY = new Factory<>(AudioPlaybackIndexSavedData::new, AudioPlaybackIndexSavedData::load);
   private final Map<Long, AudioPlaybackIndexSavedData.SourceEntry> sources;
   private final Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry> endpoints;

   public AudioPlaybackIndexSavedData() {
      this(new HashMap<>(), new HashMap<>());
   }

   private AudioPlaybackIndexSavedData(
      Map<Long, AudioPlaybackIndexSavedData.SourceEntry> sources, Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry> endpoints
   ) {
      this.sources = new HashMap<>(sources);
      this.endpoints = new HashMap<>(endpoints);
   }

   public static AudioPlaybackIndexSavedData get(ServerLevel level) {
      return (AudioPlaybackIndexSavedData)level.getDataStorage().computeIfAbsent(FACTORY, "audio_playback_index");
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CODEC.encodeStart(NbtOps.INSTANCE, this).resultOrPartial(error -> LOGGER.error("Failed to save audio playback index: {}", error)).ifPresent(encoded -> {
         if (encoded instanceof CompoundTag compound) {
            tag.merge(compound);
         }
      });
      return tag;
   }

   private static AudioPlaybackIndexSavedData load(CompoundTag tag, Provider registries) {
      return CODEC.parse(NbtOps.INSTANCE, tag)
         .resultOrPartial(error -> LOGGER.warn("Failed to load audio playback index: {}", error))
         .orElseGet(AudioPlaybackIndexSavedData::new);
   }

   public synchronized void upsertSource(BlockPos pos, PlaybackSourceId sourceId, AudioPlaybackIndexSavedData.SourceKind kind) {
      if (pos != null && sourceId != null && kind != null) {
         long packed = pos.asLong();
         AudioPlaybackIndexSavedData.SourceEntry next = new AudioPlaybackIndexSavedData.SourceEntry(sourceId.value().toString(), packed, kind.name());
         AudioPlaybackIndexSavedData.SourceEntry previous = this.sources.put(packed, next);
         boolean changed = !next.equals(previous);

         for (Entry<UUID, AudioPlaybackIndexSavedData.EndpointEntry> entry : this.endpoints.entrySet()) {
            AudioPlaybackIndexSavedData.EndpointEntry endpoint = entry.getValue();
            if (endpoint.sourcePos() == packed && !sourceId.value().toString().equals(endpoint.sourceId())) {
               entry.setValue(endpoint.withSourceId(sourceId.value().toString()));
               changed = true;
            }
         }

         if (changed) {
            this.setDirty();
         }
      }
   }

   public synchronized Optional<AudioPlaybackIndexSavedData.SourceEntry> sourceAt(BlockPos pos) {
      return pos != null ? Optional.ofNullable(this.sources.get(pos.asLong())) : Optional.empty();
   }

   public synchronized void removeSource(BlockPos pos) {
      if (pos != null) {
         long packed = pos.asLong();
         AudioPlaybackIndexSavedData.SourceEntry removed = this.sources.remove(packed);
         boolean endpointsRemoved = this.endpoints.entrySet().removeIf(entry -> entry.getValue().sourcePos() == packed);
         if (removed != null || endpointsRemoved) {
            this.setDirty();
         }
      }
   }

   public synchronized void upsertEndpoint(AudioPlaybackIndexSavedData.EndpointEntry endpoint) {
      if (endpoint != null) {
         AudioPlaybackIndexSavedData.EndpointEntry current = this.endpoints.get(endpoint.endpointId());
         if (current == null || current.revision() <= endpoint.revision()) {
            String sourceId = this.sourceAt(BlockPos.of(endpoint.sourcePos())).map(source -> source.sourceId()).orElse(endpoint.sourceId());
            AudioPlaybackIndexSavedData.EndpointEntry normalized = endpoint.withSourceId(sourceId);
            if (!normalized.equals(current)) {
               this.endpoints.put(normalized.endpointId(), normalized);
               this.setDirty();
            }
         }
      }
   }

   public synchronized void removeEndpoint(UUID endpointId) {
      if (endpointId != null && this.endpoints.remove(endpointId) != null) {
         this.setDirty();
      }
   }

   public synchronized void removeEndpointAt(BlockPos endpointPos) {
      if (endpointPos != null && this.endpoints.entrySet().removeIf(entry -> entry.getValue().endpointPos() == endpointPos.asLong())) {
         this.setDirty();
      }
   }

   public synchronized List<AudioPlaybackIndexSavedData.EndpointEntry> endpointsFor(PlaybackSourceId sourceId) {
      if (sourceId == null) {
         return List.of();
      } else {
         String id = sourceId.value().toString();
         return this.endpoints.values().stream().filter(endpoint -> id.equals(endpoint.sourceId())).toList();
      }
   }

   public synchronized List<AudioPlaybackIndexSavedData.EndpointEntry> endpointSnapshot() {
      return List.copyOf(this.endpoints.values());
   }

   private static Map<Long, AudioPlaybackIndexSavedData.SourceEntry> decodeLongKeys(Map<String, AudioPlaybackIndexSavedData.SourceEntry> raw) {
      Map<Long, AudioPlaybackIndexSavedData.SourceEntry> result = new HashMap<>();
      raw.forEach((key, value) -> {
         try {
            result.put(Long.parseLong(key), value);
         } catch (NumberFormatException var4) {
         }
      });
      return result;
   }

   private static Map<String, AudioPlaybackIndexSavedData.SourceEntry> encodeLongKeys(Map<Long, AudioPlaybackIndexSavedData.SourceEntry> raw) {
      Map<String, AudioPlaybackIndexSavedData.SourceEntry> result = new HashMap<>();
      raw.forEach((key, value) -> result.put(Long.toString(key), value));
      return result;
   }

   private static Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry> decodeUuidKeys(Map<String, AudioPlaybackIndexSavedData.EndpointEntry> raw) {
      Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry> result = new HashMap<>();
      raw.forEach((key, value) -> {
         try {
            result.put(UUID.fromString(key), value);
         } catch (IllegalArgumentException var4) {
         }
      });
      return result;
   }

   private static Map<String, AudioPlaybackIndexSavedData.EndpointEntry> encodeUuidKeys(Map<UUID, AudioPlaybackIndexSavedData.EndpointEntry> raw) {
      Map<String, AudioPlaybackIndexSavedData.EndpointEntry> result = new HashMap<>();
      raw.forEach((key, value) -> result.put(key.toString(), value));
      return result;
   }

   public record EndpointEntry(
      UUID endpointId, String sourceId, long sourcePos, long endpointPos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance, long revision
   ) {
      public static final Codec<AudioPlaybackIndexSavedData.EndpointEntry> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.STRING.fieldOf("endpointId").forGetter(entry -> entry.endpointId().toString()),
               Codec.STRING.optionalFieldOf("sourceId", "").forGetter(entry -> entry.sourceId()),
               Codec.LONG.fieldOf("sourcePos").forGetter(entry -> entry.sourcePos()),
               Codec.LONG.fieldOf("endpointPos").forGetter(entry -> entry.endpointPos()),
               Codec.INT.optionalFieldOf("channelIndex", -1).forGetter(entry -> entry.channelIndex()),
               Codec.FLOAT.optionalFieldOf("volume", 1.0F).forGetter(entry -> entry.volume()),
               Codec.BOOL.optionalFieldOf("autoMixJoc", false).forGetter(entry -> entry.autoMixJoc()),
               Codec.FLOAT.optionalFieldOf("maxDistance", 64.0F).forGetter(entry -> entry.maxDistance()),
               Codec.LONG.optionalFieldOf("revision", 0L).forGetter(entry -> entry.revision())
            )
            .apply(
               instance,
               (endpointId, sourceId, sourcePos, endpointPos, channelIndex, volume, autoMixJoc, maxDistance, revision) -> new AudioPlaybackIndexSavedData.EndpointEntry(
                  UUID.fromString(endpointId), sourceId, sourcePos, endpointPos, channelIndex, volume, autoMixJoc, maxDistance, revision
               )
            )
      );

      public EndpointEntry(
         UUID endpointId,
         String sourceId,
         long sourcePos,
         long endpointPos,
         int channelIndex,
         float volume,
         boolean autoMixJoc,
         float maxDistance,
         long revision
      ) {
         if (endpointId != null && sourceId != null && revision >= 0L) {
            volume = Math.clamp(volume, 0.0F, 2.0F);
            maxDistance = Math.clamp(maxDistance, 1.0F, 256.0F);
            this.endpointId = endpointId;
            this.sourceId = sourceId;
            this.sourcePos = sourcePos;
            this.endpointPos = endpointPos;
            this.channelIndex = channelIndex;
            this.volume = volume;
            this.autoMixJoc = autoMixJoc;
            this.maxDistance = maxDistance;
            this.revision = revision;
         } else {
            throw new IllegalArgumentException("invalid persistent audio endpoint");
         }
      }

      AudioPlaybackIndexSavedData.EndpointEntry withSourceId(String value) {
         return new AudioPlaybackIndexSavedData.EndpointEntry(
            this.endpointId,
            value != null ? value : "",
            this.sourcePos,
            this.endpointPos,
            this.channelIndex,
            this.volume,
            this.autoMixJoc,
            this.maxDistance,
            this.revision
         );
      }

      public Optional<IndexedAudioEndpoint> toIndexed(String dimension) {
         PlaybackSourceId parsed = PlaybackSourceId.parse(this.sourceId).orElse(null);
         if (parsed == null) {
            return Optional.empty();
         } else {
            BlockPos pos = BlockPos.of(this.endpointPos);
            return Optional.of(
               new IndexedAudioEndpoint(
                  this.endpointId,
                  parsed,
                  dimension,
                  pos.getX() + 0.5,
                  pos.getY() + 0.5,
                  pos.getZ() + 0.5,
                  this.maxDistance,
                  this.volume,
                  this.volume,
                  IndexedAudioEndpoint.Kind.SPEAKER,
                  this.revision
               )
            );
         }
      }
   }

   public record SourceEntry(String sourceId, long pos, String kind) {
      public static final Codec<AudioPlaybackIndexSavedData.SourceEntry> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.STRING.fieldOf("sourceId").forGetter(entry -> entry.sourceId()),
               Codec.LONG.fieldOf("pos").forGetter(entry -> entry.pos()),
               Codec.STRING.fieldOf("kind").forGetter(entry -> entry.kind())
            )
            .apply(
               instance,
               (sourceId, pos, kind) -> new AudioPlaybackIndexSavedData.SourceEntry(
                  Objects.requireNonNull(sourceId, "sourceId"), Objects.requireNonNull(pos, "pos"), Objects.requireNonNull(kind, "kind")
               )
            )
      );

      public PlaybackSourceId playbackSourceId() {
         return PlaybackSourceId.parse(this.sourceId).orElseThrow();
      }
   }

   public static enum SourceKind {
      TURNTABLE,
      LIVE_STREAMER;
   }
}
