package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.gui.BigMegaphoneScreen;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
   value = {BigMegaphoneScreen.class},
   remap = false
)
public abstract class BigMegaphoneScreenMixin {
   @Shadow
   private EditBox urlTextField;

   @Inject(
      method = {"init"},
      at = {@At("TAIL")}
   )
   private void net_music_can_play_bili$hookButtons(CallbackInfo ci) {
      // 【移植伪影修复】jar 里这个 Mixin 类**没有 superclass**（原工程应为 extends BigMegaphoneScreen），
      // 于是 `(Screen)this` 在源码里非法（两个类互不为子类型）。经 Object 桥接可以写出同样的
      // checkcast（字节码实证：aload_0; checkcast Screen），且不改动类的 superclass。
      for (GuiEventListener child : ((Screen)(Object)this).children()) {
         if (child instanceof Button btn) {
            String msg = btn.getMessage().getString();
            boolean isStart = msg.contains("开始") || msg.equalsIgnoreCase("start");
            boolean isSave = msg.contains("保存") || msg.equalsIgnoreCase("save");
            if (isStart || isSave) {
               ButtonAccessor bridge = (ButtonAccessor)btn;
               OnPress original = bridge.net_music_can_play_bili$getOnPress();
               OnPress wrapped = btn2 -> {
                  String text = this.urlTextField.getValue().trim();
                  String roomId = BiliLiveRoomInput.parseRoomId(text);
                  if (!roomId.isEmpty()) {
                     this.urlTextField.setValue(BiliLiveRoomInput.placeholderUrl(roomId));
                     original.onPress(btn2);
                     this.urlTextField.setValue(text);
                  } else {
                     original.onPress(btn2);
                  }
               };
               bridge.net_music_can_play_bili$setOnPress(wrapped);
            }
         }
      }
   }
}
