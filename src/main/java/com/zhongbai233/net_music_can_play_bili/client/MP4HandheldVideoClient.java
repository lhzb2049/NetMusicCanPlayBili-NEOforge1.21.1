package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.PadDiagnosticsProperties;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.diagnostics.ClientMemoryProtection;
import com.zhongbai233.net_music_can_play_bili.client.media.LocalVideoSources;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4ItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadItemScreenRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoFallbackReason;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoZombieCloseSupervisor;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoPipelineConfig;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

public final class MP4HandheldVideoClient {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final HandheldVideoPipelineConfig CONFIG = HandheldVideoPipelineConfig.fromSystemProperties("ncpb.mp4.video");
   private static final VideoClientProperties.Handheld VIDEO_PROPERTIES = VideoClientProperties.handheld();
   private static final boolean PAD_VIDEO_DEBUG_LOG = PadDiagnosticsProperties.videoDebugLogEnabled();
   private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
      VIDEO_PROPERTIES.maxThreads(), NetMusicThreadFactory.daemon("mp4-handheld-video")
   );
   private static final Map<PlaybackSourceId, HandheldDeviceVideoState> STATES = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, HandheldMediaDeviceProfile> PROFILES = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, HandheldReplacementGate> REPLACEMENT_GATES = new ConcurrentHashMap<>();
   private static final MP4HandheldMediaProfile MP4_PROFILE = MP4HandheldMediaProfile.INSTANCE;
   private static final long CANDIDATE_CLOSE_TIMEOUT_MILLIS = 3000L;
   private static final AtomicLong HANDHELD_CLOSE_SEQUENCE = new AtomicLong();

   private MP4HandheldVideoClient() {
   }

   public static boolean update(UUID deviceId) {
      return update(deviceId, MP4_PROFILE);
   }

   public static boolean update(UUID deviceId, HandheldMediaDeviceProfile profile) {
      if (deviceId == null) {
         return false;
      } else {
         HandheldMediaDeviceProfile activeProfile = (HandheldMediaDeviceProfile)(profile != null ? profile : MP4_PROFILE);
         PROFILES.put(PlaybackSourceId.of(deviceId), activeProfile);
         HandheldDeviceVideoState state = state(deviceId);
         if (!activeProfile.isDeviceAvailable(deviceId)) {
            stop(deviceId, "等待快捷栏");
            return false;
         } else {
            HandheldMediaRenderState renderState = activeProfile.renderState(deviceId);
            if (!renderState.videoDecodeEnabled()) {
               stop(deviceId, "等待横屏播放");
               return false;
            } else if (!ClientMemoryProtection.allowMediaStart()) {
               stop(deviceId, "视频内存保护冷却中");
               return false;
            } else {
               HandheldMediaPlayback playback = activeProfile.playback(deviceId);
               if (!playback.hasPlayableVideoSource()) {
                  stop(deviceId, "等待播放同步");
                  return false;
               } else if (!VideoNativeDecoder.isNativeAvailable()) {
                  stopForNativeUnavailable(state, playback);
                  return false;
               } else if (!activeProfile.canStartVideoDecode(deviceId, playback)) {
                  waitForAudioStart(state);
                  return false;
               } else {
                  HandheldPlaybackKey key = new HandheldPlaybackKey(
                     playback.playbackSessionId(),
                     playback.rawUrl(),
                     renderState.videoQualityCeiling(),
                     renderState.allowAiSubtitle(),
                     HandheldVideoFrameTimeline.shouldUseRgbaFallback() || HandheldVideoFrameTimeline.hasActiveRgbaConsumer(state)
                  );
                  long intentGeneration;
                  synchronized (state.lifecycleLock) {
                     HandheldVideoSession session = state.activeSession;
                     if (key.equals(state.activeKey) && session != null && !session.closed.get()) {
                        return HandheldVideoFrameTimeline.pumpFrameForTimeline(
                           state, session, HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, activeProfile, playback)
                        );
                     }

                     if (key.equals(state.resolvingKey)) {
                        return false;
                     }

                     if (key.equals(state.activeKey) && (key.equals(state.failedKey) || key.equals(state.endedKey))) {
                        return false;
                     }

                     stopLocked(state, "切换视频源");
                     intentGeneration = state.intentGeneration;
                     state.activeKey = key;
                     state.resolvingKey = key;
                     state.failedKey = HandheldPlaybackKey.EMPTY;
                     state.endedKey = HandheldPlaybackKey.EMPTY;
                     if (!BiliVideoStreamResolver.isStoredVideoSelection(playback.rawUrl())) {
                        state.resolvingKey = HandheldPlaybackKey.EMPTY;
                        state.failedKey = key;
                        state.audioOnly = true;
                        state.statusText = "纯音乐";
                        state.sourceWidth = 0;
                        state.sourceHeight = 0;
                        HandheldVideoFrameTimeline.clearFrameQueue(state);
                        return false;
                     }

                     state.audioOnly = false;
                     state.statusText = "解析视频流...";
                     state.sourceWidth = 0;
                     state.sourceHeight = 0;
                  }

                  resolveAndStart(deviceId, state, playback, key, intentGeneration);
                  return false;
               }
            }
         }
      }
   }

   public static void markVisible(UUID deviceId) {
      if (deviceId != null) {
         HandheldDeviceVideoState state = state(deviceId);
         long nowNs = System.nanoTime();
         long offscreenSince = state.offscreenSinceNanoTime;
         state.lastVisibleNanoTime = nowNs;
         state.offscreenSinceNanoTime = 0L;
         if (offscreenSince > 0L) {
            HandheldOffscreenVideoPolicy.maybeRestartVisibleSession(deviceId, state, nowNs - offscreenSince);
         }
      }
   }

   public static void requestRgbaOutput(UUID deviceId) {
      if (deviceId != null) {
         HandheldDeviceVideoState state = state(deviceId);
         state.rgbaConsumerUntilNanoTime = System.nanoTime() + Math.max(0L, CONFIG.rgbaConsumerGraceNanos());
         markVisible(deviceId);
      }
   }

   public static HandheldVideoFrame latestFrame(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      return state != null ? state.latestFrame.get() : null;
   }

   public static HandheldVideoFrame acquireLatestFrame(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      if (state == null) {
         return null;
      } else {
         for (int attempt = 0; attempt < 2; attempt++) {
            HandheldVideoFrame frame = state.latestFrame.get();
            if (frame == null) {
               return null;
            }

            try {
               return frame.retain();
            } catch (IllegalStateException var5) {
            }
         }

         return null;
      }
   }

   public static long frameSequence(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      return state != null ? state.frameSequence.get() : -1L;
   }

   public static String statusText(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      return state != null ? state.statusText : "等待设备 ID";
   }

   public static boolean audioOnly(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      return state != null && state.audioOnly;
   }

   public static String currentResolutionLabel(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      if (state == null) {
         return "";
      } else {
         HandheldVideoFrame frame = state.latestFrame.get();
         if (frame != null && frame.width() > 0 && frame.height() > 0) {
            return frame.width() + "x" + frame.height();
         } else {
            int width = state.sourceWidth;
            int height = state.sourceHeight;
            if (width > 0 && height > 0) {
               MP4HandheldVideoClient.DecodeSize preview = HandheldVideoFrameTimeline.chooseDecodeSize(width, height);
               return preview.width() + "x" + preview.height();
            } else {
               return "";
            }
         }
      }
   }

   public static String currentSubtitle(UUID deviceId) {
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      if (state == null) {
         return "";
      } else {
         LyricRecord record = state.subtitleRecord;
         if (record == null) {
            return state.currentSubtitle != null ? state.currentSubtitle : "";
         } else {
            HandheldMediaDeviceProfile profile = profileFor(deviceId);
            HandheldMediaPlayback playback = profile.playback(deviceId);
            long visualMillis = HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, profile, playback);
            int tick = visualMillis >= 0L ? (int)Math.min(2147483647L, visualMillis / 50L) : -1;
            String primary = currentLineAt(record.getLyrics(), tick);
            String secondary = currentLineAt(record.getTransLyrics(), tick);
            String mode = profile.subtitleMode(deviceId);
            if ("off".equals(mode)) {
               return "";
            } else if ("primary".equals(mode)) {
               return !primary.isBlank() ? primary : secondary;
            } else {
               return !secondary.isBlank() ? secondary : primary;
            }
         }
      }
   }

   public static void stop(String reason) {
      STATES.values().forEach(state -> stop(state, reason));
      MP4ItemScreenRenderer.releaseAllVideoLayers();
   }

   public static void stop(UUID deviceId, String reason) {
      HandheldMediaDeviceProfile profile = profileFor(deviceId);
      HandheldDeviceVideoState state = stateOrNull(deviceId);
      if (state != null) {
         stop(state, reason);
      }

      if (profile == MP4_PROFILE) {
         MP4ItemScreenRenderer.releaseVideoLayers(deviceId);
      } else {
         PadItemScreenRenderer.releaseVideoLayers(deviceId);
      }
   }

   private static void stop(HandheldDeviceVideoState state, String reason) {
      synchronized (state.lifecycleLock) {
         stopLocked(state, reason);
      }
   }

   private static void stopLocked(HandheldDeviceVideoState state, String reason) {
      state.intentGeneration++;
      cancelResolveTaskLocked(state);
      state.activeKey = HandheldPlaybackKey.EMPTY;
      state.resolvingKey = HandheldPlaybackKey.EMPTY;
      state.failedKey = HandheldPlaybackKey.EMPTY;
      state.endedKey = HandheldPlaybackKey.EMPTY;
      HandheldVideoSession session = state.activeSession;
      state.activeSession = null;
      if (session != null) {
         session.close();
      }

      if (reason != null && !reason.isBlank()) {
         state.statusText = reason;
      }

      state.audioOnly = false;
      state.subtitleRecord = null;
      state.currentSubtitle = "";
      state.sourceWidth = 0;
      state.sourceHeight = 0;
      HandheldVideoFrameTimeline.clearFrameQueue(state);
      HandheldVideoFrame latest = state.latestFrame.getAndSet(null);
      if (latest != null) {
         latest.close();
         state.frameSequence.incrementAndGet();
      }
   }

   private static void stopForNativeUnavailable(HandheldDeviceVideoState state, HandheldMediaPlayback playback) {
      synchronized (state.lifecycleLock) {
         if (!"原生视频不可用".equals(state.statusText)) {
            stopLocked(state, "原生视频不可用");
            state.audioOnly = true;
            state.statusText = "原生视频不可用";
            LOGGER.warn(
               "手持视频解码跳过：FFmpeg native 未加载，session={} raw='{}'",
               playback != null ? playback.sessionId() : "unknown",
               playback != null ? playback.rawUrl() : "unknown"
            );
         }
      }
   }

   private static void waitForAudioStart(HandheldDeviceVideoState state) {
      synchronized (state.lifecycleLock) {
         state.intentGeneration++;
         cancelResolveTaskLocked(state);
         state.statusText = "等待音频缓冲...";
         state.audioOnly = false;
         HandheldVideoSession session = state.activeSession;
         if (session != null) {
            session.close();
            state.activeSession = null;
         }

         state.activeKey = HandheldPlaybackKey.EMPTY;
         state.resolvingKey = HandheldPlaybackKey.EMPTY;
         state.failedKey = HandheldPlaybackKey.EMPTY;
         state.endedKey = HandheldPlaybackKey.EMPTY;
      }

      HandheldVideoFrameTimeline.clearFrameQueue(state);
      HandheldVideoFrame latest = state.latestFrame.getAndSet(null);
      if (latest != null) {
         latest.close();
         state.frameSequence.incrementAndGet();
      }
   }

   public static void clearAll() {
      STATES.values().forEach(state -> stop(state, "等待播放"));
      STATES.clear();
      PROFILES.clear();
      MP4ItemScreenRenderer.releaseAllVideoLayers();
   }

   public static void stopDevicesOutsideHotbar() {
      for (Entry<PlaybackSourceId, HandheldDeviceVideoState> entry : STATES.entrySet()) {
         UUID deviceId = entry.getKey().value();
         HandheldMediaDeviceProfile profile = profileFor(deviceId);
         if (!profile.isDeviceAvailable(deviceId)) {
            stop(entry.getValue(), profile == MP4_PROFILE ? "等待快捷栏" : "等待设备");
            if (profile == MP4_PROFILE) {
               MP4ItemScreenRenderer.releaseDeviceResources(deviceId);
            } else {
               PadItemScreenRenderer.releaseDeviceResources(deviceId);
            }
         }
      }
   }

   public static void tickHotbarVideoFrames() {
      tickHotbarHandheldVideoSessions();

      for (Entry<PlaybackSourceId, HandheldDeviceVideoState> entry : STATES.entrySet()) {
         UUID deviceId = entry.getKey().value();
         HandheldMediaDeviceProfile profile = profileFor(deviceId);
         if (profile.isDeviceAvailable(deviceId)) {
            HandheldDeviceVideoState state = entry.getValue();
            HandheldVideoSession session = state.activeSession;
            if (session != null && !session.closed.get() && session.key.equals(state.activeKey)) {
               HandheldMediaPlayback playback = profile.playback(deviceId);
               if (playback != null && session.key.playbackSessionId().equals(playback.playbackSessionId())) {
                  HandheldVideoFrameTimeline.pumpFrameForTimeline(state, session, HandheldVideoFrameTimeline.anchoredVisualMillis(deviceId, profile, playback));
               }
            }
         }
      }
   }

   private static void tickHotbarHandheldVideoSessions() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null) {
         MP4DeviceStacks.forEachHotbarAndOffhand(minecraft.player, stack -> {
            tickStackHandheldVideoSession(stack);
            return false;
         });
      }
   }

   private static void tickStackHandheldVideoSession(ItemStack stack) {
      if (stack.getItem() instanceof MP4Item) {
         UUID deviceId = MP4Item.readDeviceId(stack);
         if (deviceId != null) {
            MP4Item.State renderState = MP4Client.stateForHeldRender(stack);
            if (!renderState.videoDecodeEnabled()) {
               stop(deviceId, "等待横屏播放");
            } else {
               update(deviceId);
            }
         }
      }
   }

   private static void resolveAndStart(
      UUID deviceId, HandheldDeviceVideoState state, HandheldMediaPlayback playback, HandheldPlaybackKey key, long intentGeneration
   ) {
      CancellableTaskFuture<BiliVideoStreamResolver.ResolvedVideoStream> resolveTask = CancellableTaskFuture.submit(
         EXECUTOR, () -> resolveStream(playback, key.quality())
      );
      synchronized (state.lifecycleLock) {
         if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
            resolveTask.cancel(true);
            return;
         }

         CancellableTaskFuture<BiliVideoStreamResolver.ResolvedVideoStream> previous = state.resolveTask;
         state.resolveTask = resolveTask;
         if (previous != null && previous != resolveTask) {
            previous.cancel(true);
         }
      }

      resolveTask.whenComplete((stream, error) -> {
         synchronized (state.lifecycleLock) {
            if (state.resolveTask == resolveTask) {
               state.resolveTask = null;
            }

            if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
               return;
            }

            if (error != null) {
               state.resolvingKey = HandheldPlaybackKey.EMPTY;
               state.failedKey = key;
               state.audioOnly = !BiliVideoStreamResolver.isStoredVideoSelection(playback.rawUrl());
               state.statusText = state.audioOnly ? "纯音乐" : "视频解析失败";
               LOGGER.warn("MP4 横屏视频流解析失败: session={} raw='{}' reason={}", new Object[]{playback.sessionId(), playback.rawUrl(), error.toString()});
               return;
            }

            state.audioOnly = false;
            state.subtitleRecord = stream.subtitleRecord();
            state.currentSubtitle = state.subtitleRecord != null ? "" : "无可用字幕";
            state.sourceWidth = stream.sourceWidth();
            state.sourceHeight = stream.sourceHeight();
            logResolvedStreamIfPad(playback, stream);
         }

         HandheldMediaPlayback currentPlayback = profileFor(deviceId).playback(deviceId);
         if (!isCurrentPlayback(currentPlayback, key)) {
            synchronized (state.lifecycleLock) {
               if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                  state.resolvingKey = HandheldPlaybackKey.EMPTY;
               }
            }
         } else {
            startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
         }
      });
   }

   private static boolean isCurrentIntent(UUID deviceId, HandheldDeviceVideoState state, HandheldPlaybackKey key, long intentGeneration) {
      return deviceId != null
         && state != null
         && STATES.get(PlaybackSourceId.of(deviceId)) == state
         && state.intentGeneration == intentGeneration
         && key.equals(state.activeKey)
         && key.equals(state.resolvingKey);
   }

   private static boolean isCurrentPlayback(HandheldMediaPlayback playback, HandheldPlaybackKey key) {
      return playback != null
         && key != null
         && key.playbackSessionId().equals(playback.playbackSessionId())
         && key.rawUrl().equals(playback.rawUrl())
         && playback.timeline() != null
         && playback.timeline().mediaMillis() >= 0L;
   }

   private static void logResolvedStreamIfPad(HandheldMediaPlayback playback, BiliVideoStreamResolver.ResolvedVideoStream stream) {
      if (PAD_VIDEO_DEBUG_LOG && PadClientMediaSessionIds.isPadSession(playback.sessionId())) {
         LOGGER.info(
            "Pad video stream resolved: session={} requestedRaw='{}' quality={} codec={} source={}x{} fps={} host={} title='{}'",
            new Object[]{
               playback.sessionId(),
               playback.rawUrl(),
               stream.quality(),
               stream.codecId(),
               stream.sourceWidth(),
               stream.sourceHeight(),
               stream.fps(),
               hostOf(stream.url()),
               stream.title()
            }
         );
      }
   }

   private static String hostOf(String url) {
      try {
         return URI.create(url).getHost();
      } catch (RuntimeException var2) {
         return "unknown";
      }
   }

   private static BiliVideoStreamResolver.ResolvedVideoStream resolveStream(HandheldMediaPlayback playback, int qualityCeiling) {
      try {
         // 阶段 2：本地视频直接给出重封装后的回环地址（本方法跑在 mp4-handheld-video 线程上，可以等重封装）
         return LocalVideoSources.isLocalVideoSource(playback.rawUrl())
            ? LocalVideoSources.resolveLocalStream(playback.rawUrl(), 0L)
            : BiliVideoStreamResolver.resolveWithSubtitle(playback.rawUrl(), qualityCeiling, playback.title(), playback.allowAiSubtitle());
      } catch (Exception var3) {
         throw new IllegalStateException(var3);
      }
   }

   private static void startDecoder(
      UUID deviceId,
      HandheldDeviceVideoState state,
      HandheldMediaPlayback playback,
      HandheldPlaybackKey key,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      long intentGeneration
   ) {
      if (!ClientMemoryProtection.allowMediaStart()) {
         synchronized (state.lifecycleLock) {
            if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
               state.resolvingKey = HandheldPlaybackKey.EMPTY;
               state.statusText = "视频内存保护冷却中";
            }
         }
      } else {
         long elapsedMillis = Math.max(0L, playback.timeline().mediaMillis());
         long totalMillis = Math.max(0L, playback.timeline().totalMillis());
         HandheldVideoSession session;
         synchronized (state.replacementGate) {
            label92: {
               synchronized (state.lifecycleLock) {
                  if (!isCurrentIntent(deviceId, state, key, intentGeneration)) {
                     return;
                  }

                  HandheldReplacementGate.Signals previousSignals = state.replacementGate.snapshot();
                  HandheldDecoderAdmissionPolicy.Decision admission = HandheldDecoderAdmissionPolicy.decide(
                     previousSignals.decodeExit(), previousSignals.nativeTermination()
                  );
                  if (admission == HandheldDecoderAdmissionPolicy.Decision.FAIL_CLOSED) {
                     state.resolvingKey = HandheldPlaybackKey.EMPTY;
                     state.failedKey = key;
                     state.statusText = "旧视频解码器关闭失败";
                     LOGGER.error("手持视频旧 decoder 退出信号异常，拒绝打开新会话: session={}", key.sessionId());
                     return;
                  }

                  if (admission != HandheldDecoderAdmissionPolicy.Decision.WAIT) {
                     session = new HandheldVideoSession(state, key, elapsedMillis, stream.candidates());
                     state.activeSession = session;
                     state.replacementGate.install(key.sessionId(), session.decodeExit, session.physicalTermination);
                     state.resolvingKey = HandheldPlaybackKey.EMPTY;
                     state.statusText = "视频缓冲中...";
                     break label92;
                  }

                  state.statusText = "等待旧视频解码器退出...";
                  waitForPreviousDecoderExit(deviceId, state, key, stream, intentGeneration, previousSignals);
               }

               return;
            }
         }

         try {
            CompletableFuture.runAsync(() -> {
               try {
                  decodeLoop(deviceId, state, session, stream, elapsedMillis, totalMillis);
               } catch (IOException var12) {
                  throw new IllegalStateException(var12);
               } finally {
                  session.completeDecodeTaskExit();
               }
            }, EXECUTOR).whenComplete((ignored, error) -> completeDecoderTask(state, session, key, stream, error));
         } catch (RuntimeException var19) {
            session.completeDecodeTaskExit();
            completeDecoderTask(state, session, key, stream, var19);
         }
      }
   }

   private static void waitForPreviousDecoderExit(
      UUID deviceId,
      HandheldDeviceVideoState state,
      HandheldPlaybackKey key,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      long intentGeneration,
      HandheldReplacementGate.Signals previousSignals
   ) {
      long now = System.nanoTime();
      long closeOperation = VideoCloseDiagnostics.global()
         .begin(previousSignals.sessionId(), EnumSet.of(VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED), now);
      previousSignals.decodeExit().whenComplete((ignored, error) -> {
         if (error == null) {
            VideoCloseDiagnostics.global().complete(closeOperation, VideoCloseDiagnostics.Phase.DECODE_THREAD_EXITED, System.nanoTime());
         }
      });
      previousSignals.nativeTermination().whenComplete((ignored, error) -> {
         if (error == null) {
            VideoCloseDiagnostics.global().complete(closeOperation, VideoCloseDiagnostics.Phase.NATIVE_TERMINATED, System.nanoTime());
         }
      });
      CompletableFuture<Void> convergence = HandheldDecoderAdmissionPolicy.convergence(previousSignals.decodeExit(), previousSignals.nativeTermination());
      HandheldReplacementWait waitDecision = new HandheldReplacementWait();
      convergence.orTimeout(3000L, TimeUnit.MILLISECONDS)
         .whenComplete(
            (ignored, error) -> resumeDecoderAfterPreviousExit(
               deviceId, state, key, stream, intentGeneration, previousSignals, closeOperation, waitDecision, error
            )
         );
   }

   private static void resumeDecoderAfterPreviousExit(
      UUID deviceId,
      HandheldDeviceVideoState state,
      HandheldPlaybackKey key,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      long intentGeneration,
      HandheldReplacementGate.Signals previousSignals,
      long closeOperation,
      HandheldReplacementWait waitDecision,
      Throwable convergenceError
   ) {
      HandheldDecoderAdmissionPolicy.Decision finalDecision = HandheldDecoderAdmissionPolicy.decide(
         previousSignals.decodeExit(), previousSignals.nativeTermination()
      );
      HandheldReplacementWait.Outcome outcome = waitDecision.complete(convergenceError, finalDecision);
      if (outcome != HandheldReplacementWait.Outcome.ALREADY_DECIDED) {
         if (outcome == HandheldReplacementWait.Outcome.FAIL_CLOSED) {
            synchronized (state.lifecycleLock) {
               if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                  state.resolvingKey = HandheldPlaybackKey.EMPTY;
                  state.failedKey = key;
                  state.statusText = "旧视频解码器关闭失败";
               }
            }

            VideoZombieCloseSupervisor.global()
               .track(
                  previousSignals.sessionId(),
                  HANDHELD_CLOSE_SEQUENCE.incrementAndGet(),
                  CompletableFuture.completedFuture(null),
                  previousSignals.nativeTermination(),
                  previousSignals.decodeExit()
               );
            LOGGER.error("手持视频旧 decoder 未正常收敛，拒绝恢复新会话: session={} decision={}", new Object[]{key.sessionId(), finalDecision, convergenceError});
         } else {
            HandheldMediaPlayback currentPlayback = profileFor(deviceId).playback(deviceId);
            if (!isCurrentPlayback(currentPlayback, key)) {
               synchronized (state.lifecycleLock) {
                  if (isCurrentIntent(deviceId, state, key, intentGeneration)) {
                     state.resolvingKey = HandheldPlaybackKey.EMPTY;
                  }
               }
            } else {
               startDecoder(deviceId, state, currentPlayback, key, stream, intentGeneration);
            }
         }
      }
   }

   private static void completeDecoderTask(
      HandheldDeviceVideoState state,
      HandheldVideoSession session,
      HandheldPlaybackKey key,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      Throwable error
   ) {
      if (containsOutOfMemory(error)) {
         ClientMediaLifecycleHandler.tripMemoryProtection("handheld video decoder allocation failed");
      }

      synchronized (state.lifecycleLock) {
         if (state.activeSession == session) {
            state.activeSession = null;
         }

         if (error != null && !session.closed.get()) {
            state.failedKey = key;
            state.statusText = session.fallbackReason.isBlank() ? "视频播放失败" : "视频播放失败 · " + VideoFallbackReason.userLabel(session.fallbackReason);
            LOGGER.warn(
               "MP4 横屏视频解码失败: session={} stream={} quality={} reason={}", new Object[]{key.sessionId(), stream.url(), stream.quality(), error.toString()}
            );
         }
      }
   }

   private static boolean containsOutOfMemory(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof OutOfMemoryError) {
            return true;
         }
      }

      return false;
   }

   private static void decodeLoop(
      UUID deviceId,
      HandheldDeviceVideoState state,
      HandheldVideoSession session,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      long elapsedMillis,
      long totalMillis
   ) throws IOException {
      IOException lastStartupFailure = null;
      boolean forceH264 = false;

      for (BiliVideoStreamResolver.VideoCandidate candidate : stream.candidates()) {
         if (session.closed.get() || !session.key.equals(state.activeKey)) {
            return;
         }

         if (!forceH264 || candidate.codecId() == 7) {
            BiliVideoStreamResolver.ResolvedVideoStream selected = stream.withCandidate(candidate);

            try {
               decodeCandidate(deviceId, state, session, selected, elapsedMillis, totalMillis);
               return;
            } catch (SustainedPerformanceFallbackException var14) {
               forceH264 = true;
               session.lockPerformanceFallback(var14.reason);
               HandheldVideoFrameTimeline.clearFrameQueue(state);
               lastStartupFailure = var14;
               LOGGER.warn(
                  "MP4 横屏 AV1 持续性能回退并锁定 H.264: session={} reason={} quality={} backend={}",
                  new Object[]{session.key.sessionId(), var14.reason, selected.quality(), session.actualBackend}
               );
            } catch (MP4HandheldVideoClient.StartupDecodeException var15) {
               lastStartupFailure = var15;
               if (selected.codecId() == 13) {
                  session.fallbackReason = VideoFallbackReason.classifyAv1StartupFailure(var15, session.h264CandidateAvailable);
               }

               LOGGER.warn(
                  "MP4 横屏视频候选首帧失败，尝试下一候选: session={} quality={} codec={} source={}x{} reason={}",
                  new Object[]{
                     session.key.sessionId(), selected.quality(), selected.codecId(), selected.sourceWidth(), selected.sourceHeight(), var15.getMessage()
                  }
               );
            }
         }
      }

      throw lastStartupFailure != null ? lastStartupFailure : new IOException("没有可用的视频解码候选");
   }

   private static void decodeCandidate(
      UUID deviceId,
      HandheldDeviceVideoState state,
      HandheldVideoSession session,
      BiliVideoStreamResolver.ResolvedVideoStream stream,
      long elapsedMillis,
      long totalMillis
   ) throws IOException {
      LOGGER.debug(
         "MP4 横屏视频启动: session={} quality={} source={}x{} fps={} offset={}ms title='{}'",
         new Object[]{session.key.sessionId(), stream.quality(), stream.sourceWidth(), stream.sourceHeight(), stream.fps(), elapsedMillis, stream.title()}
      );
      MP4HandheldVideoClient.DecodeSize decodeSize = HandheldVideoFrameTimeline.chooseDecodeSize(stream.sourceWidth(), stream.sourceHeight());
      HandheldVideoFrameTimeline.maybeWarnHighResolution(decodeSize);
      LOGGER.debug(
         "MP4 横屏视频解码尺寸: session={} source={}x{} target={}x{}",
         new Object[]{session.key.sessionId(), stream.sourceWidth(), stream.sourceHeight(), decodeSize.width(), decodeSize.height()}
      );
      Fmp4NativeVideoDecoder.OutputFormat outputFormat = session.key.rgbaFallback()
         ? Fmp4NativeVideoDecoder.OutputFormat.RGBA
         : Fmp4NativeVideoDecoder.OutputFormat.NV12;
      LOGGER.debug(
         "MP4 横屏视频输出格式: session={} format={} irisShaderpackFallback={}", new Object[]{session.key.sessionId(), outputFormat, session.key.rgbaFallback()}
      );
      boolean firstFrameAccepted = false;
      Fmp4NativeVideoDecoder decoder = null;

      try {
         decoder = HandheldVideoDecoderFactory.open(session, stream, decodeSize, outputFormat, elapsedMillis, totalMillis);
         if (!session.attachDecoder(decoder)) {
            return;
         }

         long displayedFrames = 0L;

         while (true) {
            if (session.closed.get() || !session.key.equals(state.activeKey)) {
               return;
            }

            if (!HandheldOffscreenVideoPolicy.waitWhileOffscreen(deviceId, state, session)) {
               return;
            }

            boolean boundedAv1Probe = !firstFrameAccepted && HandheldVideoDecoderFactory.requiresBoundedAv1FirstFrameProbe(stream);
            Fmp4NativeVideoDecoder.DecodedFrame decoded = boundedAv1Probe ? decoder.getNextDecodedFrameWithAv1FirstFrameProbe() : decoder.getNextDecodedFrame();
            if (decoded == null) {
               if (firstFrameAccepted && session.performanceFallbackRequested.get()) {
                  throw new SustainedPerformanceFallbackException(session.pendingFallbackReason);
               }

               if (!firstFrameAccepted) {
                  throw new MP4HandheldVideoClient.StartupDecodeException("候选在输出首帧前结束");
               }

               synchronized (state.lifecycleLock) {
                  if (state.activeSession == session && session.key.equals(state.activeKey)) {
                     state.endedKey = session.key;
                     state.statusText = "视频播放结束";
                  }
                  break;
               }
            }

            int requiredBytes = HandheldVideoFrameTimeline.requiredFrameBytes(decoded.format(), decodeSize.width(), decodeSize.height());
            if (!HandheldVideoFrameTimeline.hasFrameBytes(decoded, requiredBytes)) {
               try {
                  if (boundedAv1Probe) {
                     decoder.rejectAv1FirstFrameProbeFrame(decoded);
                  }
               } finally {
                  decoded.close();
               }
            } else {
               long framePtsNanos = HandheldVideoFrameTimeline.framePtsOrFallback(decoded.ptsNanos(), displayedFrames, stream.fps());
               if (!firstFrameAccepted && HandheldVideoFrameTimeline.shouldDropStaleStartupFrame(deviceId, state, session, framePtsNanos)) {
                  try {
                     if (boundedAv1Probe) {
                        decoder.rejectAv1FirstFrameProbeFrame(decoded);
                     }
                  } finally {
                     decoded.close();
                  }

                  displayedFrames++;
               } else {
                  if (!HandheldVideoFrameTimeline.waitForDecodeLead(deviceId, state, session, framePtsNanos)) {
                     try {
                        if (boundedAv1Probe) {
                           decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }

                        return;
                     } finally {
                        decoded.close();
                     }
                  }

                  HandheldVideoFrame frame = HandheldVideoFrame.retain(decoded, requiredBytes, decodeSize.width(), decodeSize.height(), framePtsNanos);
                  if (!HandheldVideoFrameTimeline.offerFrame(state, session, frame)) {
                     try {
                        if (boundedAv1Probe) {
                           decoder.rejectAv1FirstFrameProbeFrame(decoded);
                        }

                        return;
                     } finally {
                        frame.close();
                     }
                  }

                  if (!firstFrameAccepted) {
                     if (boundedAv1Probe) {
                        try {
                           decoder.commitAv1FirstFrameProbe(decoded);
                        } catch (IOException var196) {
                           HandheldVideoFrameTimeline.clearFrameQueue(state);
                           throw var196;
                        }
                     }

                     firstFrameAccepted = true;
                     session.startPerformanceObservation(stream, decoder, System.nanoTime());
                     session.performanceMonitor.recordDecodedFrame(HandheldVideoDecoderFactory.preferredDecodeSampleNanos(decoded));
                     state.statusText = playingStatus(session, stream, decoder.actualHwaccel());
                     LOGGER.debug(
                        "MP4 横屏视频首帧已提交: session={} target={}x{} pts={}ms offset={}ms backend={}",
                        new Object[]{
                           session.key.sessionId(), decodeSize.width(), decodeSize.height(), framePtsNanos / 1000000L, elapsedMillis, decoder.actualHwaccel()
                        }
                     );
                  } else {
                     session.performanceMonitor.recordDecodedFrame(HandheldVideoDecoderFactory.preferredDecodeSampleNanos(decoded));
                  }

                  displayedFrames++;
                  if (session.evaluatePerformance(state, System.nanoTime())) {
                     throw new SustainedPerformanceFallbackException(session.pendingFallbackReason);
                  }
               }
            }
         }
      } catch (IOException var203) {
         if (firstFrameAccepted
            && session.performanceFallbackRequested.get()
            && !(var203 instanceof HandheldCandidateCloseTimeoutException)
            && !(var203 instanceof HandheldCandidateCloseFailureException)) {
            throw new SustainedPerformanceFallbackException(session.pendingFallbackReason, var203);
         }

         if (!firstFrameAccepted) {
            if (!(var203 instanceof HandheldCandidateCloseTimeoutException) && !(var203 instanceof HandheldCandidateCloseFailureException)) {
               throw var203 instanceof MP4HandheldVideoClient.StartupDecodeException startup
                  ? startup
                  : new MP4HandheldVideoClient.StartupDecodeException(var203.getMessage(), var203);
            }

            throw var203;
         }

         throw var203;
      } catch (RuntimeException var204) {
         if (!firstFrameAccepted && !session.closed.get()) {
            throw new MP4HandheldVideoClient.StartupDecodeException(var204.getMessage(), var204);
         }

         throw var204;
      } finally {
         try {
            if (decoder != null) {
               HandheldVideoDecoderFactory.closeCandidate(decoder, firstFrameAccepted, stream, session, session.performanceFallbackRequested.get());
            }
         } finally {
            session.detachDecoder(decoder);
         }
      }
   }

   private static String playingStatus(HandheldVideoSession session, BiliVideoStreamResolver.ResolvedVideoStream stream, String backend) {
      return playingStatus(session, stream.quality(), stream.codecId(), backend);
   }

   static String playingStatus(HandheldVideoSession session, int actualQuality, int codecId, String backend) {
      String codec = codecId == 13 ? "AV1" : (codecId == 7 ? "H.264" : "codec-" + codecId);
      String actual = backend != null && !backend.isBlank() ? backend : "unknown";
      String fallback = session.fallbackReason.isBlank() ? "" : " · 降级=" + VideoFallbackReason.userLabel(session.fallbackReason);
      return "视频播放中 · 请求Q" + session.key.quality() + " · 实际Q" + actualQuality + " " + codec + " · " + actual + fallback;
   }

   static void cancelResolveTaskLocked(HandheldDeviceVideoState state) {
      CancellableTaskFuture<BiliVideoStreamResolver.ResolvedVideoStream> resolveTask = state.resolveTask;
      state.resolveTask = null;
      if (resolveTask != null) {
         resolveTask.cancel(true);
      }
   }

   private static HandheldDeviceVideoState state(UUID deviceId) {
      if (deviceId == null) {
         throw new IllegalArgumentException("MP4 video state requires a device id");
      } else {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         return STATES.computeIfAbsent(
            sourceId, ignored -> new HandheldDeviceVideoState(REPLACEMENT_GATES.computeIfAbsent(sourceId, key -> new HandheldReplacementGate()))
         );
      }
   }

   private static HandheldDeviceVideoState stateOrNull(UUID deviceId) {
      return deviceId != null ? STATES.get(PlaybackSourceId.of(deviceId)) : null;
   }

   public static boolean isDeviceInHotbar(UUID deviceId) {
      if (deviceId == null) {
         return true;
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         return minecraft.player == null
            ? false
            : MP4DeviceStacks.forEachHotbarAndOffhand(minecraft.player, stack -> deviceId.equals(MP4Item.readDeviceId(stack)));
      }
   }

   public static boolean isMp4DeviceProfile(UUID deviceId) {
      return profileFor(deviceId) == MP4_PROFILE;
   }

   static HandheldMediaDeviceProfile profileFor(UUID deviceId) {
      return profileFor(deviceId, null);
   }

   static HandheldMediaDeviceProfile profileFor(UUID deviceId, HandheldMediaDeviceProfile fallback) {
      HandheldMediaDeviceProfile profile = deviceId != null ? PROFILES.get(PlaybackSourceId.of(deviceId)) : null;
      return (HandheldMediaDeviceProfile)(profile != null ? profile : (fallback != null ? fallback : MP4_PROFILE));
   }

   private static String currentLineAt(Int2ObjectSortedMap<String> lines, int tick) {
      if (lines != null && !lines.isEmpty() && tick >= 0) {
         int key = lines.firstIntKey();

         for (int candidate : lines.keySet().toIntArray()) {
            if (candidate > tick) {
               break;
            }

            key = candidate;
         }

         String line = (String)lines.get(key);
         return line != null ? line : "";
      } else {
         return "";
      }
   }

   public record DecodeSize(int width, int height) {
   }

   private static final class StartupDecodeException extends IOException {
      private StartupDecodeException(String message) {
         super(message);
      }

      private StartupDecodeException(String message, Throwable cause) {
         super(message, cause);
      }
   }
}
