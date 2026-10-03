package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackTracker;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoResolveAdmissionPolicy;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioNativeCloseDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.audio.OpenALSpatialAudio;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

public final class ModernTurntableVideoClient {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final VideoClientProperties.Turntable VIDEO_PROPERTIES = VideoClientProperties.turntable();
   private static final int DEFAULT_PREFERRED_QUALITY = VideoFeatureFlags.advancedInt("bili.video.turntable.quality", 116);
   private static final int DEFAULT_FPS = VideoFeatureFlags.advancedInt("bili.video.turntable.default_fps", 60);
   private static final boolean PREFER_NATIVE = VideoFeatureFlags.advancedBoolean("bili.video.projector.native", true);
   private static final boolean LOG_SYNC_DECISIONS = VideoFeatureFlags.advancedBoolean("bili.video.turntable.log_sync_decisions", false);
   private static final String DECODER_OVERRIDE = VideoFeatureFlags.advancedString("ncpb.video.ffmpeg.decoder", "").trim();
   private static final ExecutorService VIDEO_RESOLVE_EXECUTOR = Executors.newFixedThreadPool(
      VIDEO_PROPERTIES.resolveThreads(), NetMusicThreadFactory.daemon("BiliVideoResolve")
   );
   private static final Set<PlaybackSessionId> ACTIVE_SESSION_IDS = ConcurrentHashMap.newKeySet();
   private static final ConcurrentHashMap<BlockPos, PlaybackSessionId> ACTIVE_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<BlockPos, PlaybackSessionId> LATEST_SESSION_BY_TURNTABLE = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<BlockPos, Set<BlockPos>> CONTROL_CONSOLE_CONSUMERS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<BlockPos, Integer> CONTROL_CONSOLE_QUALITY = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlaybackSessionId, Integer> ACTIVE_QUALITY_CEILING_BY_SESSION = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlaybackSessionId, ResolveGeneration> ACTIVE_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlaybackSessionId, VideoResolveRequestOwner<BlockPos>> PENDING_REQUEST_BY_SESSION = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<PlaybackSessionId, String> LAST_DECISION_BY_SESSION = new ConcurrentHashMap<>();
   private static final AtomicReference<ResolveGeneration> REQUEST_SEQUENCE = new AtomicReference<>(ResolveGeneration.initial());
   private static final AtomicLong STALE_RESOLVE_DROPS = new AtomicLong();
   private static final AtomicLong NO_CONSUMER_RESOLVE_DROPS = new AtomicLong();

   private ModernTurntableVideoClient() {
   }

