package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Gui.class})
public abstract class GuiRoamingCrosshairMixin {
   @Inject(
      method = {"renderCrosshair"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private void net_music_can_play_bili$hideVanillaCrosshair(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
      if (ControlConsoleRoamingSession.isActive()) {
         callback.cancel();
      }
   }
}
