package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {SetMusicIDMessage.class},
   remap = false
)
public abstract class SetMusicIDMessageMixin {
   @Inject(
      method = {"handle"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private static void net_music_can_play_bili$guardBiliWhitelist(SetMusicIDMessage message, IPayloadContext context, CallbackInfo ci) {
      if (message != null && context != null && context.flow().isServerbound()) {
         SongInfo song = message.song();
         String songUrl = song == null ? null : song.songUrl;
         SongInfo normalized = BiliSongInfoSanitizer.sanitize(song);
         if (normalized != song) {
            SetMusicIDMessage.handle(new SetMusicIDMessage(Objects.requireNonNull(normalized)), context);
            ci.cancel();
         } else {
            songUrl = normalized == null ? songUrl : normalized.songUrl;
            boolean forbiddenBiliDirectUrl = BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(songUrl);
            if (forbiddenBiliDirectUrl || BiliWhitelistManager.enabled() && !BiliWhitelistManager.canonicalResource(songUrl).isEmpty()) {
               if (context.player() instanceof ServerPlayer player) {
                  if (forbiddenBiliDirectUrl) {
                     player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, songUrl, "创建唱片/音源头"));
                     ci.cancel();
                  } else if (!BiliWhitelistManager.isAllowed(player.level().getServer(), songUrl)) {
                     player.sendSystemMessage(BiliWhitelistManager.denialMessage(player, songUrl, "创建唱片/音源头"));
                     ci.cancel();
                  }
               } else {
                  ci.cancel();
               }
            }
         }
      }
   }
}
