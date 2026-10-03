package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution.MinecartTurntableCompat;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

final class ModernTurntablePlaybackClock {
   private ModernTurntablePlaybackClock() {
   }

   static String sessionId(Level level, BlockPos sourcePos, long startedGameTime, int seekGeneration) {
      UUID hostUuid = MinecartTurntableCompat.hostUuid(level);
      String sourceId = hostUuid != null ? "minecart-" + hostUuid : Long.toString(sourcePos.asLong());
      return sourceId + "-" + startedGameTime + (seekGeneration > 0 ? "-" + seekGeneration : "");
   }
}
