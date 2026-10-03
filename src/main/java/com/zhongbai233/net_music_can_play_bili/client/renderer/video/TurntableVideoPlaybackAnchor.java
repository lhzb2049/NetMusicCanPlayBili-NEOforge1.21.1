package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.client.sync.PlaybackClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class TurntableVideoPlaybackAnchor implements VideoPlaybackAnchor {
   private final BlockPos turntablePos;
   private final Optional<PlaybackSessionId> playbackSessionId;
   private final MediaVideoTimeline timeline;

   TurntableVideoPlaybackAnchor(BlockPos turntablePos, String sessionId, long totalMillis) {
      this.turntablePos = turntablePos != null ? turntablePos.immutable() : null;
      this.playbackSessionId = PlaybackSessionId.parse(sessionId);
      this.timeline = (MediaVideoTimeline)(this.turntablePos != null
         ? new TurntableVideoPlaybackAnchor.TurntableMediaVideoTimeline(this.turntablePos, this.playbackSessionId, totalMillis)
         : MediaVideoTimeline.EMPTY);
   }

   @Override
   public MediaVideoTimeline timeline() {
      return this.timeline;
   }

   @Override
   public Vec3 position() {
      return this.turntablePos != null ? new Vec3(this.turntablePos.getX() + 0.5, this.turntablePos.getY() + 0.5, this.turntablePos.getZ() + 0.5) : null;
   }

   @Override
   public boolean isForTurntable(BlockPos pos) {
      return pos != null && this.turntablePos != null && this.turntablePos.equals(pos);
   }

   @Override
   public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
      if (minecraft != null && minecraft.player != null) {
         Vec3 playerPos = minecraft.player.position();
         Vec3 anchorPos = this.position();
         if (anchorPos != null && distanceSqr(anchorPos, playerPos) <= rangeSqr) {
            return true;
         } else if (fallbackProjectors == null) {
            return false;
         } else {
            for (BlockPos pos : fallbackProjectors) {
               Vec3 projectorPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
               if (distanceSqr(projectorPos, playerPos) <= rangeSqr) {
                  return true;
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   @Override
   public Object replacementOwnerKey() {
      return this.turntablePos != null
         ? new VideoPlaybackAnchor.TurntableOwnerKey(this.turntablePos)
         // 【反编译伪影修复】同 LiveVideoPlaybackAnchor：原 lambda 是恒等函数
         : this.playbackSessionId.<Object>map(value -> value).orElse(this);
   }

   private static double distanceSqr(Vec3 a, Vec3 b) {
      double dx = a.x - b.x;
      double dy = a.y - b.y;
      double dz = a.z - b.z;
      return dx * dx + dy * dy + dz * dz;
   }

   Optional<PlaybackSessionId> playbackSessionId() {
      return this.playbackSessionId;
   }

   private record TurntableMediaVideoTimeline(BlockPos turntablePos, Optional<PlaybackSessionId> playbackSessionId, long totalMillis)
      implements MediaVideoTimeline {
      private TurntableMediaVideoTimeline(BlockPos turntablePos, Optional<PlaybackSessionId> playbackSessionId, long totalMillis) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.turntablePos = turntablePos;
         this.playbackSessionId = playbackSessionId;
         this.totalMillis = totalMillis;
      }

      @Override
      public long mediaMillis() {
         return PlaybackClock.mediaMillis(this.turntablePos);
      }

      @Override
      public long visualMillis() {
         return PlaybackClock.visualMillis(this.turntablePos);
      }

      @Override
      public long pacingMillis() {
         return PlaybackClock.pacingMillis(this.turntablePos);
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         return PlaybackClock.relativeNanos(this.turntablePos, absoluteStartMillis);
      }
   }
}
