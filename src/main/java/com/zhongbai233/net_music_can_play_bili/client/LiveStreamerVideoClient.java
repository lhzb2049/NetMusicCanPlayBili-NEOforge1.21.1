package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

public final class LiveStreamerVideoClient {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final VideoClientProperties.Live VIDEO_PROPERTIES = VideoClientProperties.live();
   private static final int HIGH_QUALITY_CEILING = 116;
   private static final ConcurrentHashMap<PlaybackSessionId, String> LAST_DECISION = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlaybackSessionId, Integer> ACTIVE_QUALITY = new ConcurrentHashMap<>();
   private static final MediaConsumerRegistry<BlockPos> CONTROL_CONSOLE_CONSUMERS = new MediaConsumerRegistry<>();
   private static final ConcurrentHashMap<BlockPos, Integer> CONTROL_CONSOLE_QUALITY = new ConcurrentHashMap<>();

   private LiveStreamerVideoClient() {
   }

   public static void sync(BlockPos livePos, String sessionId) {
      Minecraft minecraft = Minecraft.getInstance();
      PlaybackSessionId sessionKey = PlaybackSessionId.parse(sessionId).orElse(null);
      if (livePos != null && sessionKey != null && minecraft.level != null) {
         int rawSourceCount = ClientLinkRegistry.getSources(livePos).size();
         List<VideoProjectorBlockEntity> projectors = findLinkedVideoProjectors(livePos);
         LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
         projectors.stream().map(projector -> projector.getBlockPos().immutable()).forEach(positions::add);
         positions.addAll(CONTROL_CONSOLE_CONSUMERS.consumersFor(livePos));
         boolean holographicConsumer = HolographicGlassesClient.handlesTurntable(livePos);
         if (positions.isEmpty() && !holographicConsumer) {
            logDecision(
               sessionKey, "no-consumer", "直播画面暂无消费端: pos=" + livePos + " session=" + sessionId + " registrySources=" + rawSourceCount + "（需要链接视频投影仪或佩戴全息眼镜）"
            );
            VideoBillboardPreview.stopIfSession(sessionId);
         } else if (!isAudioReady(livePos, sessionId)) {
            logDecision(sessionKey, "wait-audio", "直播画面等待音频输出就绪: pos=" + livePos + " session=" + sessionId);
         } else {
            List<BlockPos> consumerPositions = List.copyOf(positions);
            int qualityCeiling = qualityCeiling(projectors, CONTROL_CONSOLE_CONSUMERS.consumersFor(livePos));
            if (VideoBillboardPreview.isSessionRunning(sessionId)) {
               if (Objects.equals(ACTIVE_QUALITY.get(sessionKey), qualityCeiling)) {
                  VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
                  logDecision(sessionKey, "running:" + consumerPositions.size(), "直播画面会话运行中: session=" + sessionId + " consumers=" + consumerPositions.size());
                  return;
               }

               VideoBillboardPreview.stopIfSession(sessionId);
            }

            int width = qualityCeiling >= 116 ? 1920 : 1280;
            int height = qualityCeiling >= 116 ? 1080 : 720;
            LOGGER.info(
               "直播画面会话启动: session={} pos={} {}x{}@{}fps projectors={} holographic={}",
               new Object[]{sessionId, livePos, width, height, VIDEO_PROPERTIES.fps(), consumerPositions.size(), holographicConsumer}
            );
            LAST_DECISION.put(sessionKey, "started");
            ACTIVE_QUALITY.put(sessionKey, qualityCeiling);
            VideoBillboardPreview.startLiveSession(
               LiveVideoSampleBus.busUrl(sessionKey), width, height, VIDEO_PROPERTIES.fps(), sessionId, consumerPositions, livePos
            );
         }
      }
   }

   public static void registerControlConsoleConsumer(BlockPos livePos, BlockPos consolePos, int qualityCeiling) {
      if (livePos != null && consolePos != null) {
         CONTROL_CONSOLE_CONSUMERS.register(livePos.immutable(), consolePos.immutable());
         CONTROL_CONSOLE_QUALITY.put(consolePos.immutable(), qualityCeiling);
      }
   }

   public static void unregisterControlConsoleConsumer(BlockPos consolePos) {
      CONTROL_CONSOLE_CONSUMERS.unregister(consolePos);
      if (consolePos != null) {
         CONTROL_CONSOLE_QUALITY.remove(consolePos);
      }
   }

   public static void clear() {
      CONTROL_CONSOLE_CONSUMERS.clear();
      CONTROL_CONSOLE_QUALITY.clear();
      LAST_DECISION.clear();
      ACTIVE_QUALITY.clear();
   }

   public static void forget(String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(sessionKey -> {
         LAST_DECISION.remove(sessionKey);
         ACTIVE_QUALITY.remove(sessionKey);
         VideoBillboardPreview.stopIfSession(sessionId);
      });
   }

   private static void logDecision(PlaybackSessionId sessionId, String fingerprint, String message) {
      String previous = LAST_DECISION.put(sessionId, fingerprint);
      if (!fingerprint.equals(previous)) {
         LOGGER.debug("{}", message);
      }
   }

   private static boolean isAudioReady(BlockPos livePos, String sessionId) {
      ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(livePos);
      String audioSessionId = timeline.audioSessionId();
      return audioSessionId != null && !audioSessionId.isBlank() && !audioSessionId.equals(sessionId)
         ? false
         : timeline.audibleMillis() >= 0L || timeline.fedMillis() >= 0L;
   }

   private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors, Collection<BlockPos> consoleConsumers) {
      int projectorQuality = projectors.stream()
         .mapToInt(projector -> projector.getPreferredQuality() > 0 ? projector.getPreferredQuality() : VIDEO_PROPERTIES.qualityCeiling())
         .max()
         .orElse(0);
      int consoleQuality = consoleConsumers.stream()
         .mapToInt(pos -> CONTROL_CONSOLE_QUALITY.getOrDefault(pos, VIDEO_PROPERTIES.qualityCeiling()))
         .max()
         .orElse(0);
      int selected = Math.max(projectorQuality, consoleQuality);
      return selected > 0 ? selected : VIDEO_PROPERTIES.qualityCeiling();
   }

   private static List<VideoProjectorBlockEntity> findLinkedVideoProjectors(BlockPos livePos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (livePos != null && minecraft.level != null) {
         List<VideoProjectorBlockEntity> projectors = new ArrayList<>();

         for (BlockPos sourcePos : ClientLinkRegistry.getSources(livePos)) {
            if (minecraft.level.getBlockEntity(sourcePos) instanceof VideoProjectorBlockEntity projector && livePos.equals(projector.getLinkedTurntablePos())) {
               projectors.add(projector);
            }
         }

         return projectors;
      } else {
         return List.of();
      }
   }
}
