package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedData.Factory;
import org.slf4j.Logger;

public final class MP4PlaybackSavedData extends SavedData {
   private static final String NAME = "mp4_playback";
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Codec<Map<UUID, MP4PlaybackSavedData.Entry>> ENTRIES_CODEC = Codec.unboundedMap(Codec.STRING, MP4PlaybackSavedData.Entry.CODEC)
      .xmap(MP4PlaybackSavedData::decodeEntries, MP4PlaybackSavedData::encodeEntries);
   private static final Codec<Map<UUID, MP4DeviceStateStore.DeviceEntry>> DEVICES_CODEC = Codec.unboundedMap(
         Codec.STRING, MP4PlaybackSavedData.DeviceEntryCodec.CODEC
      )
      .xmap(MP4PlaybackSavedData::decodeDevices, MP4PlaybackSavedData::encodeDevices);
   public static final Codec<MP4PlaybackSavedData> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
            ENTRIES_CODEC.optionalFieldOf("entries", Map.of()).forGetter(data -> data.entries),
            DEVICES_CODEC.optionalFieldOf("devices", Map.of()).forGetter(data -> data.devices)
         )
         .apply(instance, MP4PlaybackSavedData::new)
   );
   // 【反编译伪影修复】补回菱形推断（同 AudioPlaybackIndexSavedData 的说明）
   public static final Factory<MP4PlaybackSavedData> FACTORY = new Factory<>(MP4PlaybackSavedData::new, MP4PlaybackSavedData::load);
   private final Map<UUID, MP4PlaybackSavedData.Entry> entries;
   private final Map<UUID, MP4DeviceStateStore.DeviceEntry> devices;

   public MP4PlaybackSavedData() {
      this(new HashMap<>(), new HashMap<>());
   }

   private MP4PlaybackSavedData(Map<UUID, MP4PlaybackSavedData.Entry> entries, Map<UUID, MP4DeviceStateStore.DeviceEntry> devices) {
      this.entries = new HashMap<>(entries);
      this.devices = new HashMap<>(devices);
   }

   public static MP4PlaybackSavedData get(ServerLevel level) {
      return (MP4PlaybackSavedData)level.getDataStorage().computeIfAbsent(FACTORY, "mp4_playback");
   }

   public CompoundTag save(CompoundTag tag, Provider registries) {
      CODEC.encodeStart(NbtOps.INSTANCE, this).resultOrPartial(error -> LOGGER.error("Failed to save mp4 playback data: {}", error)).ifPresent(encoded -> {
         if (encoded instanceof CompoundTag compound) {
            tag.merge(compound);
         }
      });
      return tag;
   }

   private static MP4PlaybackSavedData load(CompoundTag tag, Provider registries) {
      return CODEC.parse(NbtOps.INSTANCE, tag)
         .resultOrPartial(error -> LOGGER.warn("Failed to load mp4 playback data: {}", error))
         .orElseGet(MP4PlaybackSavedData::new);
   }

   public Optional<MP4PlaybackSavedData.Entry> get(UUID deviceId) {
      return deviceId == null ? Optional.empty() : Optional.ofNullable(this.entries.get(deviceId));
   }

   public Optional<MP4DeviceStateStore.DeviceEntry> device(UUID deviceId) {
      return deviceId == null ? Optional.empty() : Optional.ofNullable(this.devices.get(deviceId));
   }

   public long elapsedMillis(UUID deviceId, int queueIndex, long fallbackMillis) {
      MP4PlaybackSavedData.Entry entry = this.entries.get(deviceId);
      return entry != null && entry.queueIndex() == queueIndex ? Math.max(0L, entry.elapsedMillis()) : fallbackMillis;
   }

   public void put(UUID deviceId, MP4PlaybackSavedData.Entry entry) {
      if (deviceId != null && entry != null) {
         this.entries.put(deviceId, entry.normalized());
         this.setDirty();
      }
   }

   public void putDevice(UUID deviceId, MP4DeviceStateStore.DeviceEntry entry) {
      if (deviceId != null && entry != null) {
         this.devices.put(deviceId, entry.normalized());
         this.setDirty();
      }
   }

   public void record(UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille, String sessionId, boolean playing) {
      this.put(
         deviceId, new MP4PlaybackSavedData.Entry(queueIndex, elapsedMillis, durationSeconds, volumePerMille, sessionId == null ? "" : sessionId, playing)
      );
   }

   public void clear(UUID deviceId) {
      if (deviceId != null && this.entries.remove(deviceId) != null) {
         this.setDirty();
      }

      if (deviceId != null && this.devices.remove(deviceId) != null) {
         this.setDirty();
      }
   }

   private static Map<UUID, MP4PlaybackSavedData.Entry> decodeEntries(Map<String, MP4PlaybackSavedData.Entry> raw) {
      Map<UUID, MP4PlaybackSavedData.Entry> decoded = new HashMap<>();
      raw.forEach((key, value) -> {
         try {
            decoded.put(UUID.fromString(key), value.normalized());
         } catch (IllegalArgumentException var4) {
         }
      });
      return decoded;
   }

   private static Map<String, MP4PlaybackSavedData.Entry> encodeEntries(Map<UUID, MP4PlaybackSavedData.Entry> entries) {
      Map<String, MP4PlaybackSavedData.Entry> encoded = new HashMap<>();
      entries.forEach((key, value) -> encoded.put(key.toString(), value.normalized()));
      return encoded;
   }

   private static Map<UUID, MP4DeviceStateStore.DeviceEntry> decodeDevices(Map<String, MP4PlaybackSavedData.DeviceEntryCodec> raw) {
      Map<UUID, MP4DeviceStateStore.DeviceEntry> decoded = new HashMap<>();
      raw.forEach((key, value) -> {
         try {
            decoded.put(UUID.fromString(key), value.toDeviceEntry());
         } catch (IllegalArgumentException var4) {
         }
      });
      return decoded;
   }

   private static Map<String, MP4PlaybackSavedData.DeviceEntryCodec> encodeDevices(Map<UUID, MP4DeviceStateStore.DeviceEntry> devices) {
      Map<String, MP4PlaybackSavedData.DeviceEntryCodec> encoded = new HashMap<>();
      devices.forEach((key, value) -> encoded.put(key.toString(), MP4PlaybackSavedData.DeviceEntryCodec.from(value)));
      return encoded;
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

   private record DeviceEntryCodec(MP4PlaybackSavedData.DeviceStateCodec state, long elapsedMillis, int durationSeconds, String sessionId, long updatedGameTime) {
      private static final Codec<MP4PlaybackSavedData.DeviceEntryCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               MP4PlaybackSavedData.DeviceStateCodec.CODEC
                  .optionalFieldOf("state", MP4PlaybackSavedData.DeviceStateCodec.from(MP4Item.State.DEFAULT))
                  .forGetter(entry -> entry.state()),
               Codec.LONG.optionalFieldOf("elapsedMillis", 0L).forGetter(entry -> entry.elapsedMillis()),
               Codec.INT.optionalFieldOf("durationSeconds", 0).forGetter(entry -> entry.durationSeconds()),
               Codec.STRING.optionalFieldOf("sessionId", "").forGetter(entry -> entry.sessionId()),
               Codec.LONG.optionalFieldOf("updatedGameTime", 0L).forGetter(entry -> entry.updatedGameTime())
            )
            .apply(
               instance,
               (state, elapsedMillis, durationSeconds, sessionId, updatedGameTime) -> new MP4PlaybackSavedData.DeviceEntryCodec(
                  state,
                  MP4PlaybackSavedData.longValue(elapsedMillis, 0L),
                  MP4PlaybackSavedData.intValue(durationSeconds, 0),
                  sessionId,
                  MP4PlaybackSavedData.longValue(updatedGameTime, 0L)
               )
            )
      );

      private MP4DeviceStateStore.DeviceEntry toDeviceEntry() {
         return new MP4DeviceStateStore.DeviceEntry(
            this.state.toState(), List.of(), this.elapsedMillis, this.durationSeconds, this.sessionId, this.updatedGameTime
         );
      }

      private static MP4PlaybackSavedData.DeviceEntryCodec from(MP4DeviceStateStore.DeviceEntry entry) {
         MP4DeviceStateStore.DeviceEntry safe = entry == null ? MP4DeviceStateStore.DeviceEntry.EMPTY : entry.normalized();
         return new MP4PlaybackSavedData.DeviceEntryCodec(
            MP4PlaybackSavedData.DeviceStateCodec.from(safe.state()), safe.elapsedMillis(), safe.durationSeconds(), safe.sessionId(), safe.updatedGameTime()
         );
      }
   }

   private record DeviceStateCodec(
      boolean playing,
      boolean shuffle,
      boolean videoEnabled,
      boolean landscape,
      int qualityIndex,
      int selectedQueueIndex,
      int queueScrollOffset,
      int volumePerMille,
      int repeatMode,
      boolean playlistOpen,
      boolean lyricsEnabled,
      int subtitleMode,
      boolean subtitleAiEnabled,
      int progressPerMille,
      boolean rotationHintShown
   ) {
      private static final Codec<MP4PlaybackSavedData.DeviceStateCodec> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.BOOL.optionalFieldOf("playing", false).forGetter(state -> state.playing()),
               Codec.BOOL.optionalFieldOf("shuffle", false).forGetter(state -> state.shuffle()),
               Codec.BOOL.optionalFieldOf("videoEnabled", true).forGetter(state -> state.videoEnabled()),
               Codec.BOOL.optionalFieldOf("landscape", false).forGetter(state -> state.landscape()),
               Codec.INT.optionalFieldOf("qualityIndex", 5).forGetter(state -> state.qualityIndex()),
               Codec.INT.optionalFieldOf("selectedQueueIndex", 0).forGetter(state -> state.selectedQueueIndex()),
               Codec.INT.optionalFieldOf("queueScrollOffset", 0).forGetter(state -> state.queueScrollOffset()),
               Codec.INT.optionalFieldOf("volumePerMille", 1000).forGetter(state -> state.volumePerMille()),
               Codec.INT.optionalFieldOf("repeatMode", 0).forGetter(state -> state.repeatMode()),
               Codec.BOOL.optionalFieldOf("playlistOpen", false).forGetter(state -> state.playlistOpen()),
               Codec.BOOL.optionalFieldOf("lyricsEnabled", false).forGetter(state -> state.lyricsEnabled()),
               Codec.INT.optionalFieldOf("subtitleMode", 0).forGetter(state -> state.subtitleMode()),
               Codec.BOOL.optionalFieldOf("subtitleAiEnabled", false).forGetter(state -> state.subtitleAiEnabled()),
               Codec.INT.optionalFieldOf("progressPerMille", 0).forGetter(state -> state.progressPerMille()),
               Codec.BOOL.optionalFieldOf("rotationHintShown", false).forGetter(state -> state.rotationHintShown())
            )
            .apply(
               instance,
               (playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex, queueScrollOffset, volumePerMille, repeatMode, playlistOpen, lyricsEnabled, subtitleMode, subtitleAiEnabled, progressPerMille, rotationHintShown) -> new MP4PlaybackSavedData.DeviceStateCodec(
                  MP4PlaybackSavedData.boolValue(playing),
                  MP4PlaybackSavedData.boolValue(shuffle),
                  MP4PlaybackSavedData.boolValue(videoEnabled, true),
                  MP4PlaybackSavedData.boolValue(landscape),
                  MP4PlaybackSavedData.intValue(qualityIndex, 5),
                  MP4PlaybackSavedData.intValue(selectedQueueIndex, 0),
                  MP4PlaybackSavedData.intValue(queueScrollOffset, 0),
                  MP4PlaybackSavedData.intValue(volumePerMille, 1000),
                  MP4PlaybackSavedData.intValue(repeatMode, 0),
                  MP4PlaybackSavedData.boolValue(playlistOpen),
                  MP4PlaybackSavedData.boolValue(lyricsEnabled),
                  MP4PlaybackSavedData.intValue(subtitleMode, 0),
                  MP4PlaybackSavedData.boolValue(subtitleAiEnabled),
                  MP4PlaybackSavedData.intValue(progressPerMille, 0),
                  MP4PlaybackSavedData.boolValue(rotationHintShown)
               )
            )
      );

      static MP4PlaybackSavedData.DeviceStateCodec from(MP4Item.State state) {
         MP4Item.State s = state == null ? MP4Item.State.DEFAULT : state;
         return new MP4PlaybackSavedData.DeviceStateCodec(
            s.playing(),
            s.shuffle(),
            s.videoEnabled(),
            s.landscape(),
            s.qualityIndex(),
            s.selectedQueueIndex(),
            s.queueScrollOffset(),
            s.volumePerMille(),
            s.repeatMode(),
            s.playlistOpen(),
            s.lyricsEnabled(),
            s.subtitleMode(),
            s.subtitleAiEnabled(),
            s.progressPerMille(),
            s.rotationHintShown()
         );
      }

      MP4Item.State toState() {
         return new MP4Item.State(
            this.playing,
            this.shuffle,
            this.videoEnabled,
            this.landscape,
            this.qualityIndex,
            this.selectedQueueIndex,
            this.queueScrollOffset,
            this.volumePerMille,
            this.repeatMode,
            this.playlistOpen,
            this.lyricsEnabled,
            this.subtitleMode,
            this.subtitleAiEnabled,
            this.progressPerMille,
            this.rotationHintShown
         );
      }
   }

   public record Entry(int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille, String sessionId, boolean playing) {
      public static final Codec<MP4PlaybackSavedData.Entry> CODEC = RecordCodecBuilder.create(
         instance -> instance.group(
               Codec.INT.optionalFieldOf("queueIndex", 0).forGetter(entry -> entry.queueIndex()),
               Codec.LONG.optionalFieldOf("elapsedMillis", 0L).forGetter(entry -> entry.elapsedMillis()),
               Codec.INT.optionalFieldOf("durationSeconds", 0).forGetter(entry -> entry.durationSeconds()),
               Codec.INT.optionalFieldOf("volumePerMille", 700).forGetter(entry -> entry.volumePerMille()),
               Codec.STRING.optionalFieldOf("sessionId", "").forGetter(entry -> entry.sessionId()),
               Codec.BOOL.optionalFieldOf("playing", false).forGetter(entry -> entry.playing())
            )
            .apply(
               instance,
               (queueIndex, elapsedMillis, durationSeconds, volumePerMille, sessionId, playing) -> new MP4PlaybackSavedData.Entry(
                  MP4PlaybackSavedData.intValue(queueIndex, 0),
                  MP4PlaybackSavedData.longValue(elapsedMillis, 0L),
                  MP4PlaybackSavedData.intValue(durationSeconds, 0),
                  MP4PlaybackSavedData.intValue(volumePerMille, 700),
                  sessionId,
                  MP4PlaybackSavedData.boolValue(playing)
               )
            )
      );

      MP4PlaybackSavedData.Entry normalized() {
         int duration = Math.max(0, this.durationSeconds);
         long max = duration > 0 ? Math.max(0L, duration * 1000L - 50L) : Long.MAX_VALUE;
         return new MP4PlaybackSavedData.Entry(
            Math.max(0, this.queueIndex),
            Math.max(0L, Math.min(max, this.elapsedMillis)),
            duration,
            Math.max(0, Math.min(1000, this.volumePerMille)),
            this.sessionId == null ? "" : this.sessionId,
            this.playing
         );
      }
   }
}
