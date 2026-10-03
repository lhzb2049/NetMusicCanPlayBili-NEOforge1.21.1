package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class LiveVideoPlaybackAnchor implements VideoPlaybackAnchor {
   private final BlockPos livePos;
   private final Optional<PlaybackSessionId> playbackSessionId;
   private final MediaVideoTimeline timeline;

   LiveVideoPlaybackAnchor(BlockPos livePos, String sessionId) {
      this.livePos = livePos != null ? livePos.immutable() : null;
      this.playbackSessionId = PlaybackSessionId.parse(sessionId);
      this.timeline = (MediaVideoTimeline)(this.livePos != null
         ? new LiveVideoPlaybackAnchor.LiveAudioMediaTimeline(this.livePos, this.playbackSessionId)
         : MediaVideoTimeline.EMPTY);
   }

   @Override
   public MediaVideoTimeline timeline() {
      return this.timeline;
   }

   @Override
   public Vec3 position() {
      return this.livePos != null ? new Vec3(this.livePos.getX() + 0.5, this.livePos.getY() + 0.5, this.livePos.getZ() + 0.5) : null;
   }

   @Override
   public boolean isForTurntable(BlockPos pos) {
      return pos != null && this.livePos != null && this.livePos.equals(pos);
   }

   @Override
   public boolean isWithinAudioRange(Minecraft minecraft, Collection<BlockPos> fallbackProjectors, double rangeSqr) {
      if (minecraft != null && minecraft.player != null) {
         Vec3 playerPos = minecraft.player.position();
         Vec3 anchorPos = this.position();
         if (anchorPos != null && anchorPos.distanceToSqr(playerPos) <= rangeSqr) {
            return true;
         } else if (fallbackProjectors == null) {
            return false;
         } else {
            for (BlockPos pos : fallbackProjectors) {
               Vec3 projectorPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
               if (projectorPos.distanceToSqr(playerPos) <= rangeSqr) {
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
      return this.livePos != null
         ? new VideoPlaybackAnchor.LiveOwnerKey(this.livePos)
         // 【反编译伪影修复】原 lambda 就是恒等函数（字节码 lambda$replacementOwnerKey$0 只有 aload_0/areturn），
         // 反编译器却编出了 `.<LiveVideoPlaybackAnchor>map(value -> (LiveVideoPlaybackAnchor)value)` 这种非法写法。
         // 用 <Object> 显式指定推断目标，lambda 体写成恒等 → 生成的合成方法与原字节码一致。
         : this.playbackSessionId.<Object>map(value -> value).orElse(this);
   }

   Optional<PlaybackSessionId> playbackSessionId() {
      return this.playbackSessionId;
   }

   private record LiveAudioMediaTimeline(BlockPos livePos, Optional<PlaybackSessionId> playbackSessionId) implements MediaVideoTimeline {
      private LiveAudioMediaTimeline(BlockPos livePos, Optional<PlaybackSessionId> playbackSessionId) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.livePos = livePos;
         this.playbackSessionId = playbackSessionId;
      }

      @Override
      public long mediaMillis() {
         ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(this.livePos);
         return audio.playbackSessionId().isPresent() && !audio.playbackSessionId().equals(this.playbackSessionId) ? -1L : audio.audibleMillis();
      }

      @Override
      public long visualMillis() {
         return this.mediaMillis();
      }

      @Override
      public long pacingMillis() {
         return this.mediaMillis();
      }

      @Override
      public long relativeNanos(long absoluteStartMillis) {
         long millis = this.mediaMillis();
         return millis < 0L ? -1L : Math.max(0L, millis - Math.max(0L, absoluteStartMillis)) * 1000000L;
      }

      @Override
      public long totalMillis() {
         return 0L;
      }
   }
}
