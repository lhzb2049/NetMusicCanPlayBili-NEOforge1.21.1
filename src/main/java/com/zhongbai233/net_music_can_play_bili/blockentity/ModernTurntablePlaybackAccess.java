package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class ModernTurntablePlaybackAccess {
   private ModernTurntablePlaybackAccess() {
   }

   static boolean isAllowed(ServerLevel level, String sourceUrl, ServerPlayer actor) {
      if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
         deny(actor, sourceUrl);
         return false;
      } else if (BiliWhitelistManager.enabled()
         && !BiliWhitelistManager.canonicalResource(sourceUrl).isEmpty()
         && !BiliWhitelistManager.isAllowed(level.getServer(), sourceUrl)) {
         deny(actor, sourceUrl);
         return false;
      } else {
         return true;
      }
   }

   private static void deny(ServerPlayer actor, String sourceUrl) {
      if (actor != null) {
         actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
      }
   }
}