   public static void forgetSession(String sessionId) {
      PlaybackSessionId key = sessionKey(sessionId);
      if (key != null) {
         String normalizedSessionId = key.value();
         VideoBillboardPreview.clearPendingLoading(normalizedSessionId);
         ACTIVE_SESSION_IDS.remove(key);
         ACTIVE_QUALITY_CEILING_BY_SESSION.remove(key);
         ACTIVE_REQUEST_BY_SESSION.remove(key);
         cancelPendingRequest(key);
         LAST_DECISION_BY_SESSION.remove(key);
         ACTIVE_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> key.equals(entry.getValue()));
         LATEST_SESSION_BY_TURNTABLE.entrySet().removeIf(entry -> key.equals(entry.getValue()));
      }
   }

   public static void clear() {
      PENDING_REQUEST_BY_SESSION.forEach((sessionId, pending) -> {
         if (PENDING_REQUEST_BY_SESSION.remove(sessionId, pending)) {
            pending.cancel();
         }
      });
      ACTIVE_SESSION_IDS.clear();
      ACTIVE_SESSION_BY_TURNTABLE.clear();
      LATEST_SESSION_BY_TURNTABLE.clear();
      ACTIVE_QUALITY_CEILING_BY_SESSION.clear();
      ACTIVE_REQUEST_BY_SESSION.clear();
      LAST_DECISION_BY_SESSION.clear();
      CONTROL_CONSOLE_CONSUMERS.clear();
      CONTROL_CONSOLE_QUALITY.clear();
      STALE_RESOLVE_DROPS.set(0L);
      NO_CONSUMER_RESOLVE_DROPS.set(0L);
   }

   public static void registerControlConsoleConsumer(BlockPos turntablePos, BlockPos consolePos, int qualityCeiling) {
      if (turntablePos != null && consolePos != null) {
         CONTROL_CONSOLE_CONSUMERS.computeIfAbsent(turntablePos.immutable(), ignored -> ConcurrentHashMap.newKeySet()).add(consolePos.immutable());
         CONTROL_CONSOLE_QUALITY.put(consolePos.immutable(), qualityCeiling);
      }
   }

   public static void unregisterControlConsoleConsumer(BlockPos consolePos) {
      if (consolePos != null) {
         CONTROL_CONSOLE_QUALITY.remove(consolePos);
         List<BlockPos> affectedTurntables = new ArrayList<>();
         CONTROL_CONSOLE_CONSUMERS.forEach((turntablePos, consumers) -> {
            if (consumers.remove(consolePos)) {
               affectedTurntables.add(turntablePos);
            }
         });
         CONTROL_CONSOLE_CONSUMERS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
         affectedTurntables.forEach(ModernTurntableVideoClient::invalidateIfNoLiveConsumer);
      }
   }

   public static void syncFromTurntableIfPossible(ModernTurntableBlockEntity turntable) {
      if (turntable != null && turntable.getLevel() != null && turntable.isPlaying()) {
         String rawUrl = turntable.getRawUrl();
         if (rawUrl != null && !rawUrl.isBlank()) {
            PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
            if (sync.hasSession()) {
               syncFromPlayback(rawUrl, turntable.getBlockPos(), sync);
            }
         }
      }
   }

   public static void syncFromTurntableForProjectorIfPossible(ModernTurntableBlockEntity turntable, VideoProjectorBlockEntity projector) {
      if (turntable != null && projector != null && turntable.getLevel() != null && turntable.isPlaying()) {
         String rawUrl = turntable.getRawUrl();
         if (rawUrl != null && !rawUrl.isBlank()) {
            PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
            if (sync.hasSession()) {
               syncFromPlayback(rawUrl, turntable.getBlockPos(), sync, List.of(projector));
            }
         }
      }
   }

   public static void refreshProjector(BlockPos projectorPos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (projectorPos != null && minecraft.level != null) {
         Runnable refresh = () -> refreshProjectorOnClientThread(projectorPos.immutable());
         if (minecraft.isSameThread()) {
            refresh.run();
         } else {
            minecraft.execute(refresh);
         }
      }
   }

   private static void refreshProjectorOnClientThread(BlockPos projectorPos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null) {
         if (minecraft.level.getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector) {
            BlockPos turntablePos = projector.getLinkedTurntablePos();
            if (turntablePos == null) {
               VideoBillboardPreview.stopIfProjector(projectorPos);
            } else {
               BlockEntity turntableBe = minecraft.level.getBlockEntity(turntablePos);
               if (turntableBe instanceof LiveStreamerBlockEntity) {
                  VideoBillboardPreview.stopIfProjector(projectorPos);
               } else if (turntableBe instanceof ModernTurntableBlockEntity turntable && turntable.isPlaying()) {
                  PlaybackSync.Metadata sync = turntable.getPlaybackSyncMetadata();
                  if (!sync.hasSession()) {
                     VideoBillboardPreview.stopIfProjector(projectorPos);
                  } else {
                     VideoBillboardPreview.stopIfSession(sync.sessionId());
                     syncFromPlayback(turntable.getRawUrl(), turntablePos, sync);
                  }
               } else {
                  VideoBillboardPreview.stopIfProjector(projectorPos);
               }
            }
         } else {
            VideoBillboardPreview.stopIfProjector(projectorPos);
         }
      }
   }

   public static void syncFromPlayback(String rawUrl, BlockPos turntablePos, PlaybackSync.Metadata sync) {
      syncFromPlayback(rawUrl, turntablePos, sync, null);
   }

   private static void syncFromPlayback(String rawUrl, BlockPos turntablePos, PlaybackSync.Metadata sync, List<VideoProjectorBlockEntity> explicitProjectors) {
      if (VIDEO_PROPERTIES.enabled() && sync != null && sync.hasSession()) {
         PlaybackSessionId playbackSessionId = sync.playbackSessionId().orElse(null);
         if (playbackSessionId != null) {
            String cleanRawUrl = PlaybackSync.strip(rawUrl);
            BiliApiClient.VideoSelection selection = BiliVideoStreamResolver.selectionOrNull(cleanRawUrl);
            if (selection == null) {
               LOGGER.debug("现代唱片机视频同步跳过: 无法识别 B站视频 URL: rawUrl={}", cleanRawUrl);
            } else {
               String sessionId = playbackSessionId.value();
               BlockPos immutableTurntablePos = turntablePos != null ? turntablePos.immutable() : null;
               if (immutableTurntablePos != null) {
                  LATEST_SESSION_BY_TURNTABLE.put(immutableTurntablePos, playbackSessionId);
               }

               List<VideoProjectorBlockEntity> projectors = explicitProjectors != null ? explicitProjectors : findLinkedVideoProjectors(turntablePos);
               List<BlockPos> consoleConsumers = CONTROL_CONSOLE_CONSUMERS.getOrDefault(turntablePos != null ? turntablePos : BlockPos.ZERO, Set.of())
                  .stream()
                  .toList();
               boolean holographicConsumer = HolographicGlassesClient.handlesTurntable(turntablePos);
               if (projectors.isEmpty() && consoleConsumers.isEmpty() && !holographicConsumer) {
                  if (VideoBillboardPreview.hasSessionForTurntable(turntablePos, sessionId)
                     && (VideoBillboardPreview.isSessionRunning(sessionId) || VideoBillboardPreview.hasTerminalFailure(sessionId))) {
                     ACTIVE_SESSION_IDS.add(playbackSessionId);
                     rememberActiveSession(immutableTurntablePos, sessionId);
                     logDecision(
                        sessionId,
                        "reuse-simulated-projector",
                        turntablePos,
                        sync.elapsedMillis(),
                        0,
                        0,
                        0L,
                        VideoBillboardPreview.hasTerminalFailure(sessionId)
                           ? "failed session is retained on a BER-backed simulated projector"
                           : "running session is retained for a BER-backed simulated projector"
                     );
                  } else {
                     logDecision(sessionId, "stop-no-projector", turntablePos, sync.elapsedMillis(), 0, 0, 0L, "no linked video projector");
                     LOGGER.debug("现代唱片机视频同步跳过: 没有实时视频消费者: session={} turntable={}", sessionId, turntablePos);
                     VideoBillboardPreview.stopIfSession(sessionId);
                     forgetSession(sessionId);
                  }
               } else {
                  List<BlockPos> projectorPositions = projectors.stream().map(projector -> projector.getBlockPos().immutable()).toList();
                  List<BlockPos> consumerPositions = new ArrayList<>(projectorPositions);
                  consumerPositions.addAll(consoleConsumers);
                  long elapsedMillis = Math.max(0L, sync.elapsedMillis());
                  int qualityCeiling = qualityCeiling(projectors, consoleConsumers);
                  if (VideoBillboardPreview.hasTerminalFailure(sessionId)) {
                     VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
                     ACTIVE_SESSION_IDS.add(playbackSessionId);
                     rememberActiveSession(immutableTurntablePos, sessionId);
                     logDecision(
                        sessionId,
                        "hold-network-failure",
                        turntablePos,
                        elapsedMillis,
                        qualityCeiling,
                        projectorPositions.size(),
                        0L,
                        "same session is held at the error placeholder until a new session or explicit retry"
                     );
                  } else {
                     PlaybackSessionId existingForTurntableKey = immutableTurntablePos != null ? ACTIVE_SESSION_BY_TURNTABLE.get(immutableTurntablePos) : null;
                     if (existingForTurntableKey != null && VideoBillboardPreview.isSessionRunning(existingForTurntableKey.value())) {
                        String existingForTurntable = existingForTurntableKey.value();
                        if (existingForTurntableKey.equals(playbackSessionId)) {
                           VideoBillboardPreview.updateSessionProjectors(existingForTurntable, consumerPositions);
                           if (VideoBillboardPreview.isSessionWaitingForFirstFrame(existingForTurntable)) {
                              ACTIVE_SESSION_IDS.add(playbackSessionId);
                              rememberActiveSession(immutableTurntablePos, sessionId);
                              logDecision(
                                 sessionId,
                                 "reuse-wait-first-frame",
                                 turntablePos,
                                 elapsedMillis,
                                 qualityCeiling,
                                 projectorPositions.size(),
                                 0L,
                                 "same session already decoding"
                              );
                              return;
                           }

                           if (isSessionRunningAtQualityCeiling(existingForTurntable, qualityCeiling)
                              && VideoBillboardPreview.canSessionChaseToOffset(existingForTurntable, elapsedMillis)) {
                              ACTIVE_SESSION_IDS.add(playbackSessionId);
                              rememberActiveSession(immutableTurntablePos, sessionId);
                              logDecision(
                                 sessionId,
                                 "reuse-chase",
                                 turntablePos,
                                 elapsedMillis,
                                 qualityCeiling,
                                 projectorPositions.size(),
                                 0L,
                                 "same session will chase target inside decoder buffer/window"
                              );
                              return;
                           }

                           logDecision(
                              sessionId,
                              "restart-params-changed",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "same session target is outside chase window or quality ceiling changed"
                           );
                           VideoBillboardPreview.stopIfSession(sessionId);
                           markSessionRestarting(sessionId);
                        } else {
                           logDecision(
                              sessionId,
                              "switch-session",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "oldSession=" + existingForTurntable
                           );
                           VideoBillboardPreview.stopIfSession(existingForTurntable);
                           forgetSession(existingForTurntable);
                        }
                     }

                     if (VideoBillboardPreview.isSessionRunning(sessionId)) {
                        VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
                        if (VideoBillboardPreview.isSessionWaitingForFirstFrame(sessionId)) {
                           ACTIVE_SESSION_IDS.add(playbackSessionId);
                           rememberActiveSession(immutableTurntablePos, sessionId);
                           logDecision(
                              sessionId,
                              "reuse-wait-first-frame",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "running session has not produced first frame yet"
                           );
                           return;
                        }

                        if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                           && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
                           ACTIVE_SESSION_IDS.add(playbackSessionId);
                           rememberActiveSession(immutableTurntablePos, sessionId);
                           logDecision(
                              sessionId,
                              "reuse-chase",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "running session will chase target inside decoder buffer/window"
                           );
                           return;
                        }

                        logDecision(
                           sessionId,
                           "restart-running",
                           turntablePos,
                           elapsedMillis,
                           qualityCeiling,
                           projectorPositions.size(),
                           0L,
                           "running session target is outside chase window or quality ceiling changed"
                        );
                        VideoBillboardPreview.stopIfSession(sessionId);
                        markSessionRestarting(sessionId);
                     }

                     if (!ACTIVE_SESSION_IDS.add(playbackSessionId)) {
                        if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling)
                           && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)) {
                           rememberActiveSession(immutableTurntablePos, sessionId);
                           logDecision(
                              sessionId,
                              "reuse-chase",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "active marker session can chase target"
                           );
                           return;
                        }

                        if (!VideoBillboardPreview.isSessionRunning(sessionId)) {
                           VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.get(playbackSessionId);
                           if (pending != null && pending.matches(elapsedMillis, qualityCeiling)) {
                              VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
                              rememberActiveSession(immutableTurntablePos, sessionId);
                              logDecision(
                                 sessionId,
                                 "reuse-pending",
                                 turntablePos,
                                 elapsedMillis,
                                 qualityCeiling,
                                 projectorPositions.size(),
                                 pending.requestGeneration().value(),
                                 "stream resolve already in flight"
                              );
                              return;
                           }

                           logDecision(
                              sessionId,
                              "replace-pending",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              pending != null ? pending.requestGeneration().value() : 0L,
                              pending != null ? "pending quality ceiling changed" : "active marker without renderer"
                           );
                           markSessionRestarting(sessionId);
                           ACTIVE_SESSION_IDS.add(playbackSessionId);
                           rememberActiveSession(immutableTurntablePos, sessionId);
                        } else {
                           logDecision(
                              sessionId,
                              "restart-active-marker",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              0L,
                              "active marker conflicts with renderer state"
                           );
                           VideoBillboardPreview.stopIfSession(sessionId);
                           markSessionRestarting(sessionId);
                           ACTIVE_SESSION_IDS.add(playbackSessionId);
                        }
                     }

                     if (isSessionRunningAtQualityCeiling(sessionId, qualityCeiling) && VideoBillboardPreview.canSessionChaseToOffset(sessionId, elapsedMillis)
                        )
                      {
                        rememberActiveSession(immutableTurntablePos, sessionId);
                        logDecision(
                           sessionId,
                           "reuse-chase",
                           turntablePos,
                           elapsedMillis,
                           qualityCeiling,
                           projectorPositions.size(),
                           0L,
                           "session can chase final sync target"
                        );
                     } else {
                        rememberActiveSession(immutableTurntablePos, sessionId);
                        ACTIVE_QUALITY_CEILING_BY_SESSION.put(playbackSessionId, qualityCeiling);
                        long requestNanoTime = System.nanoTime();
                        ResolveGeneration requestGeneration = REQUEST_SEQUENCE.updateAndGet(
                           current -> Objects.requireNonNull(current, "current generation").next()
                        );
                        ACTIVE_REQUEST_BY_SESSION.put(playbackSessionId, requestGeneration);
                        VideoResolveRequestOwner<BlockPos> pendingRequest = new VideoResolveRequestOwner<>(
                           qualityCeiling, requestGeneration, List.copyOf(consumerPositions)
                        );
                        VideoResolveRequestOwner<BlockPos> replaced = PENDING_REQUEST_BY_SESSION.put(playbackSessionId, pendingRequest);
                        if (replaced != null) {
                           replaced.cancel();
                        }

                        if (!ModernTurntablePlaybackTracker.replaceResource(turntablePos, sessionId, "video-resolve", pendingRequest::cancel)) {
                           PENDING_REQUEST_BY_SESSION.remove(playbackSessionId, pendingRequest);
                           ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
                           ACTIVE_SESSION_IDS.remove(playbackSessionId);
                           pendingRequest.cancel();
                        } else {
                           VideoBillboardPreview.beginPendingLoading(sessionId, consumerPositions);
                           logDecision(
                              sessionId,
                              "schedule-resolve",
                              turntablePos,
                              elapsedMillis,
                              qualityCeiling,
                              projectorPositions.size(),
                              requestGeneration.value(),
                              "async B站 video stream resolve with quality ceiling"
                           );
                           CancellableTaskFuture<Void> resolveTask = CancellableTaskFuture.submit(VIDEO_RESOLVE_EXECUTOR, () -> {
                              startResolved(cleanRawUrl, selection, turntablePos, consumerPositions, qualityCeiling, sync, requestNanoTime, requestGeneration);
                              return null;
                           });
                           pendingRequest.bind(resolveTask);
                           resolveTask.orTimeout(45L, TimeUnit.SECONDS)
                              .whenComplete(
                                 (ignored, error) -> {
                                    if (error != null && !(error instanceof CancellationException)) {
                                       if (isTimeout(error)) {
                                          pendingRequest.cancel();
                                          LOGGER.warn("现代唱片机视频同步启动超时: {}", cleanRawUrl, error);
                                          Minecraft.getInstance()
                                             .execute(() -> clearTimedOutRequest(sessionId, requestGeneration, turntablePos, consumerPositions, error));
                                       }
                                    }
                                 }
                              );
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void clearTimedOutRequest(
      String sessionId, ResolveGeneration requestGeneration, BlockPos turntablePos, List<BlockPos> capturedConsumers, Throwable error
   ) {
      if (isTimeout(error) && isLatestRequestForTurntable(sessionId, requestGeneration, turntablePos)) {
         PlaybackSessionId key = sessionKey(sessionId);
         if (key != null) {
            ACTIVE_REQUEST_BY_SESSION.remove(key, requestGeneration);
            cancelPendingRequest(key, requestGeneration);
            ACTIVE_SESSION_IDS.remove(key);
            VideoBillboardPreview.clearPendingLoading(sessionId);
            ModernTurntableVideoClient.LiveConsumers consumers = currentConsumers(turntablePos, sessionId, capturedConsumers);
            logDecision(
               sessionId,
               "resolve-timeout-cleared",
               turntablePos,
               0L,
               0,
               consumers.positions().size(),
               requestGeneration.value(),
               "timed out request markers cleared so an active consumer can retry"
            );
         }
      }
   }

   private static boolean isTimeout(Throwable error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof TimeoutException) {
            return true;
         }
      }

      return false;
   }

   private static void markSessionRestarting(String sessionId) {
      PlaybackSessionId key = sessionKey(sessionId);
      if (key != null) {
         ACTIVE_SESSION_IDS.remove(key);
         ACTIVE_QUALITY_CEILING_BY_SESSION.remove(key);
         ACTIVE_REQUEST_BY_SESSION.remove(key);
         cancelPendingRequest(key);
      }
   }

   private static void cancelPendingRequest(PlaybackSessionId key) {
      VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.remove(key);
      if (pending != null) {
         pending.cancel();
      }
   }

   private static void cancelPendingRequest(String sessionId, ResolveGeneration requestGeneration) {
      PlaybackSessionId key = sessionKey(sessionId);
      if (key != null) {
         cancelPendingRequest(key, requestGeneration);
      }
   }

   private static void cancelPendingRequest(PlaybackSessionId key, ResolveGeneration requestGeneration) {
      PENDING_REQUEST_BY_SESSION.computeIfPresent(key, (ignored, pending) -> {
         if (!pending.requestGeneration().equals(requestGeneration)) {
            return pending;
         } else {
            pending.cancel();
            return null;
         }
      });
   }

   private static boolean isLatestRequest(String sessionId, ResolveGeneration requestGeneration) {
      PlaybackSessionId key = sessionKey(sessionId);
      ResolveGeneration active = key != null ? ACTIVE_REQUEST_BY_SESSION.get(key) : null;
      return requestGeneration != null && requestGeneration.equals(active);
   }

   private static boolean isLatestRequestForTurntable(String sessionId, ResolveGeneration requestGeneration, BlockPos turntablePos) {
      if (!isLatestRequest(sessionId, requestGeneration)) {
         return false;
      } else if (turntablePos == null) {
         return true;
      } else {
         PlaybackSessionId activeSession = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
         PlaybackSessionId key = sessionKey(sessionId);
         return key != null && key.equals(activeSession);
      }
   }

   private static void invalidateIfNoLiveConsumer(BlockPos turntablePos) {
      if (turntablePos != null) {
         PlaybackSessionId key = ACTIVE_SESSION_BY_TURNTABLE.get(turntablePos);
         if (key == null) {
            key = LATEST_SESSION_BY_TURNTABLE.get(turntablePos);
         }

         if (key != null) {
            String sessionId = key.value();
            VideoResolveRequestOwner<BlockPos> pending = PENDING_REQUEST_BY_SESSION.get(key);
            List<BlockPos> captured = pending != null ? pending.consumerPositions() : List.of();
            ModernTurntableVideoClient.LiveConsumers consumers = currentConsumers(turntablePos, sessionId, captured);
            if (!consumers.hasAny()) {
               markSessionRestarting(sessionId);
               VideoBillboardPreview.clearPendingLoading(sessionId);
               logDecision(sessionId, "invalidate-last-consumer", turntablePos, 0L, 0, 0, 0L, "last live video consumer detached");
            }
         }
      }
   }

   private static ModernTurntableVideoClient.LiveConsumers currentConsumers(BlockPos turntablePos, String sessionId, List<BlockPos> capturedPositions) {
      LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
      findLinkedVideoProjectors(turntablePos).stream().map(projector -> projector.getBlockPos().immutable()).forEach(positions::add);
      positions.addAll(CONTROL_CONSOLE_CONSUMERS.getOrDefault(turntablePos, Set.of()));
      if (capturedPositions != null) {
         capturedPositions.stream().filter(x$0 -> VideoBillboardPreview.isProjectorRenderedByBer(x$0)).forEach(pos -> positions.add(pos.immutable()));
      }

      boolean holographic = HolographicGlassesClient.handlesTurntable(turntablePos);
      boolean gui = VideoBillboardPreview.hasGuiConsumer(sessionId);
      return new ModernTurntableVideoClient.LiveConsumers(List.copyOf(positions), holographic, gui);
   }

   private static void rememberActiveSession(BlockPos turntablePos, String sessionId) {
      PlaybackSessionId key = sessionKey(sessionId);
      if (turntablePos != null && key != null) {
         ACTIVE_SESSION_BY_TURNTABLE.put(turntablePos, key);
         LATEST_SESSION_BY_TURNTABLE.put(turntablePos, key);
      }
   }

   private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors, List<BlockPos> consoleConsumers) {
      int projectorQuality = projectors.stream()
         .mapToInt(projector -> projector.getPreferredQuality() > 0 ? projector.getPreferredQuality() : DEFAULT_PREFERRED_QUALITY)
         .max()
         .orElse(0);
      int consoleQuality = consoleConsumers.stream().mapToInt(pos -> CONTROL_CONSOLE_QUALITY.getOrDefault(pos, DEFAULT_PREFERRED_QUALITY)).max().orElse(0);
      int selected = Math.max(projectorQuality, consoleQuality);
      return selected > 0 ? selected : DEFAULT_PREFERRED_QUALITY;
   }

   private static boolean isSessionRunningAtQualityCeiling(String sessionId, int requestedQualityCeiling) {
      PlaybackSessionId key = sessionKey(sessionId);
      Integer activeQualityCeiling = key != null ? ACTIVE_QUALITY_CEILING_BY_SESSION.get(key) : null;
      return activeQualityCeiling != null && activeQualityCeiling == requestedQualityCeiling;
   }

   private static void startResolved(
      String cleanRawUrl,
      BiliApiClient.VideoSelection selection,
      BlockPos turntablePos,
      List<BlockPos> projectorPositions,
      int qualityCeiling,
      PlaybackSync.Metadata sync,
      long requestNanoTime,
      ResolveGeneration requestGeneration
   ) {
      PlaybackSessionId playbackSessionId = sync.playbackSessionId().orElse(null);
      if (playbackSessionId != null) {
         try {
            BiliVideoStreamResolver.ResolvedVideoStream stream = BiliVideoStreamResolver.resolve(cleanRawUrl, qualityCeiling, DEFAULT_FPS);
            int sourceWidth = stream.sourceWidth();
            int sourceHeight = stream.sourceHeight();
            int fps = stream.fps();
            Minecraft.getInstance()
               .execute(
                  () -> {
                     ModernTurntableVideoClient.LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
                     VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestGeneration, turntablePos, consumers);
                     if (decision != VideoResolveAdmissionPolicy.Decision.START) {
                        dropResolvedResult(sync.sessionId(), requestGeneration, turntablePos, decision);
                     } else {
                        ACTIVE_QUALITY_CEILING_BY_SESSION.put(playbackSessionId, qualityCeiling);
                        PlaybackSync.Metadata launchSync = currentPlaybackMetadata(turntablePos, sync);
                        long elapsedMillis = normalizedElapsedMillis(launchSync);
                        logDecision(
                           sync.sessionId(),
                           "resolved-start",
                           turntablePos,
                           elapsedMillis,
                           qualityCeiling,
                           consumers.positions().size(),
                           requestGeneration.value(),
                           "qualityCeiling="
                              + qualityCeiling
                              + " actualQuality="
                              + stream.quality()
                              + " title='"
                              + stream.title()
                              + "' size="
                              + sourceWidth
                              + "x"
                              + sourceHeight
                              + " fps="
                              + fps
                              + " launchTimelineRefreshed="
                              + (launchSync != sync)
                        );
                        VideoBillboardPreview.startSyncedCandidates(
                           stream.candidates(),
                           sourceWidth,
                           sourceHeight,
                           fps,
                           launchSync.sessionId(),
                           elapsedMillis,
                           launchSync.totalMillis(),
                           consumers.positions(),
                           turntablePos,
                           PREFER_NATIVE,
                           DECODER_OVERRIDE.isBlank() ? null : DECODER_OVERRIDE
                        );
                        cancelPendingRequest(sync.sessionId(), requestGeneration);
                        ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
                     }
                  }
               );
         } catch (Exception var14) {
            Minecraft.getInstance().execute(() -> {
               ModernTurntableVideoClient.LiveConsumers consumers = currentConsumers(turntablePos, sync.sessionId(), projectorPositions);
               VideoResolveAdmissionPolicy.Decision decision = resolveAdmission(sync.sessionId(), requestGeneration, turntablePos, consumers);
               if (decision == VideoResolveAdmissionPolicy.Decision.START) {
                  cancelPendingRequest(sync.sessionId(), requestGeneration);
                  ACTIVE_REQUEST_BY_SESSION.remove(playbackSessionId, requestGeneration);
                  VideoBillboardPreview.markPendingFailure(sync.sessionId(), consumers.positions());
               } else {
                  dropResolvedResult(sync.sessionId(), requestGeneration, turntablePos, decision);
               }
            });
            throw new IllegalStateException("resolve B站 video stream failed", var14);
         }
      }
   }

   private static VideoResolveAdmissionPolicy.Decision resolveAdmission(
      String sessionId, ResolveGeneration requestGeneration, BlockPos turntablePos, ModernTurntableVideoClient.LiveConsumers consumers
   ) {
      boolean latest = isLatestRequestForTurntable(sessionId, requestGeneration, turntablePos);
      Minecraft minecraft = Minecraft.getInstance();
      if ((minecraft.level != null && turntablePos != null ? minecraft.level.getBlockEntity(turntablePos) : null) instanceof ModernTurntableBlockEntity turntable
         && turntable.isPlaying()) {
         PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata();
         boolean sameSession = current.hasSession() && sessionId.equals(current.sessionId());
         return VideoResolveAdmissionPolicy.decide(latest, sameSession, true, consumers.hasAny());
      } else {
         return VideoResolveAdmissionPolicy.decide(latest, false, false, consumers.hasAny());
      }
   }

   private static void dropResolvedResult(
      String sessionId, ResolveGeneration requestGeneration, BlockPos turntablePos, VideoResolveAdmissionPolicy.Decision decision
   ) {
      if (decision == VideoResolveAdmissionPolicy.Decision.DROP_NO_CONSUMER) {
         NO_CONSUMER_RESOLVE_DROPS.incrementAndGet();
      } else {
         STALE_RESOLVE_DROPS.incrementAndGet();
      }

      if (isLatestRequest(sessionId, requestGeneration)) {
         markSessionRestarting(sessionId);
         VideoBillboardPreview.clearPendingLoading(sessionId);
      }

      logDecision(
         sessionId,
         "drop-resolve-" + decision.name().toLowerCase(Locale.ROOT),
         turntablePos,
         0L,
         0,
         0,
         requestGeneration.value(),
         "resolved result failed live admission"
      );
   }

   public static ModernTurntableVideoClient.VideoLifecycleDiagnostics videoLifecycleDiagnostics() {
      int consoleConsumers = CONTROL_CONSOLE_CONSUMERS.values().stream().mapToInt(consumers -> consumers.size()).sum();
      return new ModernTurntableVideoClient.VideoLifecycleDiagnostics(
         ACTIVE_SESSION_IDS.size(),
         ACTIVE_REQUEST_BY_SESSION.size(),
         PENDING_REQUEST_BY_SESSION.size(),
         consoleConsumers,
         STALE_RESOLVE_DROPS.get(),
         NO_CONSUMER_RESOLVE_DROPS.get(),
         VideoBillboardPreview.resourceDiagnostics()
      );
   }

   public static List<String> describeVideoLifecycle() {
      ModernTurntableVideoClient.VideoLifecycleDiagnostics diagnostics = videoLifecycleDiagnostics();
      VideoBillboardState.ResourceDiagnostics resources = diagnostics.resources();
      return List.of(
         "video sessions="
            + diagnostics.activeSessions()
            + " requests="
            + diagnostics.activeRequests()
            + " pendingResolve="
            + diagnostics.pendingRequests()
            + " consoleConsumers="
            + diagnostics.controlConsoleConsumers(),
         "video instances="
            + resources.instances()
            + " running="
            + resources.runningInstances()
            + " failed="
            + resources.failedInstances()
            + " pendingLoading="
            + resources.pendingLoading()
            + " pendingFailure="
            + resources.pendingFailure()
            + " closeZombies="
            + resources.activeCloseZombies()
            + " lateCloseConvergences="
            + resources.lateCloseConvergences(),
         "video refs projector=" + resources.projectorReferences() + " ber=" + resources.berManagedProjectors() + " gui=" + resources.guiConsumers(),
         "video resolveDrops stale=" + diagnostics.staleResolveDrops() + " noConsumer=" + diagnostics.noConsumerResolveDrops(),
         VideoCloseDiagnostics.describeGlobal(),
         AudioNativeCloseDiagnostics.describeGlobal(OpenALSpatialAudio.pendingNativeDeleteBatches())
      );
   }

   private static long normalizedElapsedMillis(PlaybackSync.Metadata sync) {
      long base = Math.max(0L, sync.elapsedMillis());
      long total = Math.max(0L, sync.totalMillis());
      return normalizeMillis(base, total);
   }

   private static PlaybackSync.Metadata currentPlaybackMetadata(BlockPos turntablePos, PlaybackSync.Metadata fallback) {
      Minecraft minecraft = Minecraft.getInstance();
      if (turntablePos != null && minecraft.level != null) {
         if (minecraft.level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable && turntable.isPlaying()) {
            PlaybackSync.Metadata current = turntable.getPlaybackSyncMetadata();
            return current.hasSession() && fallback.sessionId().equals(current.sessionId()) ? current : fallback;
         } else {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   private static long normalizeMillis(long value, long totalMillis) {
      long normalized = Math.max(0L, value);
      long total = Math.max(0L, totalMillis);
      return total > 0L ? Math.min(total, normalized) : normalized;
   }

   private static List<VideoProjectorBlockEntity> findLinkedVideoProjectors(BlockPos turntablePos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (turntablePos != null && minecraft.level != null) {
         List<VideoProjectorBlockEntity> projectors = new ArrayList<>();

         for (BlockPos sourcePos : ClientLinkRegistry.getSources(turntablePos)) {
            if (minecraft.level.getBlockEntity(sourcePos) instanceof VideoProjectorBlockEntity projector
               && turntablePos.equals(projector.getLinkedTurntablePos())) {
               projectors.add(projector);
            }
         }

         return projectors;
      } else {
         return List.of();
      }
   }

   private static void logDecision(
      String sessionId, String action, BlockPos turntablePos, long elapsedMillis, int quality, int projectorCount, long requestId, String reason
   ) {
      if (LOG_SYNC_DECISIONS) {
         PlaybackSessionId key = sessionKey(sessionId);
         if (key != null) {
            String fingerprint = action + "|" + quality + "|" + projectorCount + "|" + requestId + "|" + reason;
            String previous = LAST_DECISION_BY_SESSION.put(key, fingerprint);
            if (!fingerprint.equals(previous)) {
               LOGGER.debug(
                  "现代唱片机视频同步决策: action={} session={} request={} turntable={} elapsed={}ms qualityCeiling={} projectors={} reason={}",
                  new Object[]{action, sessionId, requestId, turntablePos, Math.max(0L, elapsedMillis), quality, projectorCount, reason}
               );
            }
         }
      }
   }

   private static PlaybackSessionId sessionKey(String sessionId) {
      return PlaybackSessionId.parse(sessionId).orElse(null);
   }

   private record LiveConsumers(List<BlockPos> positions, boolean holographic, boolean gui) {
      private boolean hasAny() {
         return !this.positions.isEmpty() || this.holographic || this.gui;
      }
   }

   public record VideoLifecycleDiagnostics(
      int activeSessions,
      int activeRequests,
      int pendingRequests,
      int controlConsoleConsumers,
      long staleResolveDrops,
      long noConsumerResolveDrops,
      VideoBillboardState.ResourceDiagnostics resources
   ) {
   }
}
