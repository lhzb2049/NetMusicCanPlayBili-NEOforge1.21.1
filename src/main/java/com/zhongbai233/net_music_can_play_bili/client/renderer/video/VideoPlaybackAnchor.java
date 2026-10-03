package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

interface VideoPlaybackAnchor {
   MediaVideoTimeline timeline();

   Vec3 position();

   boolean isForTurntable(BlockPos var1);

   boolean isWithinAudioRange(Minecraft var1, Collection<BlockPos> var2, double var3);

   default Object replacementOwnerKey() {
      return this;
   }

   static VideoPlaybackAnchor turntable(BlockPos turntablePos, String sessionId, long totalMillis) {
      return new TurntableVideoPlaybackAnchor(turntablePos, sessionId, totalMillis);
   }

   public record LiveOwnerKey(BlockPos pos) {
      public LiveOwnerKey(BlockPos pos) {
         pos = Objects.requireNonNull(pos, "pos").immutable();
         this.pos = pos;
      }
   }

   public record PreviewOwnerKey(UUID sourceId) {
      public PreviewOwnerKey(UUID sourceId) {
         Objects.requireNonNull(sourceId, "sourceId");
         this.sourceId = sourceId;
      }
   }

   public record TurntableOwnerKey(BlockPos pos) {
      public TurntableOwnerKey(BlockPos pos) {
         pos = Objects.requireNonNull(pos, "pos").immutable();
         this.pos = pos;
      }
   }
}
