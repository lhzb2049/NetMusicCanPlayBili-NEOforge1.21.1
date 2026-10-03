package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.VisualTimelineSmoother;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ModernTurntableTimeline {
   private static final TimelineProperties.Turntable PROPERTIES = TimelineProperties.turntable();
   private static final boolean AUDIO_ANCHORED_LOCAL_TIMELINE = PROPERTIES.audioAnchored();
   private static final long AUDIO_ANCHOR_MAX_LAG_MILLIS = PROPERTIES.audioAnchorMaxLagMillis();
   private static final long AUDIO_ANCHOR_MAX_LEAD_MILLIS = PROPERTIES.audioAnchorMaxLeadMillis();
   private static final long CLOCK_PRUNE_INTERVAL_NANOS = PROPERTIES.clockPruneIntervalNanos();
   private static final long VISUAL_HARD_SYNC_MILLIS = PROPERTIES.visualHardSyncMillis();
   private static final long VISUAL_MAX_CORRECTION_MILLIS = PROPERTIES.visualMaxCorrectionMillis();
   private static final double VISUAL_CORRECTION_RATIO = PROPERTIES.visualCorrectionRatio();
   private static final ConcurrentHashMap<BlockPos, MediaTimelineClock> CLOCKS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<BlockPos, ModernTurntableTimeline.VisualState> VISUAL_CLOCKS = new ConcurrentHashMap<>();
   private static volatile long lastClockPruneNanos;

   private ModernTurntableTimeline() {
   }

   public static long mediaMillis(BlockPos turntablePos) {
      ModernTurntableTimeline.TimelineSnapshot snapshot = snapshot(turntablePos);
      return snapshot.mediaMillis();
   }

   public static long visualMillis(BlockPos turntablePos) {
      ModernTurntableTimeline.TimelineSnapshot snapshot = snapshot(turntablePos);
      return snapshot.visualMillis();
   }

   public static long pacingMillis(BlockPos turntablePos) {
      ModernTurntableTimeline.TimelineSnapshot snapshot = snapshot(turntablePos);
      return snapshot.pacingMillis();
   }

   public static long serverMillis(BlockPos turntablePos) {
      ModernTurntableTimeline.TimelineSnapshot snapshot = snapshot(turntablePos);
      return snapshot.serverMillis();
   }

   public static ModernTurntableTimeline.TimelineSnapshot snapshot(BlockPos turntablePos) {
      pruneStaleClocksIfNeeded();
      ModernTurntableBlockEntity turntable = turntable(turntablePos);
      if (turntable != null && turntable.isPlaying()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.level != null) {
            PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
            long rawServerMillis = sync.hasSession() ? sync.elapsedMillis() : turntable.getPlaybackElapsedMillis();
            long totalMillis = sync.hasSession() ? sync.totalMillis() : Math.max(0L, (long)turntable.getDurationSeconds()) * 1000L;
            long serverMillis = clamp(rawServerMillis, totalMillis);
            long observationGameTime = MonotonicMediaClock.nowTick();
            Optional<PlaybackSessionId> playbackSessionId = sync.playbackSessionId();
            BlockPos key = turntablePos.immutable();
            MediaTimelineClock clock = CLOCKS.compute(key, (ignored, existing) -> {
               if (existing != null && existing.isForSession(playbackSessionId)) {
                  existing.observeServerOnce(observationGameTime, serverMillis, totalMillis);
                  return (MediaTimelineClock)existing;
               } else {
                  return MediaTimelineClock.start(playbackSessionId, serverMillis, totalMillis);
               }
            });
            long pacingMillis = clock != null ? clock.pacingMillis() : serverMillis;
            long mediaMillis = audioAnchoredMillis(key, pacingMillis, totalMillis);
            mediaMillis = clamp(mediaMillis, totalMillis);
            pacingMillis = clamp(pacingMillis, totalMillis);
            long nowNanos = System.nanoTime();
            ModernTurntableTimeline.VisualState visualState = VISUAL_CLOCKS.compute(
               key,
               (ignored, existing) -> (ModernTurntableTimeline.VisualState)(existing != null && existing.playbackSessionId().equals(playbackSessionId)
                  ? existing
                  : new ModernTurntableTimeline.VisualState(
                     playbackSessionId, new VisualTimelineSmoother(VISUAL_HARD_SYNC_MILLIS, VISUAL_MAX_CORRECTION_MILLIS, VISUAL_CORRECTION_RATIO)
                  ))
            );
            long visualMillis = visualState.smoother().sample(mediaMillis, totalMillis, nowNanos);
            return new ModernTurntableTimeline.TimelineSnapshot(
               playbackSessionId, mediaMillis, visualMillis, serverMillis, pacingMillis, totalMillis, mediaMillis - serverMillis
            );
         } else {
            return ModernTurntableTimeline.TimelineSnapshot.EMPTY;
         }
      } else {
         forget(turntablePos);
         return ModernTurntableTimeline.TimelineSnapshot.EMPTY;
      }
   }

   private static long audioAnchoredMillis(BlockPos turntablePos, long fallbackMillis, long totalMillis) {
      long fallback = clamp(fallbackMillis, totalMillis);
      if (AUDIO_ANCHORED_LOCAL_TIMELINE && turntablePos != null) {
         long audibleMillis = ClientAudioOutputRegistry.getAudioTimeline(turntablePos).audibleMillis();
         if (audibleMillis < 0L) {
            return fallback;
         } else {
            long clampedAudio = clamp(audibleMillis, totalMillis);
            long lag = fallback - clampedAudio;
            return lag <= Math.max(0L, AUDIO_ANCHOR_MAX_LAG_MILLIS) && -lag <= Math.max(0L, AUDIO_ANCHOR_MAX_LEAD_MILLIS) ? clampedAudio : fallback;
         }
      } else {
         return fallback;
      }
   }

   public static void forget(BlockPos turntablePos) {
      if (turntablePos != null) {
         CLOCKS.remove(turntablePos);
         VISUAL_CLOCKS.remove(turntablePos);
      }
   }

   public static void forgetSession(String sessionId) {
      PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (playbackSessionId != null) {
         Optional<PlaybackSessionId> key = Optional.of(playbackSessionId);
         CLOCKS.entrySet().removeIf(entry -> entry.getValue().isForSession(key));
         VISUAL_CLOCKS.entrySet().removeIf(entry -> entry.getValue().playbackSessionId().equals(key));
      }
   }

   public static void clear() {
      CLOCKS.clear();
      VISUAL_CLOCKS.clear();
      lastClockPruneNanos = 0L;
   }

   public static int mediaTick(BlockPos turntablePos) {
      long millis = mediaMillis(turntablePos);
      return millis < 0L ? -1 : (int)Math.min(2147483647L, Math.max(0L, Math.round(millis / 50.0)));
   }

   public static long relativeNanos(BlockPos turntablePos, long absoluteStartMillis) {
      long millis = mediaMillis(turntablePos);
      return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1000000L;
   }

   public static ModernTurntableBlockEntity turntable(BlockPos turntablePos) {
      if (turntablePos == null) {
         return null;
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.level != null) {
            return minecraft.level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable ? turntable : null;
         } else {
            return null;
         }
      }
   }

   private static long clamp(long millis, long totalMillis) {
      return MediaTimelineClock.clamp(millis, totalMillis);
   }

   private static void pruneStaleClocksIfNeeded() {
      long now = System.nanoTime();
      if (now - lastClockPruneNanos >= CLOCK_PRUNE_INTERVAL_NANOS) {
         lastClockPruneNanos = now;
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.level != null && !CLOCKS.isEmpty()) {
            CLOCKS.entrySet()
               .removeIf(
                  entry -> {
                     BlockPos pos = entry.getKey();
                     return !(
                        minecraft.level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable
                           && turntable.isPlaying()
                           && entry.getValue().isForSession(turntable.getPlaybackSyncMetadata().playbackSessionId())
                     );
                  }
               );
            VISUAL_CLOCKS.keySet().removeIf(pos -> !CLOCKS.containsKey(pos));
         } else {
            CLOCKS.clear();
            VISUAL_CLOCKS.clear();
         }
      }
   }

   public record TimelineSnapshot(
      Optional<PlaybackSessionId> playbackSessionId,
      long mediaMillis,
      long visualMillis,
      long serverMillis,
      long pacingMillis,
      long totalMillis,
      long mediaDriftMillis
   ) {
      public static final ModernTurntableTimeline.TimelineSnapshot EMPTY = new ModernTurntableTimeline.TimelineSnapshot(
         Optional.empty(), -1L, -1L, -1L, -1L, 0L, 0L
      );

      public TimelineSnapshot(
         Optional<PlaybackSessionId> playbackSessionId,
         long mediaMillis,
         long visualMillis,
         long serverMillis,
         long pacingMillis,
         long totalMillis,
         long mediaDriftMillis
      ) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.playbackSessionId = playbackSessionId;
         this.mediaMillis = mediaMillis;
         this.visualMillis = visualMillis;
         this.serverMillis = serverMillis;
         this.pacingMillis = pacingMillis;
         this.totalMillis = totalMillis;
         this.mediaDriftMillis = mediaDriftMillis;
      }

      public TimelineSnapshot(
         String sessionId, long mediaMillis, long visualMillis, long serverMillis, long pacingMillis, long totalMillis, long mediaDriftMillis
      ) {
         this(PlaybackSessionId.parse(sessionId), mediaMillis, visualMillis, serverMillis, pacingMillis, totalMillis, mediaDriftMillis);
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }
   }

   private record VisualState(Optional<PlaybackSessionId> playbackSessionId, VisualTimelineSmoother smoother) {
      private VisualState(Optional<PlaybackSessionId> playbackSessionId, VisualTimelineSmoother smoother) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.playbackSessionId = playbackSessionId;
         this.smoother = smoother;
      }
   }
}
