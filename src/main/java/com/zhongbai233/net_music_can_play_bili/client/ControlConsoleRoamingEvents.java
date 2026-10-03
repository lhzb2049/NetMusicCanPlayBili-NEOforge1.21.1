package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.port.shim.PortSubmitNodeCollector;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortWorldRenderEvents;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;

public final class ControlConsoleRoamingEvents {
   private ControlConsoleRoamingEvents() {
   }

   public static void onClientTick(Post event) {
      ControlConsoleRoamingSession.tick();
   }

   public static void onMovementInput(MovementInputUpdateEvent event) {
      ControlConsoleRoamingSession.suppressPlayerInput(event.getInput());
   }

   public static void onMouseButton(Pre event) {
      if (ControlConsoleRoamingSession.handleMouseButton(event.getButton(), event.getAction())) {
         event.setCanceled(true);
      }
   }

   public static void onInteraction(InteractionKeyMappingTriggered event) {
      if (ControlConsoleRoamingSession.isActive() && (event.isAttack() || event.isUseItem() || event.isPickBlock())) {
         event.setSwingHand(false);
         event.setCanceled(true);
      }
   }

   public static void onMouseScroll(MouseScrollingEvent event) {
      if (ControlConsoleRoamingSession.isActive()) {
         ControlConsoleRoamingSession.adjustFlyingSpeed(event.getScrollDeltaY());
         event.setCanceled(true);
      }
   }

   public static void onRenderGui(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
      ControlConsoleRoamingSession.drawHud(event.getGuiGraphics());
   }

   public static void onSubmitGeometry(RenderLevelStageEvent event) {
      if (PortWorldRenderEvents.isStage(event, Stage.AFTER_TRANSLUCENT_BLOCKS)) {
         PortSubmitNodeCollector collector = PortWorldRenderEvents.begin();

         try {
            ControlConsoleRoamingSession.submitGeometry(collector);
         } finally {
            PortWorldRenderEvents.end(collector);
         }
      }
   }

   public static void onLogout(LoggingOut event) {
      ControlConsoleRoamingSession.stop(false);
   }

   public static void onClone(Clone event) {
      ControlConsoleRoamingSession.stop(false);
   }
}
