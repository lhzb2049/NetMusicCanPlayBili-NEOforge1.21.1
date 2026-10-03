package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.media.sync.VisualTimelineSmoother;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientMediaTimelineView {
   private static final TimelineProperties.Handheld PROPERTIES = TimelineProperties.handheld();
   private static final long AUDIO_ANCHOR_MAX_LAG_MILLIS = PROPERTIES.audioAnchorMaxLagMillis();
   private static final long AUDIO_ANCHOR_MAX_LEAD_MILLIS = PROPERTIES.audioAnchorMaxLeadMillis();
   private static final ConcurrentHashMap<PlaybackSourceId, ClientMediaTimelineView.VisualState> VISUAL_STATES = new ConcurrentHashMap<>();
   private final Optional<PlaybackSessionId> playbackSessionId;
   private final long mediaMillis;
   private final long visualMillis;
   private final long pacingMillis;
   private final long serverMillis;
   private final long totalMillis;
   private final long mediaDriftMillis;
   private final boolean started;
   private final boolean audibleAnchored;

   private ClientMediaTimelineView(
      Optional<PlaybackSessionId> playbackSessionId,
      long mediaMillis,
      long visualMillis,
      long pacingMillis,
      long serverMillis,
      long totalMillis,
      long mediaDriftMillis,
      boolean started,
      boolean audibleAnchored
   ) {
      this.playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
      this.mediaMillis = mediaMillis;
      this.visualMillis = visualMillis;
      this.pacingMillis = pacingMillis;
      this.serverMillis = serverMillis;
      this.totalMillis = Math.max(0L, totalMillis);
      this.mediaDriftMillis = mediaDriftMillis;
      this.started = started;
      this.audibleAnchored = audibleAnchored;
   }

   public static ClientMediaTimelineView empty() {
      return new ClientMediaTimelineView(Optional.empty(), -1L, -1L, -1L, -1L, 0L, 0L, false, false);
   }

   public static ClientMediaTimelineView forMediaOwner(UUID ownerId, String expectedSessionId, long fallbackMillis, long fallbackTotalMillis) {
      Optional<PlaybackSessionId> expected = PlaybackSessionId.parse(expectedSessionId);
      ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(ownerId);
      MediaTimelineClock.TimelineSnapshot snapshot = active != null ? active.timelineSnapshot() : MediaTimelineClock.TimelineSnapshot.EMPTY;
      return forHandheldOwner(
         ownerId,
         expected,
         snapshot,
         ClientMediaPlaybackRegistry.hasAudioStarted(ownerId, expected.<String>map(session -> session.value()).orElse("")),
         fallbackMillis,
         fallbackTotalMillis
      );
   }

   public static ClientMediaTimelineView forHandheldOwner(
      UUID ownerId, HandheldMediaPlayback playback, boolean started, long fallbackMillis, long fallbackTotalMillis
   ) {
      Optional<PlaybackSessionId> expected = playback != null ? playback.playbackSessionId() : Optional.empty();
      MediaTimelineClock.TimelineSnapshot snapshot = playback != null && playback.timeline() != null
         ? playback.timeline()
         : MediaTimelineClock.TimelineSnapshot.EMPTY;
      return forHandheldOwner(ownerId, expected, snapshot, started, fallbackMillis, fallbackTotalMillis);
   }

   public static ClientMediaTimelineView forHandheldOwner(UUID ownerId, HandheldMediaDeviceProfile profile, long fallbackMillis, long fallbackTotalMillis) {
      if (profile == null) {
         return empty();
      } else {
         HandheldMediaPlayback playback = profile.playback(ownerId);
         String sessionId = playback != null ? playback.playbackSessionId().map(session -> session.value()).orElse("") : "";
         return forHandheldOwner(ownerId, playback, profile.hasStartedSound(ownerId, sessionId), fallbackMillis, fallbackTotalMillis);
      }
   }

   private static ClientMediaTimelineView forHandheldOwner(
      UUID ownerId,
      Optional<PlaybackSessionId> expected,
      MediaTimelineClock.TimelineSnapshot snapshot,
      boolean started,
      long fallbackMillis,
      long fallbackTotalMillis
   ) {
      long fallback = Math.max(0L, fallbackMillis);
      long fallbackTotal = Math.max(0L, fallbackTotalMillis);
      Optional<PlaybackSessionId> normalizedExpected = expected != null ? expected : Optional.empty();
      PlaybackSessionId expectedSessionId = normalizedExpected.orElse(null);
      if (expectedSessionId != null && normalizedExpected.equals(snapshot.playbackSessionId()) && snapshot.mediaMillis() >= 0L) {
         long total = snapshot.totalMillis() > 0L ? snapshot.totalMillis() : fallbackTotal;
         long media = started ? snapshot.mediaMillis() : fallback;
         long visual = started ? snapshot.visualMillis() : fallback;
         long pacing = started ? snapshot.pacingMillis() : fallback;
         boolean anchored = false;
         if (started) {
            ClientAudioOutputRegistry.AudioTimeline audioTimeline = ClientAudioOutputRegistry.getOwnerAudioTimeline(ownerId);
            long audibleMillis = matchingAudibleMillis(audioTimeline, expectedSessionId, total);
            if (isSafeAudioAnchor(audibleMillis, pacing)) {
               media = audibleMillis;
               anchored = true;
            }

            visual = smoothVisual(ownerId, expectedSessionId, media, total);
         } else if (ownerId != null) {
            VISUAL_STATES.remove(PlaybackSourceId.of(ownerId));
         }

         media = clamp(media, total);
         visual = clamp(visual, total);
         pacing = clamp(pacing, total);
         return new ClientMediaTimelineView(
            normalizedExpected, media, visual, pacing, snapshot.serverMillis(), total, media - snapshot.serverMillis(), started, anchored
         );
      } else {
         return new ClientMediaTimelineView(normalizedExpected, fallback, fallback, fallback, -1L, fallbackTotal, 0L, false, false);
      }
   }

   public String sessionId() {
      return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
   }

   public Optional<PlaybackSessionId> playbackSessionId() {
      return this.playbackSessionId;
   }

   public long mediaMillis() {
      return this.mediaMillis;
   }

   public long visualMillis() {
      return this.visualMillis;
   }

   public long pacingMillis() {
      return this.pacingMillis;
   }

   public long serverMillis() {
      return this.serverMillis;
   }

   public long totalMillis() {
      return this.totalMillis;
   }

   public long mediaDriftMillis() {
      return this.mediaDriftMillis;
   }

   public boolean started() {
      return this.started;
   }

   public boolean audibleAnchored() {
      return this.audibleAnchored;
   }

   public boolean hasTimeline() {
      return this.mediaMillis >= 0L;
   }

   public float progressOr(float fallbackProgress) {
      return this.totalMillis > 0L && this.mediaMillis >= 0L ? clamp01((float)this.mediaMillis / (float)this.totalMillis) : clamp01(fallbackProgress);
   }

   public long relativeNanos(long absoluteStartMillis) {
      return this.mediaMillis < 0L ? -1L : Math.max(0L, this.mediaMillis - Math.max(0L, absoluteStartMillis)) * 1000000L;
   }

   private static long matchingAudibleMillis(ClientAudioOutputRegistry.AudioTimeline audioTimeline, PlaybackSessionId sessionId, long totalMillis) {
      if (audioTimeline == null) {
         return -1L;
      } else {
         long audibleMillis = audioTimeline.audibleMillis();
         if (audibleMillis < 0L) {
            return -1L;
         } else {
            String audioSessionId = audioTimeline.audioSessionId();
            return audioSessionId != null && !audioSessionId.isBlank() && PlaybackSessionId.parse(audioSessionId).filter(sessionId::equals).isEmpty()
               ? -1L
               : clamp(audibleMillis, totalMillis);
         }
      }
   }

   public static void forget(UUID ownerId) {
      if (ownerId != null) {
         VISUAL_STATES.remove(PlaybackSourceId.of(ownerId));
      }
   }

   public static void clearVisualStates() {
      VISUAL_STATES.clear();
   }

   private static boolean isSafeAudioAnchor(long audibleMillis, long pacingMillis) {
      if (audibleMillis >= 0L && pacingMillis >= 0L) {
         long lag = pacingMillis - audibleMillis;
         return lag <= Math.max(0L, AUDIO_ANCHOR_MAX_LAG_MILLIS) && -lag <= Math.max(0L, AUDIO_ANCHOR_MAX_LEAD_MILLIS);
      } else {
         return false;
      }
   }

   private static long smoothVisual(UUID ownerId, PlaybackSessionId sessionId, long mediaMillis, long totalMillis) {
      if (ownerId == null) {
         return clamp(mediaMillis, totalMillis);
      } else {
         ClientMediaTimelineView.VisualState state = VISUAL_STATES.compute(
            PlaybackSourceId.of(ownerId),
            (ignored, existing) -> (ClientMediaTimelineView.VisualState)(existing != null && existing.playbackSessionId().equals(sessionId)
               ? existing
               : new ClientMediaTimelineView.VisualState(sessionId, new VisualTimelineSmoother(500L, 20L, 0.2)))
         );
         return state.smoother().sample(mediaMillis, totalMillis, System.nanoTime());
      }
   }

   private static long clamp(long millis, long totalMillis) {
      long value = Math.max(0L, millis);
      long total = Math.max(0L, totalMillis);
      return total > 0L ? Math.min(total, value) : value;
   }

   private static float clamp01(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }

   private record VisualState(PlaybackSessionId playbackSessionId, VisualTimelineSmoother smoother) {
      private VisualState(PlaybackSessionId playbackSessionId, VisualTimelineSmoother smoother) {
         playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
         this.playbackSessionId = playbackSessionId;
         this.smoother = smoother;
      }
   }
}
