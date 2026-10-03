package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.LyricProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ModernTurntableRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.SpeakerRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.VideoProjectorRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.CuriosHeadGearLayer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.HolographicPrivacyOverlay;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.YuvVideoRenderTypes;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolBindingScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolReportScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortPictureInPictureRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.neoforged.neoforge.client.event.RenderFrameEvent.Pre;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

public final class ModernTurntableClientEvents {
   private static final Logger LOGGER = LogUtils.getLogger();

   private ModernTurntableClientEvents() {
   }

   public static void register(IEventBus modEventBus) {
      modEventBus.addListener(ModernTurntableClientEvents::registerRenderers);
      modEventBus.addListener(CuriosHeadGearLayer::register);
      PortPictureInPictureRenderer.registerPipRenderer(HolographicPreviewPipRenderState.class, HolographicPreviewPipRenderer::new);
      modEventBus.addListener(ModernTurntableClientEvents::registerMenuScreens);
      modEventBus.addListener(HolographicGlassesKeyHandler::register);
      modEventBus.addListener(YuvVideoRenderTypes::registerPipelines);
      NeoForge.EVENT_BUS.addListener(ModernTurntableClientEvents::onClientLogout);
      NeoForge.EVENT_BUS.addListener(ModernTurntableClientEvents::onRenderFrame);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onClientTick);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMovementInput);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMouseButton);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onInteraction);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onMouseScroll);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onRenderGui);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onSubmitGeometry);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onLogout);
      NeoForge.EVENT_BUS.addListener(ControlConsoleRoamingEvents::onClone);
   }

   private static void onRenderFrame(Pre event) {
      if (Minecraft.getInstance().screen instanceof HolographicScreenConfigTestScreen screen) {
         double deltaSeconds = Math.clamp(event.getPartialTick().getRealtimeDeltaTicks() / 20.0, 0.0, 0.1);
         screen.advanceCameraFrame(deltaSeconds);
      }
   }

   private static void onClientLogout(LoggingOut event) {
      HolographicPrivacyOverlay.release();
   }

   private static void registerMenuScreens(RegisterMenuScreensEvent event) {
      event.register((MenuType)ModMenus.MEDIA_TOOL_BINDING.get(), MediaToolBindingScreen::new);
      event.register((MenuType)ModMenus.MEDIA_TOOL_REPORT.get(), MediaToolReportScreen::new);
   }

   private static void registerRenderers(RegisterRenderers event) {
      event.registerBlockEntityRenderer((BlockEntityType)ModBlockEntities.MODERN_TURNTABLE.get(), ModernTurntableRenderer::new);
      event.registerBlockEntityRenderer((BlockEntityType)ModBlockEntities.LYRIC_PROJECTOR.get(), LyricProjectorRenderer::new);
      event.registerBlockEntityRenderer((BlockEntityType)ModBlockEntities.VIDEO_PROJECTOR.get(), VideoProjectorRenderer::new);
      event.registerBlockEntityRenderer((BlockEntityType)ModBlockEntities.SPEAKER.get(), SpeakerRenderer::new);
      event.registerBlockEntityRenderer((BlockEntityType)ModBlockEntities.CONTROL_CONSOLE.get(), ControlConsoleRenderer::new);
      warmupClientResources();
   }

   private static void warmupClientResources() {
      Minecraft.getInstance().execute(() -> {
         try {
            MP4ItemScreenRenderer.warmup();
            PadItemScreenRenderer.warmup();
            LOGGER.debug("MP4/Pad handheld GUI resources warmed up");
         } catch (Exception var1) {
            LOGGER.warn("MP4/Pad handheld GUI resource warmup failed; falling back to lazy initialization", var1);
         }
      });
   }
}
