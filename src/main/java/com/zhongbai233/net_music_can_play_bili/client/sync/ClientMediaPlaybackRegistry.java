package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOwnerVolumes;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class ClientMediaPlaybackRegistry {
   private static final Map<PlaybackSourceId, ClientMediaPlaybackRegistry.ActivePlayback> ACTIVE = new ConcurrentHashMap<>();
   private static final Set<ClientMediaPlaybackRegistry.SourceSessionKey> STARTED_AUDIO_SESSIONS = ConcurrentHashMap.newKeySet();

   private ClientMediaPlaybackRegistry() {
   }

   public static ClientMediaPlaybackRegistry.ActivePlayback get(UUID sourceId) {
      return sourceId != null ? ACTIVE.get(PlaybackSourceId.of(sourceId)) : null;
   }

   public static void put(UUID sourceId, ClientMediaPlaybackRegistry.ActivePlayback playback) {
      if (sourceId != null && playback != null) {
         ACTIVE.put(PlaybackSourceId.of(sourceId), playback);
      }
   }

   public static boolean contains(UUID sourceId) {
      return sourceId != null && ACTIVE.containsKey(PlaybackSourceId.of(sourceId));
   }

   public static void computeIfPresent(
      UUID sourceId, BiFunction<UUID, ClientMediaPlaybackRegistry.ActivePlayback, ClientMediaPlaybackRegistry.ActivePlayback> remapper
   ) {
      if (sourceId != null && remapper != null) {
         ACTIVE.computeIfPresent(PlaybackSourceId.of(sourceId), (key, active) -> remapper.apply(key.value(), active));
      }
   }

   public static void remove(UUID sourceId) {
      if (sourceId != null) {
         ACTIVE.remove(PlaybackSourceId.of(sourceId));
         removeAudioStartedForSource(sourceId);
         ClientMediaTimelineView.forget(sourceId);
         ClientAudioOwnerVolumes.remove(sourceId);
      }
   }

   public static void finish(UUID sourceId, String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finish(sourceId, parsedSessionId));
   }

   public static void finish(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
         boolean[] removed = new boolean[1];
         ACTIVE.computeIfPresent(parsedSourceId, (ignored, active) -> {
            if (active.playbackSessionId().filter(sessionId::equals).isPresent()) {
               removed[0] = true;
               return null;
            } else {
               return (ClientMediaPlaybackRegistry.ActivePlayback)active;
            }
         });
         if (removed[0]) {
            ClientAudioOwnerVolumes.remove(sourceId);
         }

         STARTED_AUDIO_SESSIONS.remove(new ClientMediaPlaybackRegistry.SourceSessionKey(parsedSourceId, sessionId));
         ClientMediaTimelineView.forget(sourceId);
      }
   }

   public static void finishSession(UUID sourceId, String sessionId) {
      if (sourceId != null && sessionId != null && !sessionId.isBlank()) {
         PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finishSession(sourceId, parsedSessionId));
      }
   }

   public static void finishSession(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         finish(sourceId, sessionId);
         ClientMediaSoundRegistry.finishAndDiscard(sourceId, sessionId);
      }
   }

   public static void updateLyric(UUID sourceId, String sessionId, LyricRecord record, int lyricTick) {
      if (sourceId != null && record != null && isCurrent(sourceId, sessionId)) {
         String current = currentLineAt(record.getLyrics(), lyricTick);
         String translated = currentLineAt(record.getTransLyrics(), lyricTick);
         ACTIVE.computeIfPresent(PlaybackSourceId.of(sourceId), (ignored, active) -> active.withLyrics(record, current, translated));
      }
   }

   public static void clear() {
      ACTIVE.clear();
      STARTED_AUDIO_SESSIONS.clear();
      ClientMediaTimelineView.clearVisualStates();
   }

   public static boolean isCurrent(UUID sourceId, String sessionId) {
      return sourceId != null && sessionId != null && !sessionId.isBlank()
         ? PlaybackSessionId.parse(sessionId).map(parsedSessionId -> isCurrent(sourceId, parsedSessionId)).orElse(false)
         : true;
   }

   public static boolean isCurrent(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         ClientMediaPlaybackRegistry.ActivePlayback active = ACTIVE.get(PlaybackSourceId.of(sourceId));
         return active != null && active.playbackSessionId().filter(sessionId::equals).isPresent();
      } else {
         return false;
      }
   }

   public static boolean markAudioStarted(UUID sourceId, String sessionId, long startOffsetMillis, long totalMillis) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (sourceId != null && parsedSessionId != null) {
         PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
         ClientMediaPlaybackRegistry.ActivePlayback current = ACTIVE.get(parsedSourceId);
         if (current != null && current.playbackSessionId().filter(parsedSessionId::equals).isPresent()) {
            if (!STARTED_AUDIO_SESSIONS.add(new ClientMediaPlaybackRegistry.SourceSessionKey(parsedSourceId, parsedSessionId))) {
               return false;
            } else {
               ACTIVE.computeIfPresent(
                  parsedSourceId, (ignored, active) -> active.reanchoredAtSoundStart(Math.max(0L, startOffsetMillis), Math.max(0L, totalMillis))
               );
               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static boolean hasAudioStarted(UUID sourceId, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      return sourceId != null
         && parsedSessionId != null
         && STARTED_AUDIO_SESSIONS.contains(new ClientMediaPlaybackRegistry.SourceSessionKey(PlaybackSourceId.of(sourceId), parsedSessionId));
   }

   public static void removeAudioStartedForSource(UUID sourceId) {
      if (sourceId != null) {
         PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
         STARTED_AUDIO_SESSIONS.removeIf(key -> key.sourceId().equals(parsedSourceId));
      }
   }

   public static ClientMediaPlaybackRegistry.ActivePlayback createFromSync(ClientMediaSyncPayload payload) {
      Optional<PlaybackSessionId> playbackSessionId = payload.playbackSessionId();
      return new ClientMediaPlaybackRegistry.ActivePlayback(
         playbackSessionId,
         payload.queueIndex(),
         payload.songName(),
         payload.rawUrl(),
         MediaTimelineClock.start(playbackSessionId, Math.max(0L, payload.elapsedMillis()), Math.max(0L, (long)payload.durationSeconds()) * 1000L),
         null,
         "",
         "",
         payload.volumePerMille() / 1000.0F,
         ClientMediaPlaybackRegistry.SourceLocation.from(payload),
         payload.headphoneRouted()
      );
   }

   private static String currentLineAt(Int2ObjectSortedMap<String> lyrics, int tick) {
      if (lyrics != null && !lyrics.isEmpty()) {
         int key = lyrics.firstIntKey();

         for (int candidate : lyrics.keySet().toIntArray()) {
            if (candidate > tick) {
               break;
            }

            key = candidate;
         }

         String line = (String)lyrics.get(key);
         return line != null ? line : "";
      } else {
         return "";
      }
   }

   public record ActivePlayback(
      Optional<PlaybackSessionId> playbackSessionId,
      int queueIndex,
      String songName,
      String rawUrl,
      MediaTimelineClock timeline,
      LyricRecord lyricRecord,
      String currentLyric,
      String translatedLyric,
      float volume,
      ClientMediaPlaybackRegistry.SourceLocation sourceLocation,
      boolean headphoneRouted
   ) {
      public ActivePlayback(
         Optional<PlaybackSessionId> playbackSessionId,
         int queueIndex,
         String songName,
         String rawUrl,
         MediaTimelineClock timeline,
         LyricRecord lyricRecord,
         String currentLyric,
         String translatedLyric,
         float volume,
         ClientMediaPlaybackRegistry.SourceLocation sourceLocation,
         boolean headphoneRouted
      ) {
         Objects.requireNonNull(playbackSessionId, "playbackSessionId");
         this.playbackSessionId = playbackSessionId;
         this.queueIndex = queueIndex;
         this.songName = songName;
         this.rawUrl = rawUrl;
         this.timeline = timeline;
         this.lyricRecord = lyricRecord;
         this.currentLyric = currentLyric;
         this.translatedLyric = translatedLyric;
         this.volume = volume;
         this.sourceLocation = sourceLocation;
         this.headphoneRouted = headphoneRouted;
      }

      public ActivePlayback(
         String sessionId,
         int queueIndex,
         String songName,
         String rawUrl,
         MediaTimelineClock timeline,
         LyricRecord lyricRecord,
         String currentLyric,
         String translatedLyric,
         float volume,
         ClientMediaPlaybackRegistry.SourceLocation sourceLocation,
         boolean headphoneRouted
      ) {
         this(
            PlaybackSessionId.parse(sessionId),
            queueIndex,
            songName,
            rawUrl,
            timeline,
            lyricRecord,
            currentLyric,
            translatedLyric,
            volume,
            sourceLocation,
            headphoneRouted
         );
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }

      public long elapsedMillis() {
         return this.timeline.mediaMillis();
      }

      public long visualMillis() {
         return this.timeline.visualMillis();
      }

      public long pacingMillis() {
         return this.timeline.pacingMillis();
      }

      public long durationMillis() {
         return this.timeline.totalMillis();
      }

      public MediaTimelineClock.TimelineSnapshot timelineSnapshot() {
         return this.timeline.snapshot();
      }

      public ClientMediaPlaybackRegistry.ActivePlayback withLyrics(LyricRecord record, String current, String translated) {
         return new ClientMediaPlaybackRegistry.ActivePlayback(
            this.playbackSessionId,
            this.queueIndex,
            this.songName,
            this.rawUrl,
            this.timeline,
            record,
            current != null ? current : "",
            translated != null ? translated : "",
            this.volume,
            this.sourceLocation,
            this.headphoneRouted
         );
      }

      public String lyricLineAtCurrentTime(boolean translated) {
         if (this.lyricRecord == null) {
            return translated ? this.translatedLyric : this.currentLyric;
         } else {
            long mediaMillis = this.timeline.mediaMillis();
            if (mediaMillis < 0L) {
               return "";
            } else {
               int lyricTick = (int)Math.min(2147483647L, mediaMillis / 50L);
               return ClientMediaPlaybackRegistry.currentLineAt(translated ? this.lyricRecord.getTransLyrics() : this.lyricRecord.getLyrics(), lyricTick);
            }
         }
      }

      public ClientMediaPlaybackRegistry.ActivePlayback withVolume(float newVolume) {
         return new ClientMediaPlaybackRegistry.ActivePlayback(
            this.playbackSessionId,
            this.queueIndex,
            this.songName,
            this.rawUrl,
            this.timeline,
            this.lyricRecord,
            this.currentLyric,
            this.translatedLyric,
            newVolume,
            this.sourceLocation,
            this.headphoneRouted
         );
      }

      public ClientMediaPlaybackRegistry.ActivePlayback withServerElapsed(long serverElapsedMillis, long serverDurationMillis) {
         this.timeline.observeServer(serverElapsedMillis, serverDurationMillis > 0L ? serverDurationMillis : this.timeline.totalMillis());
         return this;
      }

      public ClientMediaPlaybackRegistry.ActivePlayback reanchoredAtSoundStart(long startOffsetMillis, long totalMillis) {
         this.timeline.reanchor(startOffsetMillis, totalMillis > 0L ? totalMillis : this.timeline.totalMillis());
         return this;
      }

      public ClientMediaPlaybackRegistry.ActivePlayback withSourceLocation(ClientMediaPlaybackRegistry.SourceLocation newSourceLocation) {
         return new ClientMediaPlaybackRegistry.ActivePlayback(
            this.playbackSessionId,
            this.queueIndex,
            this.songName,
            this.rawUrl,
            this.timeline,
            this.lyricRecord,
            this.currentLyric,
            this.translatedLyric,
            this.volume,
            newSourceLocation,
            this.headphoneRouted
         );
      }

      public ClientMediaPlaybackRegistry.ActivePlayback withHeadphoneRouted(boolean routed) {
         return new ClientMediaPlaybackRegistry.ActivePlayback(
            this.playbackSessionId,
            this.queueIndex,
            this.songName,
            this.rawUrl,
            this.timeline,
            this.lyricRecord,
            this.currentLyric,
            this.translatedLyric,
            this.volume,
            this.sourceLocation,
            routed
         );
      }
   }

   public record SourceLocation(int sourceType, int sourceEntityId, double x, double y, double z) {
      public static ClientMediaPlaybackRegistry.SourceLocation from(ClientMediaSyncPayload payload) {
         return new ClientMediaPlaybackRegistry.SourceLocation(
            payload.sourceType(), payload.sourceEntityId(), payload.sourceX(), payload.sourceY(), payload.sourceZ()
         );
      }

      public Vec3 position() {
         Minecraft minecraft = Minecraft.getInstance();
         if (this.sourceType == 0 && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(this.sourceEntityId);
            if (entity != null) {
               return entity.position().add(0.0, 1.2, 0.0);
            }
         }

         if (this.sourceType == 1 && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(this.sourceEntityId);
            if (entity != null) {
               return entity.position().add(0.0, 0.25, 0.0);
            }
         }

         if (this.sourceType == 3 && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(this.sourceEntityId);
            if (entity != null) {
               return entity.position().add(0.0, 0.5, 0.0);
            }
         }

         return new Vec3(this.x, this.y, this.z);
      }
   }

   private record SourceSessionKey(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
      private SourceSessionKey(PlaybackSourceId sourceId, PlaybackSessionId sessionId) {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(sessionId, "sessionId");
         this.sourceId = sourceId;
         this.sessionId = sessionId;
      }
   }
}
