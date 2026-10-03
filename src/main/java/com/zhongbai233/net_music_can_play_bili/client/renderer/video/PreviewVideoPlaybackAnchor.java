package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.client.sync.MediaTimelineClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class PreviewVideoPlaybackAnchor implements VideoPlaybackAnchor {
   private final UUID sourceId;
   private final PlaybackSessionId playbackSessionId;
   private final long startOffsetMillis;
   private final long totalMillis;

   PreviewVideoPlaybackAnchor(UUID sourceId, String sessionId, long startOffsetMillis, long totalMillis) {
      this.sourceId = sourceId;
      this.playbackSessionId = PlaybackSessionId.of(sessionId);
      this.startOffsetMillis = Math.max(0L, startOffsetMillis);
      this.totalMillis = Math.max(0L, totalMillis);
   }

   @Override
   public MediaVideoTimeline timeline() {
      ClientMediaTimelineView view = ClientMediaTimelineView.forMediaOwner(this.sourceId, this.sessionId(), this.startOffsetMillis, this.totalMillis);
      if (!this.sessionId().equals(view.sessionId()) || !view.hasTimeline()) {
         return MediaVideoTimeline.EMPTY;
      } else if (view.started()) {
         return new PreviewVideoPlaybackAnchor.SnapshotTimeline(view);
      } else {
         ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(this.sourceId);
         MediaTimelineClock.TimelineSnapshot visualSnapshot = active != null ? active.timelineSnapshot() : null;
         return (MediaVideoTimeline)(visualSnapshot != null
               && visualSnapshot.mediaMillis() >= 0L
               && visualSnapshot.playbackSessionId().filter(this.playbackSessionId::equals).isPresent()
            ? new PreviewVideoPlaybackAnchor.RegistrySnapshotTimeline(visualSnapshot)
            : new PreviewVideoPlaybackAnchor.FrozenTimeline());
      }
   }

   @Override
   public Vec3 position() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null ? minecraft.player.position().add(0.0, minecraft.player.getEyeHeight(), 0.0) : Vec3.ZERO;
   }

   @Override
   public boolean isForTurntable(BlockPos pos) {
      return false;
   }

   @Override
   public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
      return minecraft != null && minecraft.player != null;
   }

   @Override
   public Object replacementOwnerKey() {
      return new VideoPlaybackAnchor.PreviewOwnerKey(this.sourceId);
   }

   PlaybackSessionId playbackSessionId() {
      return this.playbackSessionId;
   }

   private String sessionId() {
      return this.playbackSessionId.value();
   }

   private final class FrozenTimeline implements MediaVideoTimeline {
      @Override
      public long mediaMillis() {
         return PreviewVideoPlaybackAnchor.this.startOffsetMillis;
      }

      @Override
      public long visualMillis() {
         return PreviewVideoPlaybackAnchor.this.startOffsetMillis;
      }

      @Override
      public long pacingMillis() {
         return PreviewVideoPlaybackAnchor.this.startOffsetMillis;
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         return Math.max(0L, PreviewVideoPlaybackAnchor.this.startOffsetMillis - Math.max(0L, absoluteStartMillis)) * 1000000L;
      }

      @Override
      public long totalMillis() {
         return PreviewVideoPlaybackAnchor.this.totalMillis;
      }

      @Override
      public Optional<PlaybackSessionId> playbackSessionId() {
         return Optional.of(PreviewVideoPlaybackAnchor.this.playbackSessionId);
      }
   }

   private final class RegistrySnapshotTimeline implements MediaVideoTimeline {
      private final MediaTimelineClock.TimelineSnapshot snapshot;

      private RegistrySnapshotTimeline(MediaTimelineClock.TimelineSnapshot snapshot) {
         this.snapshot = snapshot;
      }

      @Override
      public long mediaMillis() {
         return this.snapshot.mediaMillis();
      }

      @Override
      public long visualMillis() {
         return this.snapshot.visualMillis();
      }

      @Override
      public long pacingMillis() {
         return this.snapshot.pacingMillis();
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         return Math.max(0L, this.snapshot.mediaMillis() - Math.max(0L, absoluteStartMillis)) * 1000000L;
      }

      @Override
      public long totalMillis() {
         return this.snapshot.totalMillis() > 0L ? this.snapshot.totalMillis() : PreviewVideoPlaybackAnchor.this.totalMillis;
      }

      @Override
      public Optional<PlaybackSessionId> playbackSessionId() {
         return Optional.of(PreviewVideoPlaybackAnchor.this.playbackSessionId);
      }
   }

   private final class SnapshotTimeline implements MediaVideoTimeline {
      private final ClientMediaTimelineView view;

      private SnapshotTimeline(ClientMediaTimelineView view) {
         this.view = view;
      }

      @Override
      public long mediaMillis() {
         return this.view.mediaMillis();
      }

      @Override
      public long visualMillis() {
         return this.view.visualMillis();
      }

      @Override
      public long pacingMillis() {
         return this.view.pacingMillis();
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         return this.view.relativeNanos(absoluteStartMillis);
      }

      @Override
      public long totalMillis() {
         return this.view.totalMillis();
      }

      @Override
      public Optional<PlaybackSessionId> playbackSessionId() {
         return Optional.of(PreviewVideoPlaybackAnchor.this.playbackSessionId);
      }
   }
}
