package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioHandler;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioPlaybackRange;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackApproachPredictor;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class ClientAudioOutputRegistry {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_ACTIVE_TURNTABLES = 16;
   private static final long CLEANUP_TIMEOUT_MILLIS = 2000L;
   private static final AtomicInteger ANONYMOUS_COUNTER = new AtomicInteger();
   private static final BoundedConcurrentStore<BlockPos, ClientAudioOutputRegistry.AudioEntry> OUTPUTS = new BoundedConcurrentStore<>(16);
   private static final ConcurrentMap<UUID, BlockPos> MINECART_KEYS = new ConcurrentHashMap<>();
   private static final ConcurrentMap<BlockPos, SpeakerAudioRelay> RELAYS = new ConcurrentHashMap<>();
   private static final ConcurrentMap<BlockPos, BlockPos> RELAY_TURNTABLE = new ConcurrentHashMap<>();
   private static final PersistentRouteOwners<BlockPos> CONSOLE_ROUTE_OWNERS = new PersistentRouteOwners<>();
   private static volatile float[] listenerPos;
   private static volatile boolean paused;

   public static void register(DolbyAudioHandler handler) {
      register(handler, null);
   }

   public static void register(DolbyAudioHandler handler, BlockPos pos) {
      register(handler, pos, 0.0F);
   }

   public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds) {
      register(handler, pos, startOffsetSeconds, "");
   }

   public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId) {
      register(handler, pos, startOffsetSeconds, sessionId, null);
   }

   public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId, UUID ownerId) {
      if (handler != null) {
         Optional<PlaybackSessionId> playbackSessionId = PlaybackSessionId.parse(sessionId);
         BlockPos key = keyFor(pos, ownerId, playbackSessionId);
         String normalizedSessionId = playbackSessionId.<String>map(session -> session.value()).orElse("");
         if (!ClientAudioOutputPolicy.isCurrentSession(pos, normalizedSessionId)) {
            handler.hardStopOutput();
            handler.cleanup();
         } else {
            ClientAudioOutputRegistry.AudioEntry entry = new ClientAudioOutputRegistry.AudioEntry(
               key,
               centerFor(pos),
               handler,
               ClientAudioOutputRegistry.OutputKind.DOLBY,
               System.currentTimeMillis(),
               startOffsetTicks(startOffsetSeconds),
               playbackSessionId,
               ownerId
            );
            handler.setConsoleRouteSuppressed(isMainRouteSuppressed(pos));
            handler.setPaused(paused);
            handler.setUserVolume(ownerId != null ? ClientAudioOwnerVolumes.getOrDefault(ownerId, 1.0F) : ClientAudioOutputPolicy.volume(pos));
            cleanupAfterPut(OUTPUTS.put(key, entry, entry.createdAtMillis()));
            connectPendingRelays(key, handler);
         }
      }
   }

   public static void unregister(DolbyAudioHandler handler) {
      if (handler != null) {
         unregisterOutput(handler);
      }
   }

   public static void registerStereo(StereoOpenALHandler handler) {
      registerStereo(handler, null);
   }

   public static void registerStereo(StereoOpenALHandler handler, BlockPos pos) {
      registerStereo(handler, pos, 0.0F);
   }

   public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds) {
      registerStereo(handler, pos, startOffsetSeconds, "");
   }

   public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId) {
      registerStereo(handler, pos, startOffsetSeconds, sessionId, null);
   }

   public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId, UUID ownerId) {
      if (handler != null) {
         Optional<PlaybackSessionId> playbackSessionId = PlaybackSessionId.parse(sessionId);
         BlockPos key = keyFor(pos, ownerId, playbackSessionId);
         String normalizedSessionId = playbackSessionId.<String>map(session -> session.value()).orElse("");
         if (!ClientAudioOutputPolicy.isCurrentSession(pos, normalizedSessionId)) {
            handler.hardStopOutput();
            handler.cleanup();
         } else {
            ClientAudioOutputRegistry.AudioEntry entry = new ClientAudioOutputRegistry.AudioEntry(
               key,
               centerFor(pos),
               handler,
               ClientAudioOutputRegistry.OutputKind.STEREO,
               System.currentTimeMillis(),
               startOffsetTicks(startOffsetSeconds),
               playbackSessionId,
               ownerId
            );
            handler.setConsoleRouteSuppressed(isMainRouteSuppressed(pos));
            handler.setPaused(paused);
            handler.setUserVolume(ownerId != null ? ClientAudioOwnerVolumes.getOrDefault(ownerId, 1.0F) : ClientAudioOutputPolicy.volume(pos));
            cleanupAfterPut(OUTPUTS.put(key, entry, entry.createdAtMillis()));
            connectPendingRelays(key, handler);
         }
      }
   }

   private static void connectPendingRelays(BlockPos handlerKey, AudioOutputHandle handler) {
      for (Entry<BlockPos, SpeakerAudioRelay> entry : RELAYS.entrySet()) {
         BlockPos speakerPos = entry.getKey();
         BlockPos linkedTurntable = RELAY_TURNTABLE.get(speakerPos);
         if (linkedTurntable != null && linkedTurntable.equals(handlerKey)) {
            handler.addRelay(entry.getValue());
         }
      }

      handler.setConsoleRouteSuppressed(isMainRouteSuppressed(handlerKey));
   }

   public static void unregisterStereo(StereoOpenALHandler handler) {
      if (handler != null) {
         unregisterOutput(handler);
      }
   }

   private static void unregisterOutput(AudioOutputHandle handler) {
      for (ClientAudioOutputRegistry.AudioEntry removed : OUTPUTS.removeIf(entry -> entry.output() == handler)) {
         releaseMinecartKeyIfUnused(removed.pos());
      }
   }

   public static void updateListenerPosition(float[] listenerPos) {
      if (listenerPos != null) {
         ClientAudioOutputRegistry.listenerPos = AudioUtils.copyPos3(listenerPos);
      }
   }

   public static void updatePositions(float[] listenerPos) {
      if (listenerPos != null) {
         float[] currentListenerPos = AudioUtils.copyPos3(listenerPos);
         ClientAudioOutputRegistry.listenerPos = currentListenerPos;
         long nowNanos = System.nanoTime();
         RELAYS.forEach((outputKey, relay) -> relay.setAreaGain(ClientAreaAudioZoneRegistry.gain(outputKey, nowNanos)));

         for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
            if (!discardIfStaleOutput(entry)) {
               if (entry.ownerId() == null) {
                  entry.output().setUserVolume(ClientAudioOutputPolicy.volume(entry.pos()) * areaGainForMainOutput(entry.pos(), nowNanos));
               }

               if (isRealWorldKey(entry.pos()) && HeadphoneClientState.linkedTurntableOutOfRange(entry.pos())) {
                  if (OUTPUTS.remove(entry.pos(), entry)) {
                     hardStopAndCleanup(entry);
                  }
               } else if (isRealWorldKey(entry.pos()) && HeadphoneClientState.suppressesTurntable(entry.pos())) {
                  entry.output().setUserVolume(0.0F);
                  entry.output().tick(entry.machinePos(), currentListenerPos, Long.MAX_VALUE, false, true);
               } else {
                  float[] pos = resolveMachinePos(entry);
                  if (isRealWorldKey(entry.pos()) && HeadphoneClientState.handlesTurntable(entry.pos())) {
                     pos = currentListenerPos;
                  }

                  boolean followLocalPlayerFront = followsLocalPlayerFront(entry);
                  entry.output()
                     .tick(
                        pos,
                        currentListenerPos,
                        targetRelativeTicks(entry),
                        followLocalPlayerFront,
                        AudioRelayRoutingPolicy.muteWorldRelays(
                           isRealWorldKey(entry.pos()) && HeadphoneClientState.handlesTurntable(entry.pos()), false, followLocalPlayerFront
                        )
                     );
               }
            }
         }
      }
   }

   public static void setPaused(boolean paused) {
      ClientAudioOutputRegistry.paused = paused;

      for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
         entry.output().setPaused(paused);
      }

      for (SpeakerAudioRelay relay : RELAYS.values()) {
         relay.setPaused(paused);
      }
   }

   private static boolean followsLocalPlayerFront(ClientAudioOutputRegistry.AudioEntry entry) {
      if (entry != null && isRealWorldKey(entry.pos()) && HeadphoneClientState.handlesTurntable(entry.pos())) {
         return true;
      } else if (entry == null || entry.ownerId() == null) {
         return false;
      } else if (ClientMediaPlayback.sourcePosition(entry.ownerId()) != null) {
         return ClientMediaPlayback.followsLocalPlayerFront(entry.ownerId());
      } else {
         Minecraft mc = Minecraft.getInstance();
         return mc != null && mc.player != null && entry.ownerId().equals(mc.player.getUUID());
      }
   }

   private static long targetRelativeTicks(ClientAudioOutputRegistry.AudioEntry entry) {
      return entry != null ? ClientAudioOutputPolicy.targetRelativeTicks(entry.pos(), entry.sessionId(), entry.startOffsetTicks()) : Long.MAX_VALUE;
   }

   private static boolean discardIfStaleOutput(ClientAudioOutputRegistry.AudioEntry entry) {
      if (entry != null && isRealWorldKey(entry.pos()) && !entry.sessionId().isBlank()) {
         if (ClientAudioOutputPolicy.isCurrentSession(entry.pos(), entry.sessionId())) {
            return false;
         } else {
            if (OUTPUTS.remove(entry.pos(), entry)) {
               LOGGER.debug("丢弃等待新音频流期间的旧输出: pos={} oldSession={}", entry.pos(), entry.sessionId());
               hardStopAndCleanup(entry);
            }

            return true;
         }
      } else {
         return false;
      }
   }

   private static long startOffsetTicks(float startOffsetSeconds) {
      return Math.max(0L, Math.round(Math.max(0.0F, startOffsetSeconds) * 20.0));
   }

   private static float[] resolveMachinePos(BlockPos handlerKey, float[] originalPos, UUID ownerId) {
      if (ownerId != null) {
         Vec3 mp4Pos = ClientMediaPlayback.sourcePosition(ownerId);
         if (mp4Pos != null) {
            return new float[]{(float)mp4Pos.x, (float)mp4Pos.y, (float)mp4Pos.z};
         }

         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.level != null) {
            Player owner = mc.level.getPlayerByUUID(ownerId);
            if (owner != null) {
               return new float[]{(float)owner.getX(), (float)(owner.getY() + 1.2), (float)owner.getZ()};
            }
         }
      }

      return originalPos;
   }

   private static float[] resolveMachinePos(ClientAudioOutputRegistry.AudioEntry entry) {
      Vec3 movingPos = ClientMinecartAudioAnchors.position(entry.sessionId());
      return movingPos != null
         ? new float[]{(float)movingPos.x, (float)movingPos.y, (float)movingPos.z}
         : resolveMachinePos(entry.pos(), entry.machinePos(), entry.ownerId());
   }

   public static void clearMachineOverrideForSpeaker(BlockPos speakerPos) {
      if (speakerPos != null) {
         BlockPos turntablePos = RELAY_TURNTABLE.remove(speakerPos);
         SpeakerAudioRelay relay = RELAYS.remove(speakerPos);
         if (relay != null) {
            for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
               entry.output().removeRelay(relay);
            }

            relay.cleanup();
         }

         refreshMainRouteSuppression(turntablePos);
      }
   }

   public static void registerConsoleRelay(
      BlockPos consoleElementKey, BlockPos turntablePos, float[] worldPos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance
   ) {
      if (consoleElementKey != null && turntablePos != null && worldPos != null) {
         BlockPos previousSource = RELAY_TURNTABLE.get(consoleElementKey);
         if (previousSource != null && !previousSource.equals(turntablePos)) {
            clearMachineOverrideForSpeaker(consoleElementKey);
         }

         ClientAudioOutputRegistry.AudioEntry output = OUTPUTS.get(keyFor(turntablePos));
         if (output != null) {
            output.output().setConsoleRouteSuppressed(true);
         }

         SpeakerAudioRelay relay = RELAYS.get(consoleElementKey);
         if (relay == null) {
            relay = new SpeakerAudioRelay();
            relay.setChannelIndex(channelIndex);
            relay.setAutoMixJoc(autoMixJoc);
            relay.setUserVolume(volume);
            relay.setMaxDistance(maxDistance);
            registerRelay(consoleElementKey, turntablePos, relay);
         } else {
            relay.setChannelIndex(channelIndex);
            relay.setAutoMixJoc(autoMixJoc);
            relay.setUserVolume(volume);
            relay.setMaxDistance(maxDistance);
         }

         relay.setSpeakerPos(worldPos);
      }
   }

   public static void unregisterConsoleRelay(BlockPos consoleElementKey) {
      BlockPos turntablePos = RELAY_TURNTABLE.get(consoleElementKey);
      clearMachineOverrideForSpeaker(consoleElementKey);
      refreshMainRouteSuppression(turntablePos);
   }

   public static void bindConsoleRoute(BlockPos consolePos, BlockPos turntablePos) {
      if (consolePos != null && turntablePos != null) {
         PersistentRouteOwners.Change<BlockPos> change = CONSOLE_ROUTE_OWNERS.bind(consolePos.immutable(), turntablePos.immutable());
         if (change.previousSource() != null && !change.previousSource().equals(change.currentSource())) {
            refreshMainRouteSuppression(change.previousSource());
         }

         refreshMainRouteSuppression(change.currentSource());
      }
   }

   public static void unbindConsoleRoute(BlockPos consolePos) {
      if (consolePos != null) {
         refreshMainRouteSuppression(CONSOLE_ROUTE_OWNERS.unbind(consolePos.immutable()));
      }
   }

   public static void registerRelay(BlockPos speakerPos, BlockPos turntablePos, SpeakerAudioRelay relay) {
      if (speakerPos != null && turntablePos != null && relay != null) {
         relay.setSpeakerPos(AudioUtils.centerFor(speakerPos));
         relay.setPaused(paused);
         SpeakerAudioRelay old = RELAYS.put(speakerPos, relay);
         RELAY_TURNTABLE.put(speakerPos, turntablePos);
         if (old != null) {
            for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
               entry.output().removeRelay(old);
            }

            old.cleanup();
         }

         BlockPos key = keyFor(turntablePos);
         ClientAudioOutputRegistry.AudioEntry output = OUTPUTS.get(key);
         if (output != null) {
            output.output().addRelay(relay);
         }

         refreshMainRouteSuppression(turntablePos);
      }
   }

   public static void updateRelayConfig(BlockPos speakerPos, int channelIndex, float volume, boolean autoMixJoc) {
      updateRelayConfig(speakerPos, channelIndex, volume, autoMixJoc, 64.0F);
   }

   public static boolean hasRelayAt(BlockPos speakerPos) {
      return speakerPos != null && RELAYS.containsKey(speakerPos);
   }

   public static void updateRelayConfig(BlockPos speakerPos, int channelIndex, float volume, boolean autoMixJoc, float maxDistance) {
      if (speakerPos != null) {
         SpeakerAudioRelay relay = RELAYS.get(speakerPos);
         if (relay != null) {
            relay.setChannelIndex(channelIndex);
            relay.setAutoMixJoc(autoMixJoc);
            relay.setUserVolume(volume);
            relay.setMaxDistance(maxDistance);
            refreshMainRouteSuppression(RELAY_TURNTABLE.get(speakerPos));
         }
      }
   }

   private static boolean isMainRouteSuppressed(BlockPos turntablePos) {
      if (turntablePos == null) {
         return false;
      } else {
         BlockPos source = turntablePos.immutable();
         if (CONSOLE_ROUTE_OWNERS.hasOwners(source)) {
            return true;
         } else {
            for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
               if (source.equals(entry.getValue())) {
                  SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
                  if (relay != null && relay.takesOverMainOutput()) {
                     return true;
                  }
               }
            }

            return false;
         }
      }
   }

   private static void refreshMainRouteSuppression(BlockPos turntablePos) {
      if (turntablePos != null) {
         ClientAudioOutputRegistry.AudioEntry output = OUTPUTS.get(keyFor(turntablePos));
         if (output != null) {
            output.output().setConsoleRouteSuppressed(isMainRouteSuppressed(turntablePos));
         }
      }
   }

   public static void updateRelayRangeGain(BlockPos speakerPos, float rangeGain) {
      if (speakerPos != null) {
         SpeakerAudioRelay relay = RELAYS.get(speakerPos);
         if (relay != null) {
            relay.setRangeGain(rangeGain);
         }
      }
   }

   public static void updateRelayPreparationDemand(BlockPos speakerPos, boolean preparationDemand) {
      if (speakerPos != null) {
         SpeakerAudioRelay relay = RELAYS.get(speakerPos);
         if (relay != null) {
            relay.setPreparationDemand(preparationDemand);
         }
      }
   }

   public static float areaGainForSource(BlockPos sourcePos, long nowNanos) {
      if (sourcePos != null && !HeadphoneClientState.handlesTurntable(sourcePos)) {
         float best = isMainRouteSuppressed(sourcePos) ? 0.0F : ClientAreaAudioZoneRegistry.gain(sourcePos, nowNanos);

         for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
            if (sourcePos.equals(entry.getValue())) {
               best = Math.max(best, ClientAreaAudioZoneRegistry.gain(entry.getKey(), nowNanos));
            }
         }

         return best;
      } else {
         return 1.0F;
      }
   }

   private static float areaGainForMainOutput(BlockPos sourcePos, long nowNanos) {
      if (sourcePos != null && HeadphoneClientState.handlesTurntable(sourcePos)) {
         return 1.0F;
      } else {
         return sourcePos != null && isRealWorldKey(sourcePos) ? ClientAreaAudioZoneRegistry.gain(sourcePos, nowNanos) : 1.0F;
      }
   }

   public static void applySpeakerConfig(BlockPos turntablePos, int channelMask, float volume, boolean autoMixJoc) {
      if (turntablePos != null) {
         BlockPos key = keyFor(turntablePos);
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
         if (entry != null) {
            entry.output().setUserVolume(volume);
            if (entry.output() instanceof DolbyAudioHandler dolby) {
               dolby.setChannelMask(channelMask);
               dolby.setForceStaticJoc(autoMixJoc);
            }
         }
      }
   }

   public static float[] getListenerPos() {
      return AudioUtils.copyPos3(listenerPos);
   }

   public static boolean hasAudioDemand(BlockPos sourcePos, PlaybackSourceId sourceId, String sessionId) {
      return audioDemandDebug(sourcePos, sourceId, sessionId).demand();
   }

   public static boolean hasGeometricAudioDemand(BlockPos sourcePos, PlaybackSourceId sourceId, String sessionId) {
      ClientAudioOutputRegistry.AudioDemandDebug actual = audioDemandDebug(sourcePos, sourceId, sessionId);
      if (actual.audioEnabled() && actual.listenerPresent() && sourcePos != null) {
         if (!actual.headphone() && (sourceId == null || ClientAudioEndpointIndex.geometricDemands(sourceId).isEmpty())) {
            float[] listener = currentListenerPosition();
            if (listener == null) {
               return false;
            } else {
               if (!actual.mainSuppressed() && actual.sourceVolume() > 0.0F) {
                  float[] sourcePosition = AudioUtils.centerFor(sourcePos);
                  Vec3 movingPosition = ClientMinecartAudioAnchors.position(sessionId);
                  if (movingPosition != null) {
                     sourcePosition = new float[]{(float)movingPosition.x, (float)movingPosition.y, (float)movingPosition.z};
                  }

                  if (AudioPlaybackRange.evaluateSphere(AudioUtils.distance(listener, sourcePosition), 64.0F, actual.sourceVolume(), false).audible()) {
                     return true;
                  }
               }

               for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
                  if (sourcePos.equals(entry.getValue())) {
                     SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
                     if (relay != null && relay.hasGeometricDemand(listener)) {
                        return true;
                     }
                  }
               }

               return false;
            }
         } else {
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean hasPreparationDemand(BlockPos sourcePos, PlaybackSourceId sourceId, String sessionId) {
      ClientAudioOutputRegistry.AudioDemandDebug actual = audioDemandDebug(sourcePos, sourceId, sessionId);
      if (!actual.demand() && sourcePos != null && actual.audioEnabled() && actual.listenerPresent()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null) {
            return false;
         } else {
            Vec3 movement = minecraft.player.getDeltaMovement();
            float[] velocity = new float[]{(float)movement.x, (float)movement.y, (float)movement.z};
            float[] listener = currentListenerPosition();
            if (listener == null) {
               return false;
            } else if (sourceId != null && !ClientAudioEndpointIndex.geometricDemands(sourceId).isEmpty()) {
               return true;
            } else {
               if (!actual.mainSuppressed() && actual.sourceVolume() > 0.0F) {
                  float[] sourcePosition = AudioUtils.centerFor(sourcePos);
                  Vec3 movingPosition = ClientMinecartAudioAnchors.position(sessionId);
                  if (movingPosition != null) {
                     sourcePosition = new float[]{(float)movingPosition.x, (float)movingPosition.y, (float)movingPosition.z};
                  }

                  if (AudioPlaybackRange.evaluateSphere(AudioUtils.distance(listener, sourcePosition), 64.0F, actual.sourceVolume(), false).audible()) {
                     return true;
                  }
               }

               for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
                  if (sourcePos.equals(entry.getValue())) {
                     SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
                     if (relay != null && relay.hasGeometricDemand(listener)) {
                        return true;
                     }
                  }
               }

               if (sourceId != null && !ClientAudioEndpointIndex.anticipatedDemands(sourceId).isEmpty()) {
                  return true;
               } else {
                  if (!actual.mainSuppressed() && actual.sourceVolume() > 0.0F) {
                     float[] sourcePositionx = AudioUtils.centerFor(sourcePos);
                     Vec3 movingPositionx = ClientMinecartAudioAnchors.position(sessionId);
                     if (movingPositionx != null) {
                        sourcePositionx = new float[]{(float)movingPositionx.x, (float)movingPositionx.y, (float)movingPositionx.z};
                     }

                     AudioPlaybackRange.Profile profile = AudioPlaybackRange.profile(64.0F, actual.sourceVolume(), actual.sourceVolume());
                     if (PlaybackApproachPredictor.willEnterSphere(
                        listener[0],
                        listener[1],
                        listener[2],
                        velocity[0],
                        velocity[1],
                        velocity[2],
                        sourcePositionx[0],
                        sourcePositionx[1],
                        sourcePositionx[2],
                        profile.fadeEndDistance()
                     )) {
                        return true;
                     }
                  }

                  for (Entry<BlockPos, BlockPos> entryx : RELAY_TURNTABLE.entrySet()) {
                     if (sourcePos.equals(entryx.getValue())) {
                        SpeakerAudioRelay relay = RELAYS.get(entryx.getKey());
                        if (relay != null && relay.hasAnticipatedDemand(listener, velocity)) {
                           return true;
                        }
                     }
                  }

                  return false;
               }
            }
         }
      } else {
         return actual.demand();
      }
   }

   public static ClientAudioOutputRegistry.AudioDemandDebug audioDemandDebug(BlockPos sourcePos, PlaybackSourceId sourceId, String sessionId) {
      boolean audioEnabled = gameAudioEnabled();
      float[] listener = currentListenerPosition();
      float sourceVolume = sourcePos != null ? ClientAudioOutputPolicy.volume(sourcePos) : 0.0F;
      boolean headphone = sourcePos != null && sourceVolume > 0.0F && HeadphoneClientState.handlesTurntable(sourcePos);
      int indexedDemands = sourceId != null ? ClientAudioEndpointIndex.audibleDemands(sourceId).size() : 0;
      boolean consoleRoute = sourcePos != null && CONSOLE_ROUTE_OWNERS.hasOwners(sourcePos);
      boolean mainSuppressed = sourcePos != null && isMainRouteSuppressed(sourcePos);
      float mainDistance = Float.POSITIVE_INFINITY;
      boolean mainAudible = false;
      if (sourcePos != null && listener != null && !mainSuppressed) {
         float[] sourcePosition = AudioUtils.centerFor(sourcePos);
         Vec3 movingPosition = ClientMinecartAudioAnchors.position(sessionId);
         if (movingPosition != null) {
            sourcePosition = new float[]{(float)movingPosition.x, (float)movingPosition.y, (float)movingPosition.z};
         }

         mainDistance = AudioUtils.distance(listener, sourcePosition);
         mainAudible = ClientAreaAudioZoneRegistry.audible(sourcePos, System.nanoTime())
            && AudioPlaybackRange.evaluateSphere(mainDistance, 64.0F, sourceVolume, false).audible();
      }

      int matchingRelays = 0;
      boolean relayAudible = false;
      if (sourcePos != null) {
         for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
            if (sourcePos.equals(entry.getValue())) {
               SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
               if (relay != null) {
                  matchingRelays++;
                  relayAudible |= listener != null && relay.isAudibleAt(listener);
               }
            }
         }
      }

      boolean demand = sourcePos != null && audioEnabled && listener != null && (headphone || indexedDemands > 0 || mainAudible || relayAudible);
      return new ClientAudioOutputRegistry.AudioDemandDebug(
         demand,
         audioEnabled,
         listener != null,
         sourceVolume,
         headphone,
         indexedDemands,
         mainSuppressed,
         consoleRoute,
         mainDistance,
         mainAudible,
         matchingRelays,
         relayAudible
      );
   }

   public static boolean hasAudibleOutput(BlockPos turntablePos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null && gameAudioEnabled()) {
         float[] listener = new float[]{(float)minecraft.player.getX(), (float)minecraft.player.getEyeY(), (float)minecraft.player.getZ()};
         ClientAudioOutputRegistry.AudioEntry mainOutput = turntablePos != null ? OUTPUTS.get(keyFor(turntablePos)) : null;
         if (mainOutput != null
            && !isMainRouteSuppressed(turntablePos)
            && ClientAreaAudioZoneRegistry.audible(turntablePos, System.nanoTime())
            && mainOutput.output().getPositionMillis() >= 0L
            && AudioPlaybackRange.evaluateSphere(
                  AudioUtils.distance(listener, AudioUtils.centerFor(turntablePos)), 64.0F, ClientAudioOutputPolicy.volume(turntablePos), false
               )
               .audible()) {
            return true;
         } else {
            for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
               if (turntablePos != null && turntablePos.equals(entry.getValue())) {
                  SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
                  if (relay != null && relay.isStarted() && relay.isAudibleAt(listener)) {
                     return true;
                  }
               }
            }

            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean gameAudioEnabled() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft != null
         && minecraft.options != null
         && minecraft.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F
         && minecraft.options.getSoundSourceVolume(SoundSource.RECORDS) > 0.0F;
   }

   private static float[] currentListenerPosition() {
      float[] current = listenerPos;
      if (current != null) {
         return AudioUtils.copyPos3(current);
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         return minecraft != null && minecraft.player != null
            ? new float[]{(float)minecraft.player.getX(), (float)minecraft.player.getEyeY(), (float)minecraft.player.getZ()}
            : null;
      }
   }

   public static boolean isActive() {
      return !OUTPUTS.isEmpty();
   }

   public static void setOwnerVolume(UUID ownerId, float volume) {
      if (ownerId != null) {
         float clamped = AudioUtils.clampGain(volume);
         ClientAudioOwnerVolumes.put(ownerId, clamped);

         for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
            if (ownerId.equals(entry.ownerId())) {
               entry.output().setUserVolume(clamped);
            }
         }
      }
   }

   public static float audioLevel(BlockPos pos) {
      if (pos == null) {
         return 0.0F;
      } else {
         BlockPos key = keyFor(pos);
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
         return entry != null ? entry.output().audioLevel() : 0.0F;
      }
   }

   public static long getStereoPositionTicks(BlockPos pos) {
      if (pos == null) {
         return -1L;
      } else {
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(keyFor(pos));
         return entry != null && entry.kind() == ClientAudioOutputRegistry.OutputKind.STEREO ? entry.output().getPositionTicks() : -1L;
      }
   }

   public static long getAnyPositionTicks(BlockPos pos) {
      if (pos == null) {
         return -1L;
      } else {
         BlockPos key = keyFor(pos);
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
         return entry != null ? entry.output().getPositionTicks() : -1L;
      }
   }

   public static long getAnyMediaMillis(BlockPos pos) {
      if (pos == null) {
         return -1L;
      } else {
         BlockPos key = keyFor(pos);
         long relayMillis = getRelayMediaMillis(key);
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
         if (entry != null) {
            long positionMillis = entry.output().getPositionMillis();
            if (positionMillis >= 0L) {
               long mainMillis = adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis, entry.output().getOutputDelayMillis());
               return relayMillis >= 0L ? Math.max(mainMillis, relayMillis) : mainMillis;
            }
         }

         return relayMillis;
      }
   }

   public static ClientAudioOutputRegistry.AudioTimeline getAudioTimeline(BlockPos pos) {
      if (pos == null) {
         return ClientAudioOutputRegistry.AudioTimeline.EMPTY;
      } else {
         BlockPos key = keyFor(pos);
         ClientAudioOutputRegistry.RelayTimeline relayTimeline = getRelayTimeline(key);
         long mainMillis = -1L;
         long mainFedMillis = -1L;
         Optional<PlaybackSessionId> mainSessionId = Optional.empty();
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
         if (entry != null) {
            mainSessionId = entry.playbackSessionId();
            long positionMillis = entry.output().getPositionMillis();
            if (positionMillis >= 0L) {
               mainMillis = adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis, entry.output().getOutputDelayMillis());
            }

            long fedMillis = entry.output().getFedPositionMillis();
            if (fedMillis >= 0L) {
               mainFedMillis = Math.max(0L, startOffsetMillis(entry.startOffsetTicks()) + fedMillis);
            }
         }

         long combinedMillis = mainMillis >= 0L ? mainMillis : relayTimeline.mediaMillis();
         return new ClientAudioOutputRegistry.AudioTimeline(
            mainMillis,
            mainFedMillis,
            relayTimeline.mediaMillis(),
            combinedMillis,
            relayTimeline.startedCount(),
            relayTimeline.registeredCount(),
            mainSessionId
         );
      }
   }

   public static ClientAudioOutputRegistry.AudioTimeline getOwnerAudioTimeline(UUID ownerId) {
      if (ownerId == null) {
         return ClientAudioOutputRegistry.AudioTimeline.EMPTY;
      } else {
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.values()
            .stream()
            .filter(candidate -> ownerId.equals(candidate.ownerId()))
            .max(Comparator.comparingLong(candidate -> candidate.createdAtMillis()))
            .orElse(null);
         if (entry != null) {
            long positionMillis = entry.output().getPositionMillis();
            long audibleMillis = positionMillis >= 0L
               ? adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis, entry.output().getOutputDelayMillis())
               : -1L;
            long fedMillis = entry.output().getFedPositionMillis();
            long mainFedMillis = fedMillis >= 0L ? Math.max(0L, startOffsetMillis(entry.startOffsetTicks()) + fedMillis) : -1L;
            return new ClientAudioOutputRegistry.AudioTimeline(audibleMillis, mainFedMillis, -1L, audibleMillis, 0, 0, entry.sessionId());
         } else {
            return ClientAudioOutputRegistry.AudioTimeline.EMPTY;
         }
      }
   }

   public static Optional<StereoOpenALHandler.DiagnosticSnapshot> getOwnerStereoSnapshot(UUID ownerId) {
      return ownerId == null
         ? Optional.empty()
         : OUTPUTS.values()
            .stream()
            .filter(candidate -> ownerId.equals(candidate.ownerId()))
            .filter(candidate -> candidate.kind() == ClientAudioOutputRegistry.OutputKind.STEREO)
            .max(Comparator.comparingLong(candidate -> candidate.createdAtMillis()))
            .map(entry -> Objects.requireNonNull(entry, "audio entry").output())
            .filter(StereoOpenALHandler.class::isInstance)
            .map(output -> (StereoOpenALHandler)output)
            .map(handler -> handler.snapshot());
   }

   public static Optional<StereoOpenALHandler.DiagnosticSnapshot> getSessionStereoSnapshot(PlaybackSessionId sessionId) {
      return sessionId == null
         ? Optional.empty()
         : OUTPUTS.values()
            .stream()
            .filter(candidate -> candidate.playbackSessionId().filter(sessionId::equals).isPresent())
            .filter(candidate -> candidate.kind() == ClientAudioOutputRegistry.OutputKind.STEREO)
            .max(Comparator.comparingLong(candidate -> candidate.createdAtMillis()))
            .map(entry -> Objects.requireNonNull(entry, "audio entry").output())
            .filter(StereoOpenALHandler.class::isInstance)
            .map(output -> (StereoOpenALHandler)output)
            .map(handler -> handler.snapshot());
   }

   public static Optional<StereoOpenALHandler.DiagnosticSnapshot> getStereoSnapshot(BlockPos turntablePos) {
      if (turntablePos == null) {
         return Optional.empty();
      } else {
         ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(keyFor(turntablePos));
         return entry != null && entry.kind() == ClientAudioOutputRegistry.OutputKind.STEREO && entry.output() instanceof StereoOpenALHandler stereo
            ? Optional.of(stereo.snapshot())
            : Optional.empty();
      }
   }

   private static long getRelayMediaMillis(BlockPos turntableKey) {
      long best = -1L;

      for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
         if (turntableKey.equals(keyFor(entry.getValue()))) {
            SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
            if (relay != null) {
               long positionMillis = relay.getPositionMillis();
               if (positionMillis >= 0L) {
                  long startOffsetTicks = startOffsetTicksFor(turntableKey);
                  best = Math.max(best, adjustedAudibleMillis(startOffsetTicks, positionMillis, relay.getOutputDelayMillis()));
               }
            }
         }
      }

      return best;
   }

   private static ClientAudioOutputRegistry.RelayTimeline getRelayTimeline(BlockPos turntableKey) {
      long best = -1L;
      int registered = 0;
      int started = 0;

      for (Entry<BlockPos, BlockPos> entry : RELAY_TURNTABLE.entrySet()) {
         if (turntableKey.equals(keyFor(entry.getValue()))) {
            registered++;
            SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
            if (relay != null) {
               if (relay.isStarted()) {
                  started++;
               }

               long positionMillis = relay.getPositionMillis();
               if (positionMillis >= 0L) {
                  long startOffsetTicks = startOffsetTicksFor(turntableKey);
                  best = Math.max(best, adjustedAudibleMillis(startOffsetTicks, positionMillis, relay.getOutputDelayMillis()));
               }
            }
         }
      }

      return new ClientAudioOutputRegistry.RelayTimeline(best, started, registered);
   }

   private static long adjustedAudibleMillis(long startOffsetTicks, long relativeMillis, long outputDelayMillis) {
      long mediaMillis = Math.max(0L, startOffsetMillis(startOffsetTicks) + Math.max(0L, relativeMillis));
      return Math.max(0L, mediaMillis - Math.max(0L, outputDelayMillis));
   }

   private static long startOffsetMillis(long startOffsetTicks) {
      return Math.max(0L, startOffsetTicks) * 50L;
   }

   private static long startOffsetTicksFor(BlockPos key) {
      ClientAudioOutputRegistry.AudioEntry entry = OUTPUTS.get(key);
      return entry != null ? entry.startOffsetTicks() : 0L;
   }

   public static List<String> describeActiveSources() {
      float[] listener = listenerPos;
      if (!isActive()) {
         return List.of("No active Dolby/OpenAL audio");
      } else {
         List<String> lines = new ArrayList<>();
         long dolbyCount = OUTPUTS.values().stream().filter(entry -> entry.kind() == ClientAudioOutputRegistry.OutputKind.DOLBY).count();
         long stereoCount = OUTPUTS.size() - dolbyCount;
         lines.add(String.format("Active OpenAL turntables: dolby=%d stereo=%d", dolbyCount, stereoCount));
         OUTPUTS.values().stream().sorted(Comparator.comparingLong(entry -> entry.createdAtMillis())).forEach(entry -> {
            lines.add(String.format("%s @ %s", entry.kind().displayName(), AudioUtils.fmtPos(entry.machinePos())));
            if (entry.output() instanceof DolbyAudioHandler dolby) {
               for (String line : dolby.describeSources(entry.machinePos(), listener)) {
                  lines.add("  " + line);
               }
            } else if (entry.output() instanceof StereoOpenALHandler stereo) {
               for (String line : stereo.describeState()) {
                  lines.add("  " + line);
               }
            }
         });
         return lines;
      }
   }

   public static void cleanup() {
      List<Runnable> cleanupTasks = new ArrayList<>();

      for (ClientAudioOutputRegistry.AudioEntry entry : OUTPUTS.values()) {
         cleanupTasks.add(entry::cleanup);
      }

      for (SpeakerAudioRelay relay : RELAYS.values()) {
         cleanupTasks.add(relay::cleanup);
      }

      OUTPUTS.clear();
      RELAYS.clear();
      RELAY_TURNTABLE.clear();
      CONSOLE_ROUTE_OWNERS.clear();
      listenerPos = null;
      ClientMinecartAudioAnchors.clear();
      ClientAudioOwnerVolumes.clear();
      MINECART_KEYS.clear();
      ClientAreaAudioZoneRegistry.clear();
      runCleanupTasks(cleanupTasks);
   }

   private static void runCleanupTasks(List<Runnable> cleanupTasks) {
      if (!cleanupTasks.isEmpty()) {
         List<Thread> threads = new ArrayList<>(cleanupTasks.size());

         for (int i = 0; i < cleanupTasks.size(); i++) {
            Runnable task = cleanupTasks.get(i);
            Thread thread = NetMusicThreadFactory.daemonThread("DolbyRegistryCleanup-" + i, () -> {
               try {
                  task.run();
               } catch (Throwable var2x) {
                  LOGGER.debug("OpenAL registry cleanup task failed: {}", var2x.toString());
               }
            });
            thread.start();
            threads.add(thread);
         }

         long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000L);
         int unfinished = 0;

         for (Thread thread : threads) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0L) {
               unfinished++;
            } else {
               try {
                  thread.join(remainingMillis);
               } catch (InterruptedException var10) {
                  Thread.currentThread().interrupt();
                  unfinished++;
                  break;
               }

               if (thread.isAlive()) {
                  unfinished++;
               }
            }
         }

         if (unfinished > 0) {
            LOGGER.debug("OpenAL registry cleanup timed out with {} task(s) still running", unfinished);
         }
      }
   }

   private static void hardStopAndCleanup(ClientAudioOutputRegistry.AudioEntry entry) {
      if (entry != null) {
         try {
            entry.output().hardStopOutput();
         } catch (Throwable var2) {
            LOGGER.debug("OpenAL hard-stop failed before cleanup: {}", var2.toString());
         }

         entry.output().cleanup();
         releaseMinecartKeyIfUnused(entry.pos());
      }
   }

   private static void cleanupAfterPut(BoundedConcurrentStore.PutResult<ClientAudioOutputRegistry.AudioEntry> result) {
      if (result.replaced() != null) {
         hardStopAndCleanup(result.replaced());
      }

      for (ClientAudioOutputRegistry.AudioEntry evicted : result.evicted()) {
         hardStopAndCleanup(evicted);
      }
   }

   private static BlockPos keyFor(BlockPos pos) {
      if (pos != null) {
         return AudioUtils.copyPos(pos);
      } else {
         int id = ANONYMOUS_COUNTER.getAndIncrement();
         return new BlockPos(Integer.MIN_VALUE, 0, id);
      }
   }

   private static BlockPos keyFor(BlockPos pos, UUID ownerId) {
      if (pos != null) {
         return AudioUtils.copyPos(pos);
      } else {
         return ownerId != null ? new BlockPos(-2147483647, ownerId.hashCode(), (int)ownerId.getLeastSignificantBits()) : keyFor((BlockPos)null);
      }
   }

   private static BlockPos keyFor(BlockPos pos, UUID ownerId, Optional<PlaybackSessionId> playbackSessionId) {
      UUID minecartUuid = playbackSessionId.<UUID>map(sessionId -> ClientMinecartAudioAnchors.entityUuid(sessionId.value())).orElse(null);
      return minecartUuid != null
         ? MINECART_KEYS.computeIfAbsent(minecartUuid, ignored -> new BlockPos(-2147483646, 0, ANONYMOUS_COUNTER.getAndIncrement()))
         : keyFor(pos, ownerId);
   }

   private static void releaseMinecartKeyIfUnused(BlockPos key) {
      if (key != null && key.getX() == -2147483646 && OUTPUTS.get(key) == null) {
         MINECART_KEYS.entrySet().removeIf(entry -> key.equals(entry.getValue()));
      }
   }

   private static boolean isRealWorldKey(BlockPos pos) {
      return pos != null && pos.getX() > -2147483646;
   }

   private static float[] centerFor(BlockPos pos) {
      return AudioUtils.centerFor(pos);
   }

   public record AudioDemandDebug(
      boolean demand,
      boolean audioEnabled,
      boolean listenerPresent,
      float sourceVolume,
      boolean headphone,
      int indexedDemands,
      boolean mainSuppressed,
      boolean consoleRoute,
      float mainDistance,
      boolean mainAudible,
      int matchingRelays,
      boolean relayAudible
   ) {
   }

   private record AudioEntry(
      BlockPos pos,
      float[] machinePos,
      AudioOutputHandle output,
      ClientAudioOutputRegistry.OutputKind kind,
      long createdAtMillis,
      long startOffsetTicks,
      Optional<PlaybackSessionId> playbackSessionId,
      UUID ownerId
   ) {
      private AudioEntry(
         BlockPos pos,
         float[] machinePos,
         AudioOutputHandle output,
         ClientAudioOutputRegistry.OutputKind kind,
         long createdAtMillis,
         long startOffsetTicks,
         Optional<PlaybackSessionId> playbackSessionId,
         UUID ownerId
      ) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.pos = pos;
         this.machinePos = machinePos;
         this.output = output;
         this.kind = kind;
         this.createdAtMillis = createdAtMillis;
         this.startOffsetTicks = startOffsetTicks;
         this.playbackSessionId = playbackSessionId;
         this.ownerId = ownerId;
      }

      private String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }

      private void cleanup() {
         this.output.cleanup();
      }
   }

   public record AudioTimeline(
      long mainMillis,
      long mainFedMillis,
      long relayMillis,
      long combinedMillis,
      int relayStartedCount,
      int relayRegisteredCount,
      Optional<PlaybackSessionId> playbackSessionId
   ) {
      private static final ClientAudioOutputRegistry.AudioTimeline EMPTY = new ClientAudioOutputRegistry.AudioTimeline(
         -1L, -1L, -1L, -1L, 0, 0, Optional.empty()
      );

      public AudioTimeline(
         long mainMillis,
         long mainFedMillis,
         long relayMillis,
         long combinedMillis,
         int relayStartedCount,
         int relayRegisteredCount,
         Optional<PlaybackSessionId> playbackSessionId
      ) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.mainMillis = mainMillis;
         this.mainFedMillis = mainFedMillis;
         this.relayMillis = relayMillis;
         this.combinedMillis = combinedMillis;
         this.relayStartedCount = relayStartedCount;
         this.relayRegisteredCount = relayRegisteredCount;
         this.playbackSessionId = playbackSessionId;
      }

      public AudioTimeline(
         long mainMillis, long mainFedMillis, long relayMillis, long combinedMillis, int relayStartedCount, int relayRegisteredCount, String sessionId
      ) {
         this(mainMillis, mainFedMillis, relayMillis, combinedMillis, relayStartedCount, relayRegisteredCount, PlaybackSessionId.parse(sessionId));
      }

      public long audibleMillis() {
         return this.mainMillis >= 0L ? this.mainMillis : this.relayMillis;
      }

      public String audioSessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }

      public String sessionId() {
         return this.audioSessionId();
      }

      public long fedMillis() {
         return this.mainFedMillis;
      }
   }

   private static enum OutputKind {
      DOLBY("Dolby"),
      STEREO("Stereo");

      private final String displayName;

      private OutputKind(String displayName) {
         this.displayName = displayName;
      }

      private String displayName() {
         return this.displayName;
      }
   }

   private record RelayTimeline(long mediaMillis, int startedCount, int registeredCount) {
   }
}
