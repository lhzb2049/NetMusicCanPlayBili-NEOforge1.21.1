package com.zhongbai233.scene_editor.minecraft.input;

import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import java.util.Optional;

public final class MinecraftEditorInput {
   private MinecraftEditorInput() {
   }

   public static Optional<StandardCameraView> standardView(int key) {
      return Optional.ofNullable(switch (key) {
         case 49, 321 -> StandardCameraView.FRONT;
         case 50, 322 -> StandardCameraView.BACK;
         case 51, 323 -> StandardCameraView.LEFT;
         case 52, 324 -> StandardCameraView.RIGHT;
         case 53, 325 -> StandardCameraView.TOP;
         case 54, 326 -> StandardCameraView.BOTTOM;
         default -> null;
      });
   }

   public static Optional<MinecraftEditorInput.FlyControl> flyControl(int key, boolean forwardOnW) {
      return Optional.ofNullable(switch (key) {
         case 32 -> forwardOnW ? MinecraftEditorInput.FlyControl.UP : null;
         case 65 -> MinecraftEditorInput.FlyControl.LEFT;
         case 67 -> forwardOnW ? MinecraftEditorInput.FlyControl.DOWN : null;
         case 68 -> MinecraftEditorInput.FlyControl.RIGHT;
         case 69 -> forwardOnW ? null : MinecraftEditorInput.FlyControl.BACKWARD;
         case 81 -> forwardOnW ? null : MinecraftEditorInput.FlyControl.FORWARD;
         case 83 -> forwardOnW ? MinecraftEditorInput.FlyControl.BACKWARD : MinecraftEditorInput.FlyControl.DOWN;
         case 87 -> forwardOnW ? MinecraftEditorInput.FlyControl.FORWARD : MinecraftEditorInput.FlyControl.UP;
         case 340, 344 -> MinecraftEditorInput.FlyControl.FAST;
         default -> null;
      });
   }

   public static enum FlyControl {
      FORWARD,
      BACKWARD,
      LEFT,
      RIGHT,
      DOWN,
      UP,
      FAST;
   }
}
