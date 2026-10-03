package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Entity.class})
public abstract class EntityTurnRoamingMixin {
   @Inject(
      method = {"turn"},
      at = {@At("HEAD")},
      cancellable = true,
      remap = false
   )
   private void net_music_can_play_bili$turnRoamingCamera(double yaw, double pitch, CallbackInfo callback) {
      // 【移植伪影修复】jar 里这个 Mixin 类没有 superclass（原工程应为 extends Entity），
      // 于是 `this instanceof LocalPlayer` 在源码里非法。经 Object 桥接写出同样的 instanceof
      // （字节码实证：aload_0; instanceof LocalPlayer）。
      if (((Object)this) instanceof LocalPlayer && ControlConsoleRoamingSession.turnCamera(yaw, pitch)) {
         callback.cancel();
      }
   }
}
