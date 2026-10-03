package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioEndpointIndex;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientAiSubtitleRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaDemandScheduler;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackSessions;
import com.zhongbai233.net_music_can_play_bili.client.sync.LiveRoomMetadataRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ModernTurntableTimeline;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.stream.CdnHealthTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent.Post;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.RenderFrameEvent.Pre;
import net.neoforged.neoforge.event.level.ChunkEvent.Load;
import net.neoforged.neoforge.event.level.LevelEvent.Unload;
import org.slf4j.Logger;

@EventBusSubscriber({Dist.CLIENT})
public final class ClientMediaLifecycleHandler {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long TICK_FALLBACK_AFTER_NANOS = 250000000L;
   private static volatile long lastFrameUpdateNanos;
   private static volatile Level lastTrackedLevel;

   private ClientMediaLifecycleHandler() {
   }

   @SubscribeEvent
   public static void onPlayerLoggedOut(LoggingOut event) {
      ControlConsoleClient.clearLease();
      cleanupClientPlayback();
   }

   @SubscribeEvent
   public static void onLevelUnload(Unload event) {
      if (event.getLevel() instanceof ClientLevel) {
         ControlConsoleClient.clearLease();
         cleanupClientPlayback();
      }
   }

   @SubscribeEvent
   public static void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
      if (event.getLevel() instanceof ClientLevel level) {
         ChunkPos pos = event.getChunk().getPos();
         ClientAiSubtitleRegistry.releaseChunk(pos.x, pos.z);
         TerrainPreviewManager.markChunkUnloaded(level, pos.x, pos.z);
      }
   }

   @SubscribeEvent
   public static void onChunkLoad(Load event) {
      if (event.getLevel() instanceof ClientLevel level) {
         ChunkPos pos = event.getChunk().getPos();
         TerrainPreviewManager.markChunkLoaded(level, pos.x, pos.z);
      }
   }

   @SubscribeEvent
   public static void onRenderFrame(Pre event) {
      ControlConsoleRenderer.updateConsumerFades();
      MP4ItemScreenRenderer.renderHeldOffscreenGuiFrameStart();
      PadItemScreenRenderer.renderHeldOffscreenGuiFrameStart();
      if (ClientAudioOutputRegistry.isActive()) {
         Minecraft mc = Minecraft.getInstance();
         if (updateFromCamera(mc)) {
            lastFrameUpdateNanos = System.nanoTime();
         }
      }
   }

   @SubscribeEvent
   public static void onClientPauseChanged(Post event) {
      ClientAudioOutputRegistry.setPaused(event.isPaused());
   }

   @SubscribeEvent
   public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
      long lifecycleNow = System.nanoTime();

      for (String warning : VideoCloseDiagnostics.tickGlobal()) {
         LOGGER.warn(warning);
      }

      OpenALSpatialAudio.tickNativeDeletes(lifecycleNow);

      for (String warning : AudioNativeCloseDiagnostics.tickGlobal()) {
         LOGGER.warn(warning);
      }

      ClientMemoryDiagnostics.tick();
      ClientMemoryProtection.tick(ClientMediaLifecycleHandler::emergencyCleanupClientPlayback);
      Minecraft mc = Minecraft.getInstance();
      updateDemandListener(mc);
      SyncedMediaSound.tickPendingDecodeAdmissions();
      ModernTurntablePlaybackCoordinator.tickIndexedPlaybackDemand();
      ClientMediaDemandScheduler.tick();
      if (mc.level != lastTrackedLevel) {
         lastTrackedLevel = mc.level;
         if (lastTrackedLevel == null) {
            ControlConsoleClient.clearLease();
            cleanupClientPlayback();
         }
      }

      MP4AutoResumeClient.tick();
      MP4Client.tickHeldDeviceIdPrefetch();
      PadClient.tickHeldDeviceSync();
      MP4HandheldVideoClient.tickHotbarVideoFrames();
      MP4HandheldVideoClient.stopDevicesOutsideHotbar();
      PadMapClientCache.tick();
      TerrainPreviewManager.tick();
      ControlConsoleRenderer.tickConsumers();
      PadItemScreenRenderer.tickHeldMapLayers();
      if (ClientAudioOutputRegistry.isActive()) {
         long now = System.nanoTime();
         long lastFrame = lastFrameUpdateNanos;
         if (lastFrame == 0L || now - lastFrame >= 250000000L) {
            if (!updateFromCamera(mc)) {
               if (mc.player != null) {
                  updateListener(mc.player.getEyePosition());
               }
            }
         }
      }
   }

   private static void updateDemandListener(Minecraft mc) {
      if (mc != null) {
         Camera camera = mc.gameRenderer.getMainCamera();
         if (camera != null && camera.isInitialized()) {
            Vec3 position = camera.getPosition();
            ClientAudioOutputRegistry.updateListenerPosition(new float[]{(float)position.x, (float)position.y, (float)position.z});
         } else if (mc.player != null) {
            Vec3 position = mc.player.getEyePosition();
            ClientAudioOutputRegistry.updateListenerPosition(new float[]{(float)position.x, (float)position.y, (float)position.z});
         }
      }
   }

   private static boolean updateFromCamera(Minecraft mc) {
      if (mc == null) {
         return false;
      } else {
         Camera camera = mc.gameRenderer.getMainCamera();
         if (camera != null && camera.isInitialized()) {
            updateListener(camera.getPosition());
            return true;
         } else {
            return false;
         }
      }
   }

   private static void updateListener(Vec3 eye) {
      float[] listenerPos = new float[]{(float)eye.x, (float)eye.y, (float)eye.z};
      ClientAudioOutputRegistry.updatePositions(listenerPos);
   }

   private static void cleanupClientPlayback() {
      cleanupClientPlayback(false);
   }

   private static void emergencyCleanupClientPlayback() {
      cleanupClientPlayback(true);
   }

   private static void cleanupClientPlayback(boolean emergency) {
      if (!emergency) {
         ClientMemoryDiagnostics.report("before-cleanup");
      }

      VideoBillboardPreview.stop();
      ModernTurntableVideoClient.clear();
      ModernTurntablePlaybackCoordinator.clearPendingPrepares();
      LiveStreamerVideoClient.clear();
      LiveRoomMetadataRegistry.clear();
      ModernTurntableTimeline.clear();
      ClientLinkRegistry.clear();
      SyncedStreamRecoveryRegistry.clear();
      CdnHealthTracker.clear();
      ModernTurntablePlaybackTracker.stopAllSounds();
      SyncedMediaSound.cancelPendingDecodeAdmissions();
      ClientAudioEndpointIndex.clear();
      MP4Client.clearCachedStates();
      PadClient.clearCachedDocuments();
      ClientMediaPlaybackSessions.clearAll(MP4HandheldVideoClient::clearAll);
      ClientMediaAudioRouting.clearLocalPrivateSources();
      MP4FocusState.resetAll();
      PadFocusState.resetAll();
      MP4ItemScreenRenderer.releaseAll();
      PadItemScreenRenderer.releaseAll();
      TerrainPreviewManager.clear();
      ControlConsoleRenderer.clearConsumers();
      ClientAiSubtitleRegistry.clear();
      MP4AutoResumeClient.reset();
      HttpAudioStreamHandler.closeModernStreams();
      ClientAudioOutputRegistry.cleanup();
      if (!emergency) {
         ClientMemoryDiagnostics.report("after-cleanup");
      }

      lastTrackedLevel = null;
   }

   public static void tripMemoryProtection(String reason) {
      ClientMemoryProtection.tripOnAllocationFailure(reason, ClientMediaLifecycleHandler::emergencyCleanupClientPlayback);
   }
}
