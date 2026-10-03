package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.network.message.MusicToClientMessage;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientMediaPreparer;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientPlaybackCommand;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {MusicToClientMessage.class},
   remap = false
)
public abstract class MusicToClientMessageClientMixin {
   @Inject(
      method = {"onHandle"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private static void net_music_can_play_bili$injectBiliLyrics(MusicToClientMessage message, CallbackInfo ci) {
      PlaybackSync.Metadata sync = PlaybackSync.parse(message.url());
      boolean modernTurntable = sync.hasSession() || net_music_can_play_bili$isModernTurntable(message);
      boolean biliSelection = ClientMediaPreparer.hasStoredBiliSelection(message.rawUrl(), message.url());
      if (modernTurntable || biliSelection) {
         if (modernTurntable) {
            PlaybackSync.MinecartAnchor minecartAnchor = PlaybackSync.parseMinecartAnchor(message.url());
            ClientPlaybackCommand command = ModernTurntablePlaybackCoordinator.command(
               message.pos(), message.rawUrl(), message.url(), message.songName(), message.timeSecond(), sync, minecartAnchor, biliSelection
            );
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> ModernTurntablePlaybackCoordinator.play(command));
            ci.cancel();
         } else {
            ClientPlaybackCommand command = ModernTurntablePlaybackCoordinator.command(
               message.pos(), message.rawUrl(), message.url(), message.songName(), message.timeSecond(), sync, null, biliSelection
            );
            Minecraft.getInstance().execute(() -> ModernTurntablePlaybackCoordinator.playCompatible(command));
            ci.cancel();
         }
      }
   }

   @Unique
   private static boolean net_music_can_play_bili$isModernTurntable(MusicToClientMessage message) {
      ClientLevel level = Minecraft.getInstance().level;
      return level != null && level.getBlockEntity(message.pos()) instanceof ModernTurntableBlockEntity;
   }
}
