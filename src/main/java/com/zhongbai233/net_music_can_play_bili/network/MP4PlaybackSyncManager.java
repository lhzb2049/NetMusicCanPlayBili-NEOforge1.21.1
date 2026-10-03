package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.level.LevelEvent.Save;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

@EventBusSubscriber
public final class MP4PlaybackSyncManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SYNC_INTERVAL_TICKS = 20;
   private static final int FULL_SYNC_INTERVAL_TICKS = 300;
   private static final int DISCOVERY_INTERVAL_TICKS = 40;
   private static final int SOURCE_MISSING_GRACE_TICKS = 20;
   private static final double SYNC_RANGE = 96.0;
   private static final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> SESSION_REGISTRY = new MP4PlaybackSourceSessionRegistry<>();
   private static final MP4ResolveIntentRegistry RESOLVE_INTENTS = new MP4ResolveIntentRegistry();
   private static final MP4PlaybackRetryAdmission<MP4PlaybackSyncManager.Session> RETRY_ADMISSION = new MP4PlaybackRetryAdmission<>(
      SESSION_REGISTRY, RESOLVE_INTENTS, session -> session.playbackSessionId(), session -> session.queueIndex(), session -> session.rawUrl()
   );
   private static final MP4PlaybackProgressPersistence PROGRESS_PERSISTENCE = new MP4PlaybackProgressPersistence();
   private static final MP4PlaybackAudienceBroadcaster AUDIENCE_BROADCASTER = new MP4PlaybackAudienceBroadcaster(
      SESSION_REGISTRY, 96.0, RESOLVE_INTENTS::invalidate, MP4PlaybackSyncManager::isPlaybackAllowed
   );
   private static final MP4PlaybackSourceDiscovery SOURCE_DISCOVERY = new MP4PlaybackSourceDiscovery(
      SESSION_REGISTRY, 96.0, MP4PlaybackSyncManager::startDiscovered, AUDIENCE_BROADCASTER::broadcast
   );
   private static final MP4PlaybackQueueController QUEUE_CONTROLLER = new MP4PlaybackQueueController(
      SESSION_REGISTRY,
      RESOLVE_INTENTS::invalidate,
      PROGRESS_PERSISTENCE::record,
      PROGRESS_PERSISTENCE::flush,
      AUDIENCE_BROADCASTER::broadcast,
      AUDIENCE_BROADCASTER::broadcastStop,
      MP4PlaybackSyncManager::startDiscovered
   );

   private MP4PlaybackSyncManager() {
   }

   static MP4ResolveIntentRegistry.Intent beginCommandResolve(UUID deviceId, int queueIndex, String sourceUrl) {
      return RESOLVE_INTENTS.replace(deviceId, queueIndex, sourceUrl);
   }

   static boolean isCurrentResolve(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
      return RESOLVE_INTENTS.isCurrent(deviceId, intent);
   }

   static void completeResolve(UUID deviceId, MP4ResolveIntentRegistry.Intent intent) {
      RESOLVE_INTENTS.complete(deviceId, intent);
   }

   static MP4PlaybackRetryAdmission.Attempt beginRetryResolve(UUID deviceId, PlaybackSessionId expectedSessionId) {
      return RETRY_ADMISSION.begin(deviceId, expectedSessionId);
   }

   static boolean isCurrentRetryResolve(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
      return RETRY_ADMISSION.isCurrent(deviceId, attempt);
   }

   static void completeRetryResolve(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
      RETRY_ADMISSION.complete(deviceId, attempt);
   }

   static boolean applyRetryResolved(
      ServerPlayer requester,
      UUID deviceId,
      MP4PlaybackRetryAdmission.Attempt attempt,
      String resolvedPlayUrl,
      String songName,
      int durationSeconds,
      long targetMillis
   ) {
      if (requester != null
         && deviceId != null
         && attempt != null
         && resolvedPlayUrl != null
         && !resolvedPlayUrl.isBlank()
         && requester.level() instanceof ServerLevel level
         && isPlaybackAllowed(level, attempt.sourceUrl(), requester)) {
         int safeDurationSeconds = Math.max(1, durationSeconds);
         long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
         String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, attempt.expectedSessionId(), elapsedMillis, safeDurationSeconds * 1000L);
         long gameTime = MonotonicMediaClock.nowTick();
         MP4PlaybackSyncManager.Session refreshed = RETRY_ADMISSION.replaceIfCurrent(
            deviceId, attempt, current -> current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds, elapsedMillis, gameTime)
         );
         if (refreshed == null) {
            return false;
         } else {
            refreshed.recordAudit(level, gameTime);
            PROGRESS_PERSISTENCE.persist(requester, refreshed, gameTime, true);
            AUDIENCE_BROADCASTER.broadcast(level, refreshed, gameTime);
            return true;
         }
      } else {
         completeRetryResolve(deviceId, attempt);
         return false;
      }
   }

   public static void start(ServerPlayer owner, MP4PlaybackSyncPacket packet) {
      if (owner != null && packet != null && packet.playing()) {
         PlaybackSessionId playbackSessionId = PlaybackSessionId.parse(packet.sessionId()).orElse(null);
         if (playbackSessionId != null) {
            if (owner.level() instanceof ServerLevel serverLevel) {
               if (!isPlaybackAllowed(serverLevel, packet.rawUrl(), owner)) {
                  PacketDistributor.sendToPlayer(
                     owner, MP4PlaybackSyncPacket.stop(packet.ownerId(), packet.sourceId(), packet.queueIndex()), new CustomPacketPayload[0]
                  );
               } else {
                  long gameTime = MonotonicMediaClock.nowTick();
                  long elapsedMillis = clampElapsed(packet.elapsedMillis(), packet.durationSeconds());
                  MP4PlaybackSyncManager.Session session = new MP4PlaybackSyncManager.Session(
                     serverLevel.dimension(),
                     owner.getUUID(),
                     packet.sourceId(),
                     packet.sourceType(),
                     packet.sourceEntityId(),
                     owner.blockPosition(),
                     -1,
                     packet.queueIndex(),
                     packet.playUrl(),
                     packet.rawUrl(),
                     packet.songName(),
                     Math.max(1, packet.durationSeconds()),
                     packet.volumePerMille(),
                     playbackSessionId,
                     gameTime - Math.round(elapsedMillis / 50.0),
                     gameTime,
                     gameTime
                  );
                  RESOLVE_INTENTS.invalidate(session.sourceId());
                  SESSION_REGISTRY.replace(session.sourceId(), session);
                  session.recordAudit(serverLevel, gameTime);
                  AUDIENCE_BROADCASTER.broadcast(serverLevel, session, gameTime);
                  PROGRESS_PERSISTENCE.persist(owner, session, gameTime, true);
               }
            }
         }
      }
   }

   public static void stop(ServerPlayer owner) {
      if (owner != null) {
         ItemStack stack = MP4Item.findPlayableInInventory(owner);
         stop(owner, MP4Item.readDeviceId(stack));
      }
   }

   public static void stop(ServerPlayer owner, UUID deviceId) {
      if (owner != null && deviceId != null) {
         RESOLVE_INTENTS.invalidate(deviceId);
         MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.remove(deviceId);
         if (session != null && owner.level() instanceof ServerLevel serverLevel) {
            PROGRESS_PERSISTENCE.persist(session.stack(serverLevel), session, MonotonicMediaClock.nowTick(), false);
            AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
         }
      }
   }

   public static int currentProgressPerMille(ServerPlayer owner, int fallback) {
      if (owner != null && owner.level() instanceof ServerLevel) {
         ItemStack stack = MP4Item.findPlayableInInventory(owner);
         UUID deviceId = MP4Item.readDeviceId(stack);
         MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.get(deviceId);
         return session != null
            ? MP4PlaybackProgressPolicy.progressPerMille(session.elapsedMillis(MonotonicMediaClock.nowTick()), session.durationSeconds())
            : fallback;
      } else {
         return fallback;
      }
   }

   public static long currentElapsedMillis(ServerPlayer owner, UUID deviceId, long fallback) {
      if (owner != null && owner.level() instanceof ServerLevel serverLevel && deviceId != null) {
         MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.get(deviceId);
         return session != null ? session.elapsedMillis(MonotonicMediaClock.nowTick()) : PROGRESS_PERSISTENCE.currentElapsed(serverLevel, deviceId, fallback);
      } else {
         return Math.max(0L, fallback);
      }
   }

   public static long savedElapsedMillis(ServerPlayer owner, UUID deviceId, int queueIndex, long fallback) {
      return owner != null && owner.level() instanceof ServerLevel serverLevel && deviceId != null
         ? PROGRESS_PERSISTENCE.savedElapsed(serverLevel, deviceId, queueIndex, fallback)
         : Math.max(0L, fallback);
   }

   public static void recordProgress(
      ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis, List<ItemStack> queue, int volumePerMille, boolean playing
   ) {
      int durationSeconds = MP4PlaybackQueueController.durationSeconds(queue, queueIndex);
      recordProgress(owner, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, "", playing);
   }

   public static void recordProgress(
      ServerPlayer owner, UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille, String sessionId, boolean playing
   ) {
      if (owner != null && owner.level() instanceof ServerLevel serverLevel && deviceId != null) {
         PROGRESS_PERSISTENCE.recordAndFlush(serverLevel, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, sessionId, playing);
      }
   }

   public static void updateVolume(ServerPlayer owner, int volumePerMille) {
      if (owner != null) {
         ItemStack stack = MP4Item.findPlayableInInventory(owner);
         updateVolume(MP4Item.readDeviceId(stack), volumePerMille);
      }
   }

   public static void updateVolume(UUID deviceId, int volumePerMille) {
      if (deviceId != null) {
         SESSION_REGISTRY.updateIfPresent(deviceId, session -> {
            MP4PlaybackSyncManager.Session updated = session.withVolume(volumePerMille);
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
               ServerLevel level = server.getLevel(updated.levelKey());
               if (level != null) {
                  AUDIENCE_BROADCASTER.broadcastTimeline(level, updated, MonotonicMediaClock.nowTick());
               }
            }

            return updated;
         });
      }
   }

   public static int activeQueueIndex(UUID deviceId) {
      MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.get(deviceId);
      return session != null ? session.queueIndex() : -1;
   }

   static boolean matchesActiveSession(UUID deviceId, PlaybackSessionId expectedSessionId, int queueIndex, String rawUrl) {
      MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.get(deviceId);
      return session != null
         && expectedSessionId != null
         && expectedSessionId.equals(session.playbackSessionId())
         && session.queueIndex() == queueIndex
         && Objects.equals(session.rawUrl(), rawUrl != null ? rawUrl : "");
   }

   static boolean refreshActiveSession(
      ServerPlayer requester,
      UUID deviceId,
      PlaybackSessionId expectedSessionId,
      int queueIndex,
      String rawUrl,
      String resolvedPlayUrl,
      String songName,
      int durationSeconds,
      long targetMillis
   ) {
      if (requester != null
         && deviceId != null
         && expectedSessionId != null
         && resolvedPlayUrl != null
         && !resolvedPlayUrl.isBlank()
         && requester.level() instanceof ServerLevel level) {
         MP4PlaybackSyncManager.Session current = SESSION_REGISTRY.get(deviceId);
         if (current != null && matchesActiveSession(deviceId, expectedSessionId, queueIndex, rawUrl)) {
            int safeDurationSeconds = Math.max(1, durationSeconds);
            long elapsedMillis = clampElapsed(targetMillis, safeDurationSeconds);
            String syncedPlayUrl = PlaybackSync.withSync(resolvedPlayUrl, expectedSessionId, elapsedMillis, safeDurationSeconds * 1000L);
            long gameTime = MonotonicMediaClock.nowTick();
            MP4PlaybackSyncManager.Session refreshed = current.withRefreshedPlayback(syncedPlayUrl, songName, safeDurationSeconds, elapsedMillis, gameTime);
            if (!SESSION_REGISTRY.replace(deviceId, current, refreshed)) {
               return false;
            } else {
               refreshed.recordAudit(level, gameTime);
               PROGRESS_PERSISTENCE.persist(requester, refreshed, gameTime, true);
               AUDIENCE_BROADCASTER.broadcast(level, refreshed, gameTime);
               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
      QUEUE_CONTROLLER.reconcileQueueChange(owner, deviceId, newQueue);
   }

   public static int unlinkAllHeadphones(ServerPlayer actor, UUID deviceId) {
      if (actor != null && deviceId != null) {
         int changed = 0;
         if (actor.level() instanceof ServerLevel actorLevel) {
            MinecraftServer var9 = actorLevel.getServer();
            MP4PlaybackSyncPacket stop = AUDIENCE_BROADCASTER.stopPacketFor(deviceId);

            for (ServerPlayer player : var9.getPlayerList().getPlayers()) {
               if (clearMp4LinksFromPlayer(player, deviceId)) {
                  changed++;
                  PacketDistributor.sendToPlayer(player, stop, new CustomPacketPayload[0]);
               }

               AudioLinkIndex.updatePlayerHeadphones(player);
            }

            for (UUID playerId : AudioLinkIndex.removeHeadphonePlayersForMp4(deviceId)) {
               ServerPlayer player = var9.getPlayerList().getPlayer(playerId);
               if (player != null) {
                  PacketDistributor.sendToPlayer(player, stop, new CustomPacketPayload[0]);
               }
            }

            return changed;
         } else {
            return 0;
         }
      } else {
         return 0;
      }
   }

   public static int unlinkAllHolographicGlasses(ServerPlayer actor, UUID deviceId) {
      if (actor != null && deviceId != null && actor.level() instanceof ServerLevel actorLevel) {
         int var7 = 0;
         MinecraftServer server = actorLevel.getServer();

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (clearHolographicGlassesLinksFromPlayer(player, deviceId)) {
               var7++;
            }
         }

         return var7;
      } else {
         return 0;
      }
   }

   public static boolean resumeExisting(ServerPlayer owner, UUID deviceId, int queueIndex, int volumePerMille) {
      return resumeExisting(owner, deviceId, queueIndex, volumePerMille, -1L);
   }

   public static boolean resumeExisting(ServerPlayer owner, UUID deviceId, int queueIndex, int volumePerMille, long targetMillis) {
      if (owner != null && deviceId != null && owner.level() instanceof ServerLevel level) {
         MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.get(deviceId);
         if (session != null && session.queueIndex() == queueIndex) {
            long gameTime = MonotonicMediaClock.nowTick();
            MP4PlaybackSyncManager.Session base = session.withVolume(volumePerMille);
            if (targetMillis >= 0L) {
               long elapsedMillis = clampElapsed(targetMillis, session.durationSeconds());
               base = base.withStartedGameTime(gameTime - Math.round(elapsedMillis / 50.0), gameTime);
               PROGRESS_PERSISTENCE.recordAndFlush(
                  level,
                  deviceId,
                  queueIndex,
                  elapsedMillis,
                  session.durationSeconds(),
                  Math.max(0, Math.min(1000, volumePerMille)),
                  Optional.of(session.playbackSessionId()),
                  true
               );
            }

            MP4PlaybackSyncManager.Session resumed = base.asPlayerSource(owner, gameTime);
            if (!SESSION_REGISTRY.replace(deviceId, session, resumed)) {
               return false;
            } else {
               RESOLVE_INTENTS.invalidate(deviceId);
               AUDIENCE_BROADCASTER.broadcast(level, resumed, gameTime);
               return true;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   @SubscribeEvent
   public static void onItemToss(ItemTossEvent event) {
      if (event.getPlayer() instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level) {
         ItemEntity itemEntity = event.getEntity();
         UUID deviceId = MP4Item.readDeviceId(itemEntity.getItem());
         if (deviceId != null) {
            MP4PlaybackSyncManager.Session session = SESSION_REGISTRY.remove(deviceId);
            if (session != null) {
               long gameTime = MonotonicMediaClock.nowTick();
               MP4PlaybackSyncManager.Session migrated = session.asItemSource(itemEntity.getId(), itemEntity.blockPosition(), gameTime);
               MP4DeviceLocationIndex.recordItemEntity(level, itemEntity, deviceId);
               PROGRESS_PERSISTENCE.persist(itemEntity.getItem(), migrated, gameTime, true);
               SESSION_REGISTRY.replace(migrated.sourceId(), migrated);
               AUDIENCE_BROADCASTER.broadcast(level, migrated, gameTime);
            }
         }
      }
   }

   @SubscribeEvent
   public static void onContainerOpen(Open event) {
      SOURCE_DISCOVERY.scanContainerEventMenu(event.getEntity(), event.getContainer());
   }

   @SubscribeEvent
   public static void onContainerClose(Close event) {
      SOURCE_DISCOVERY.scanContainerEventMenu(event.getEntity(), event.getContainer());
   }

   @SubscribeEvent
   public static void onLevelSave(Save event) {
      if (event.getLevel() instanceof ServerLevel level) {
         PROGRESS_PERSISTENCE.flush(level);
         MP4DeviceStateStore.flush(level);
      }
   }

   @SubscribeEvent
   public static void onServerStopping(ServerStoppingEvent event) {
      for (ServerLevel level : event.getServer().getAllLevels()) {
         PROGRESS_PERSISTENCE.flush(level);
         MP4DeviceStateStore.flush(level);
      }

      PROGRESS_PERSISTENCE.clearRuntime();
      AudioLinkIndex.clear();
      MP4DeviceLocationIndex.clear();
      MP4DeviceHolderTracker.clear();
      PadDeviceHolderTracker.clear();
      PadDocumentStore.clearRuntime();
      ControlConsoleEditLeaseRegistry.clear();
      ControlConsoleConsumerLeaseRegistry.clear();
      SESSION_REGISTRY.clear();
      RESOLVE_INTENTS.clear();
   }

   @SubscribeEvent
   public static void onPlayerLoggedOut(PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         UUID playerId = player.getUUID();
         PadPlaybackControlPacket.invalidatePlayer(playerId);
         NetworkRateLimiter.removePlayer(playerId);
         ControlConsoleEditLeaseRegistry.releasePlayer(playerId);
         ControlConsoleConsumerLeaseRegistry.releasePlayer(playerId);
         AudioLinkIndex.removeHeadphonePlayer(playerId);
         AudioLinkIndex.removeHeadphoneOwner(playerId);
      }
   }

   @SubscribeEvent
   public static void onServerTick(Post event) {
      MinecraftServer server = event.getServer();
      if (server != null) {
         MP4DeviceHolderTracker.tick(server);
         PadDeviceHolderTracker.tick(server);
         if (server.getTickCount() % 20 == 0) {
            ControlConsoleEditLeaseRegistry.cleanupExpired(System.currentTimeMillis());
            ControlConsoleConsumerLeaseRegistry.cleanupExpired(System.currentTimeMillis());
         }

         if (server.getTickCount() % 40 == 0) {
            for (ServerLevel level : server.getAllLevels()) {
               for (ServerPlayer player : level.players()) {
                  AudioLinkIndex.updatePlayerHeadphones(player);
               }

               SOURCE_DISCOVERY.discoverPlayingSources(level);
            }
         }

         if (!SESSION_REGISTRY.isEmpty()) {
            for (Entry<UUID, MP4PlaybackSyncManager.Session> entry : SESSION_REGISTRY.entries()) {
               UUID deviceId = entry.getKey();
               MP4PlaybackSyncManager.Session session = entry.getValue();
               ServerLevel serverLevel = server.getLevel(session.levelKey());
               if (serverLevel == null) {
                  if (SESSION_REGISTRY.remove(deviceId, session)) {
                     RESOLVE_INTENTS.invalidate(session.sourceId());
                  }
               } else {
                  long gameTime = MonotonicMediaClock.nowTick();
                  MP4PlaybackSyncManager.Session refreshed = SOURCE_DISCOVERY.refreshActiveSource(server, session, gameTime);
                  if (refreshed != null && !MP4PlaybackSourceDiscovery.sameSource(session, refreshed)) {
                     if (!SESSION_REGISTRY.replace(deviceId, session, refreshed)) {
                        continue;
                     }

                     session = refreshed;
                     ServerLevel refreshedLevel = server.getLevel(refreshed.levelKey());
                     if (refreshedLevel != null) {
                        AUDIENCE_BROADCASTER.broadcast(refreshedLevel, refreshed, gameTime);
                     }
                  }

                  ItemStack stack = session.stack(serverLevel);
                  if (!isActiveMediaStack(serverLevel, stack, session.sourceId())) {
                     MP4PlaybackSyncManager.Session relocated = SOURCE_DISCOVERY.relocateSession(server, session, gameTime);
                     if (relocated != null) {
                        if (SESSION_REGISTRY.replace(deviceId, session, relocated)) {
                           AUDIENCE_BROADCASTER.broadcast(serverLevel, relocated, gameTime);
                        }
                     } else {
                        Long missingSince = SESSION_REGISTRY.markMissingIfCurrent(deviceId, session, gameTime);
                        if (missingSince != null && gameTime - missingSince >= 20L && SESSION_REGISTRY.remove(deviceId, session)) {
                           PROGRESS_PERSISTENCE.persist(stack, session, gameTime, false);
                           RESOLVE_INTENTS.invalidate(session.sourceId());
                           AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
                        }
                     }
                  } else if (SESSION_REGISTRY.clearMissingIfCurrent(deviceId, session)) {
                     long elapsed = session.elapsedMillis(gameTime);
                     if (elapsed >= session.durationSeconds() * 1000L) {
                        if (SESSION_REGISTRY.remove(deviceId, session) && !QUEUE_CONTROLLER.tryAdvanceQueue(serverLevel, stack, session)) {
                           PROGRESS_PERSISTENCE.persist(stack, session.resetToStart(gameTime), gameTime, false);
                           AUDIENCE_BROADCASTER.broadcastStop(serverLevel, session);
                        }
                     } else {
                        PROGRESS_PERSISTENCE.persist(stack, session, gameTime, true);
                        session.recordAudit(serverLevel, gameTime);
                        if (gameTime - session.lastSyncGameTime() >= 20L) {
                           MP4PlaybackSyncManager.Session synced = session.withLastSyncGameTime(gameTime);
                           if (SESSION_REGISTRY.replace(deviceId, session, synced)) {
                              if (gameTime - session.lastFullSyncGameTime() >= 300L) {
                                 MP4PlaybackSyncManager.Session fullSynced = synced.withLastFullSyncGameTime(gameTime);
                                 if (SESSION_REGISTRY.replace(deviceId, synced, fullSynced)) {
                                    AUDIENCE_BROADCASTER.broadcast(serverLevel, fullSynced, gameTime);
                                 }
                              } else {
                                 AUDIENCE_BROADCASTER.broadcastTimeline(serverLevel, synced, gameTime);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void startDiscovered(
      ServerLevel level, ItemStack stack, UUID ownerId, UUID sourceId, int sourceType, int sourceEntityId, BlockPos sourcePos, int containerSlot
   ) {
      if (!SESSION_REGISTRY.contains(sourceId)) {
         MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, sourceId, stack);
         List<ItemStack> queue = MP4PlaybackQueueController.queueForDevice(deviceEntry, stack);
         MP4Item.State state = deviceEntry.state();
         if (!queue.isEmpty()) {
            int index = Math.max(0, Math.min(queue.size() - 1, state.selectedQueueIndex()));
            SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(index));
            if (songInfo != null && (!songInfo.vip || MusicPlayResolverManager.canResolve(songInfo))) {
               ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
               if (!isPlaybackAllowed(level, songInfo.songUrl, owner)) {
                  MP4DeviceStateStore.updateState(
                     level,
                     sourceId,
                     new MP4Item.State(
                        false,
                        state.shuffle(),
                        state.videoEnabled(),
                        state.landscape(),
                        state.qualityIndex(),
                        index,
                        state.queueScrollOffset(),
                        state.volumePerMille(),
                        state.repeatMode(),
                        state.playlistOpen(),
                        state.lyricsEnabled(),
                        state.subtitleMode(),
                        state.subtitleAiEnabled(),
                        state.progressPerMille(),
                        state.rotationHintShown()
                     )
                  );
               } else {
                  SongInfo original = songInfo.clone();
                  long targetMillis = PROGRESS_PERSISTENCE.targetMillis(level, sourceId, stack, state, index);
                  MP4ResolveIntentRegistry.Intent intent = RESOLVE_INTENTS.begin(sourceId, index, original.songUrl);
                  if (intent != null) {
                     if (SESSION_REGISTRY.contains(sourceId)) {
                        RESOLVE_INTENTS.complete(sourceId, intent);
                     } else {
                        MusicPlayResolverManager.resolve(original.clone())
                           .whenCompleteAsync(
                              (resolved, error) -> {
                                 if (RESOLVE_INTENTS.isCurrent(sourceId, intent)) {
                                    if (error != null) {
                                       RESOLVE_INTENTS.complete(sourceId, intent);
                                       LOGGER.error("MP4 自动接管播放源解析失败: {}", original.songName, error);
                                    } else if (stack.getItem() instanceof MP4Item && deviceState(level, stack, sourceId).playing()) {
                                       List<ItemStack> currentQueue = MP4PlaybackQueueController.queueForDevice(
                                          MP4DeviceStateStore.getOrCreate(level, sourceId, stack), stack
                                       );
                                       if (index >= 0 && index < currentQueue.size()) {
                                          SongInfo current = ItemMusicCD.getSongInfo(currentQueue.get(index));
                                          if (current != null && Objects.equals(current.songUrl, original.songUrl)) {
                                             ServerPlayer currentOwner = level.getServer().getPlayerList().getPlayer(ownerId);
                                             if (!isPlaybackAllowed(level, original.songUrl, currentOwner)) {
                                                RESOLVE_INTENTS.complete(sourceId, intent);
                                             } else {
                                                String rawUrl = original.songUrl != null ? original.songUrl : "";
                                                String playUrl = resolved.songUrl != null && !resolved.songUrl.isBlank() ? resolved.songUrl : rawUrl;
                                                if (BiliApiClient.isStoredVideoSelection(rawUrl)) {
                                                   playUrl = rawUrl;
                                                }

                                                if (playUrl.isBlank()) {
                                                   RESOLVE_INTENTS.complete(sourceId, intent);
                                                } else {
                                                   String songName = resolved.songName != null && !resolved.songName.isBlank()
                                                      ? resolved.songName
                                                      : original.songName;
                                                   int durationSeconds = Math.max(1, resolved.songTime > 0 ? resolved.songTime : original.songTime);
                                                   long elapsedMillis = clampElapsed(targetMillis, durationSeconds);
                                                   UUID deviceId = MP4Item.readDeviceId(stack);
                                                   if (deviceId != null && deviceId.equals(sourceId) && RESOLVE_INTENTS.isCurrent(sourceId, intent)) {
                                                      PlaybackSessionId playbackSessionId = PlaybackSessionId.of(deviceId + "-mp4-" + System.nanoTime());
                                                      String syncedPlayUrl = PlaybackSync.withSync(
                                                         playUrl, playbackSessionId, elapsedMillis, durationSeconds * 1000L
                                                      );
                                                      long gameTime = MonotonicMediaClock.nowTick();
                                                      MP4PlaybackSyncManager.Session session = new MP4PlaybackSyncManager.Session(
                                                         level.dimension(),
                                                         ownerId,
                                                         deviceId,
                                                         sourceType,
                                                         sourceEntityId,
                                                         sourcePos,
                                                         containerSlot,
                                                         index,
                                                         syncedPlayUrl,
                                                         rawUrl,
                                                         songName == null ? "" : songName,
                                                         durationSeconds,
                                                         state.volumePerMille(),
                                                         playbackSessionId,
                                                         gameTime - Math.round(elapsedMillis / 50.0),
                                                         gameTime,
                                                         gameTime
                                                      );
                                                      SESSION_REGISTRY.replace(deviceId, session);
                                                      RESOLVE_INTENTS.complete(deviceId, intent);
                                                      session.recordAudit(level, gameTime);
                                                      PROGRESS_PERSISTENCE.persist(stack, session, gameTime, true);
                                                      AUDIENCE_BROADCASTER.broadcast(level, session, gameTime);
                                                   } else {
                                                      RESOLVE_INTENTS.complete(sourceId, intent);
                                                   }
                                                }
                                             }
                                          } else {
                                             RESOLVE_INTENTS.complete(sourceId, intent);
                                          }
                                       } else {
                                          RESOLVE_INTENTS.complete(sourceId, intent);
                                       }
                                    } else {
                                       RESOLVE_INTENTS.complete(sourceId, intent);
                                    }
                                 }
                              },
                              level.getServer()
                           );
                     }
                  }
               }
            }
         }
      }
   }

   public static void stopExternalPlaybackForLinkedHeadphones(ServerPlayer actor, UUID deviceId) {
      AUDIENCE_BROADCASTER.stopExternalPlaybackForLinkedHeadphones(actor, deviceId);
   }

   private static boolean isPlaybackAllowed(ServerLevel level, String sourceUrl, ServerPlayer actor) {
      if (BiliSongInfoSanitizer.isForbiddenBiliDirectUrl(sourceUrl)) {
         if (actor != null) {
            actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
         }

         return false;
      } else if (BiliWhitelistManager.enabled() && !BiliWhitelistManager.canonicalResource(sourceUrl).isEmpty()) {
         if (BiliWhitelistManager.isAllowed(level.getServer(), sourceUrl)) {
            return true;
         } else {
            if (actor != null) {
               actor.sendSystemMessage(BiliWhitelistManager.denialMessage(actor, sourceUrl, "播放"));
            }

            return false;
         }
      } else {
         return true;
      }
   }

   private static boolean clearMp4LinksFromPlayer(ServerPlayer player, UUID deviceId) {
      boolean[] changedEquipped = new boolean[]{false};
      EquippedMediaItems.forEachEquipped(player, stack -> changedEquipped[0] |= clearMp4Link(stack, deviceId));
      boolean changed = changedEquipped[0];
      ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
      changed |= clearMp4Link(carried, deviceId);
      Inventory inventory = player.getInventory();

      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         changed |= clearMp4Link(inventory.getItem(slot), deviceId);
      }

      if (changed) {
         inventory.setChanged();
         AudioLinkIndex.updatePlayerHeadphones(player);
      }

      return changed;
   }

   private static boolean clearMp4Link(ItemStack stack, UUID deviceId) {
      if (HeadphoneAbility.has(stack) && AudioLinkData.headphoneLinkedToMp4(stack, deviceId)) {
         AudioLinkData.clearHeadphoneMp4(stack);
         return true;
      } else {
         return false;
      }
   }

   private static boolean clearHolographicGlassesLinksFromPlayer(ServerPlayer player, UUID deviceId) {
      boolean[] changedEquipped = new boolean[]{false};
      EquippedMediaItems.forEachEquipped(player, stack -> changedEquipped[0] |= clearHolographicGlassesLink(stack, deviceId));
      boolean changed = changedEquipped[0];
      ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
      changed |= clearHolographicGlassesLink(carried, deviceId);
      Inventory inventory = player.getInventory();

      for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
         changed |= clearHolographicGlassesLink(inventory.getItem(slot), deviceId);
      }

      if (changed) {
         inventory.setChanged();
         if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
         }
      }

      return changed;
   }

   private static boolean clearHolographicGlassesLink(ItemStack stack, UUID deviceId) {
      if (!HolographicGlassesItem.boundToMediaDevice(stack, deviceId)) {
         return false;
      } else {
         HolographicGlassesItem.clearBoundMediaDevice(stack, deviceId);
         return true;
      }
   }

   private static long clampElapsed(long elapsedMillis, int durationSeconds) {
      long max = Math.max(0L, Math.max(1, durationSeconds) * 1000L - 50L);
      return Math.max(0L, Math.min(max, elapsedMillis));
   }

   private static MP4Item.State deviceState(ServerLevel level, ItemStack stack, UUID deviceId) {
      return deviceId == null ? MP4Item.State.DEFAULT : MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state();
   }

   private static boolean isActiveMediaStack(ServerLevel level, ItemStack stack, UUID deviceId) {
      return stack.getItem() instanceof MP4Item
         ? deviceState(level, stack, deviceId).playing()
         : PadItem.isPad(stack) && deviceId != null && deviceId.equals(PadItem.readDeviceId(stack));
   }

   record Session(
      ResourceKey<Level> levelKey,
      UUID ownerId,
      UUID sourceId,
      int sourceType,
      int sourceEntityId,
      BlockPos sourcePos,
      int containerSlot,
      int queueIndex,
      String playUrl,
      String rawUrl,
      String songName,
      int durationSeconds,
      int volumePerMille,
      PlaybackSessionId playbackSessionId,
      long startedGameTime,
      long lastSyncGameTime,
      long lastFullSyncGameTime
   ) {
      Session(
         ResourceKey<Level> levelKey,
         UUID ownerId,
         UUID sourceId,
         int sourceType,
         int sourceEntityId,
         BlockPos sourcePos,
         int containerSlot,
         int queueIndex,
         String playUrl,
         String rawUrl,
         String songName,
         int durationSeconds,
         int volumePerMille,
         PlaybackSessionId playbackSessionId,
         long startedGameTime,
         long lastSyncGameTime,
         long lastFullSyncGameTime
      ) {
         playbackSessionId = Objects.requireNonNull(playbackSessionId, "playbackSessionId");
         this.levelKey = levelKey;
         this.ownerId = ownerId;
         this.sourceId = sourceId;
         this.sourceType = sourceType;
         this.sourceEntityId = sourceEntityId;
         this.sourcePos = sourcePos;
         this.containerSlot = containerSlot;
         this.queueIndex = queueIndex;
         this.playUrl = playUrl;
         this.rawUrl = rawUrl;
         this.songName = songName;
         this.durationSeconds = durationSeconds;
         this.volumePerMille = volumePerMille;
         this.playbackSessionId = playbackSessionId;
         this.startedGameTime = startedGameTime;
         this.lastSyncGameTime = lastSyncGameTime;
         this.lastFullSyncGameTime = lastFullSyncGameTime;
      }

      Session(
         ResourceKey<Level> levelKey,
         UUID ownerId,
         UUID sourceId,
         int sourceType,
         int sourceEntityId,
         BlockPos sourcePos,
         int containerSlot,
         int queueIndex,
         String playUrl,
         String rawUrl,
         String songName,
         int durationSeconds,
         int volumePerMille,
         String sessionId,
         long startedGameTime,
         long lastSyncGameTime,
         long lastFullSyncGameTime
      ) {
         this(
            levelKey,
            ownerId,
            sourceId,
            sourceType,
            sourceEntityId,
            sourcePos,
            containerSlot,
            queueIndex,
            playUrl,
            rawUrl,
            songName,
            durationSeconds,
            volumePerMille,
            PlaybackSessionId.of(sessionId),
            startedGameTime,
            lastSyncGameTime,
            lastFullSyncGameTime
         );
      }

      String sessionId() {
         return this.playbackSessionId.value();
      }

      long elapsedMillis(long gameTime) {
         return Math.min(this.durationSeconds * 1000L, Math.max(0L, (gameTime - this.startedGameTime) * 50L));
      }

      MP4PlaybackSyncManager.Session withVolume(int newVolumePerMille) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            Math.max(0, Math.min(1000, newVolumePerMille)),
            this.playbackSessionId,
            this.startedGameTime,
            this.lastSyncGameTime,
            this.lastFullSyncGameTime
         );
      }

      MP4PlaybackSyncManager.Session withLastSyncGameTime(long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            this.lastFullSyncGameTime
         );
      }

      MP4PlaybackSyncManager.Session withLastFullSyncGameTime(long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            this.lastSyncGameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session withStartedGameTime(long newStartedGameTime, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            newStartedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session withRefreshedPlayback(
         String refreshedPlayUrl, String refreshedSongName, int refreshedDurationSeconds, long elapsedMillis, long gameTime
      ) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            refreshedPlayUrl,
            this.rawUrl,
            refreshedSongName != null ? refreshedSongName : this.songName,
            Math.max(1, refreshedDurationSeconds),
            this.volumePerMille,
            this.playbackSessionId,
            gameTime - Math.round(Math.max(0L, elapsedMillis) / 50.0),
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session withQueueIndex(int newQueueIndex, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            Math.max(0, newQueueIndex),
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session resetToStart(long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            this.sourceType,
            this.sourceEntityId,
            this.sourcePos,
            this.containerSlot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            gameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session asItemSource(int itemEntityId, BlockPos itemPos, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            1,
            itemEntityId,
            itemPos.immutable(),
            -1,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session asPlayerSource(ServerPlayer player, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            player.level().dimension(),
            player.getUUID(),
            this.sourceId,
            0,
            player.getId(),
            player.blockPosition(),
            -1,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session asBlockSource(BlockPos pos, int slot, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            this.ownerId,
            this.sourceId,
            2,
            -1,
            pos.immutable(),
            slot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session asContainerEntitySource(ServerLevel level, Entity entity, int slot, long gameTime) {
         return new MP4PlaybackSyncManager.Session(
            level.dimension(),
            this.ownerId,
            this.sourceId,
            3,
            entity.getId(),
            entity.blockPosition(),
            slot,
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      MP4PlaybackSyncManager.Session fromResolved(MP4DeviceLocationIndex.ResolvedLocation resolved, long gameTime) {
         UUID resolvedOwnerId = resolved.sourceType() == 0 && resolved.ownerId() != null ? resolved.ownerId() : this.ownerId;
         return new MP4PlaybackSyncManager.Session(
            this.levelKey,
            resolvedOwnerId,
            this.sourceId,
            resolved.sourceType(),
            resolved.sourceEntityId(),
            resolved.sourcePos().immutable(),
            resolved.containerSlot(),
            this.queueIndex,
            this.playUrl,
            this.rawUrl,
            this.songName,
            this.durationSeconds,
            this.volumePerMille,
            this.playbackSessionId,
            this.startedGameTime,
            gameTime,
            gameTime
         );
      }

      void markContainerChanged() {
         ServerLevel level = this.currentLevel();
         if (level != null) {
            if (this.sourceType == 2 && level.getBlockEntity(this.sourcePos) instanceof Container container) {
               container.setChanged();
            } else {
               if (this.sourceType == 3 && level.getEntity(this.sourceEntityId) instanceof Container container) {
                  container.setChanged();
               }
            }
         }
      }

      ServerLevel currentLevel() {
         MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
         return server != null ? server.getLevel(this.levelKey) : null;
      }

      ItemStack stack(ServerLevel level) {
         MP4DeviceLocationIndex.ResolvedLocation indexed = MP4DeviceLocationIndex.resolve(level, this.sourceId).orElse(null);
         if (indexed != null && indexed.stack().getItem() instanceof MP4Item) {
            return indexed.stack();
         } else if (this.sourceType == 1 && level.getEntity(this.sourceEntityId) instanceof ItemEntity item) {
            MP4DeviceLocationIndex.recordItemEntity(level, item, this.sourceId);
            return item.getItem();
         } else if (this.sourceType == 2
            && level.getBlockEntity(this.sourcePos) instanceof Container container
            && this.containerSlot >= 0
            && this.containerSlot < container.getContainerSize()) {
            MP4DeviceLocationIndex.recordBlockContainer(level, this.sourcePos, this.containerSlot, this.sourceId);
            return container.getItem(this.containerSlot);
         } else {
            if (this.sourceType == 3) {
               Entity candidate = level.getEntity(this.sourceEntityId);
               if (candidate instanceof Entity
                  && candidate instanceof Container container
                  && this.containerSlot >= 0
                  && this.containerSlot < container.getContainerSize()) {
                  MP4DeviceLocationIndex.recordContainerEntity(level, candidate, this.containerSlot, this.sourceId);
                  return container.getItem(this.containerSlot);
               }
            }

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(this.sourceId);
            if (player == null) {
               for (ServerPlayer candidate : level.players()) {
                  ItemStack stack = MP4Item.findByDeviceId(candidate, this.sourceId);
                  if (stack.getItem() instanceof MP4Item) {
                     return stack;
                  }

                  ItemStack padStack = PadItem.findByDeviceId(candidate, this.sourceId);
                  if (PadItem.isPad(padStack)) {
                     return padStack;
                  }
               }

               return ItemStack.EMPTY;
            } else {
               ItemStack stackx = MP4Item.findByDeviceId(player, this.sourceId);
               return stackx.getItem() instanceof MP4Item ? stackx : PadItem.findByDeviceId(player, this.sourceId);
            }
         }
      }

      MP4PlaybackSyncManager.SourcePosition sourcePosition(ServerLevel level) {
         if (this.sourceType == 1 && level.getEntity(this.sourceEntityId) instanceof ItemEntity item) {
            MP4DeviceLocationIndex.recordItemEntity(level, item, this.sourceId);
            return new MP4PlaybackSyncManager.SourcePosition(item.getId(), item.getX(), item.getY() + 0.25, item.getZ());
         } else if (this.sourceType == 2) {
            return new MP4PlaybackSyncManager.SourcePosition(-1, this.sourcePos.getX() + 0.5, this.sourcePos.getY() + 0.5, this.sourcePos.getZ() + 0.5);
         } else {
            if (this.sourceType == 3) {
               Entity var5 = level.getEntity(this.sourceEntityId);
               if (var5 instanceof Entity) {
                  MP4DeviceLocationIndex.recordContainerEntity(level, var5, this.containerSlot, this.sourceId);
                  return new MP4PlaybackSyncManager.SourcePosition(var5.getId(), var5.getX(), var5.getY() + 0.5, var5.getZ());
               }
            }

            ServerPlayer player = this.ownerId != null ? level.getServer().getPlayerList().getPlayer(this.ownerId) : null;
            return player != null
               ? new MP4PlaybackSyncManager.SourcePosition(player.getId(), player.getX(), player.getY() + 1.2, player.getZ())
               : new MP4PlaybackSyncManager.SourcePosition(
                  this.sourceEntityId, this.sourcePos.getX() + 0.5, this.sourcePos.getY() + 0.5, this.sourcePos.getZ() + 0.5
               );
         }
      }

      void recordAudit(ServerLevel level, long gameTime) {
         MP4PlaybackSyncManager.SourcePosition position = this.sourcePosition(level);
         PlaybackAuditManager.recordMp4(
            level,
            this.sourceId,
            BlockPos.containing(position.x(), position.y(), position.z()),
            position.x(),
            position.y() + 0.65,
            position.z(),
            this.songName,
            this.rawUrl,
            this.durationSeconds,
            this.elapsedMillis(gameTime),
            this.ownerId
         );
      }
   }

   record SourcePosition(int entityId, double x, double y, double z) {
   }
}
