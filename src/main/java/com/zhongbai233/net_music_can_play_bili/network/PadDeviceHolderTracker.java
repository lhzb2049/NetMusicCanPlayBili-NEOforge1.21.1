package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PadDeviceHolderTracker {
   private static final int HELD_SYNC_INTERVAL_TICKS = 5;
   private static final int FORCED_SYNC_INTERVAL_TICKS = 300;
   private static final Map<PadDeviceHolderTracker.HolderKey, Integer> LAST_SENT = new ConcurrentHashMap<>();
   private static final Map<PadDeviceHolderTracker.HolderKey, Long> LAST_FORCED_SYNC = new ConcurrentHashMap<>();
   private static final Map<PadDeviceHolderTracker.HolderKey, UUID> ACTIVE_AUTO_POINTS = new ConcurrentHashMap<>();
   private static final Map<PadDeviceHolderTracker.HolderKey, Long> AUTO_COOLDOWNS = new ConcurrentHashMap<>();

   private PadDeviceHolderTracker() {
   }

   public static void tick(MinecraftServer server) {
      if (server != null && server.getTickCount() % 5 == 0) {
         Set<PadDeviceHolderTracker.HolderKey> active = new HashSet<>();

         for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
               scan(level, player, player.getItemInHand(InteractionHand.MAIN_HAND), active);
               scan(level, player, player.getItemInHand(InteractionHand.OFF_HAND), active);
               scan(level, player, player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY, active);

               for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                  scan(level, player, player.getInventory().getItem(i), active);
               }
            }
         }

         LAST_SENT.keySet().removeIf(key -> !active.contains(key));
         LAST_FORCED_SYNC.keySet().removeIf(key -> !active.contains(key));
         ACTIVE_AUTO_POINTS.keySet().removeIf(key -> !active.contains(key));
         AUTO_COOLDOWNS.keySet().removeIf(key -> !active.contains(key));
      }
   }

   public static void invalidate(UUID deviceId) {
      if (deviceId != null) {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         LAST_SENT.keySet().removeIf(key -> sourceId.equals(key.sourceId()));
         LAST_FORCED_SYNC.keySet().removeIf(key -> sourceId.equals(key.sourceId()));
      }
   }

   public static void clear() {
      LAST_SENT.clear();
      LAST_FORCED_SYNC.clear();
      ACTIVE_AUTO_POINTS.clear();
      AUTO_COOLDOWNS.clear();
   }

   private static void scan(ServerLevel level, ServerPlayer player, ItemStack stack, Set<PadDeviceHolderTracker.HolderKey> active) {
      if (PadItem.isPad(stack)) {
         UUID deviceId = PadItem.getOrCreateDeviceId(stack);
         PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, stack).copyWithLocked(PadItem.readLocked(stack));
         PadDeviceHolderTracker.HolderKey key = new PadDeviceHolderTracker.HolderKey(player.getUUID(), PlaybackSourceId.of(deviceId));
         if (active.add(key)) {
            tickAutoPlayback(level, player, stack, key, deviceId, document);
            int fingerprint = document.hashCode();
            long gameTime = MonotonicMediaClock.nowTick();
            Long lastForcedSync = LAST_FORCED_SYNC.get(key);
            boolean force = lastForcedSync == null || gameTime - lastForcedSync >= 300L;
            if (force || !Objects.equals(LAST_SENT.get(key), fingerprint)) {
               LAST_SENT.put(key, fingerprint);
               LAST_FORCED_SYNC.put(key, gameTime);
               PacketDistributor.sendToPlayer(player, new PadStateMirrorPacket(deviceId, document, gameTime), new CustomPacketPayload[0]);
            }
         }
      }
   }

   private static void tickAutoPlayback(
      ServerLevel level, ServerPlayer player, ItemStack stack, PadDeviceHolderTracker.HolderKey key, UUID deviceId, PadDocument document
   ) {
      if (!document.locked()) {
         ACTIVE_AUTO_POINTS.remove(key);
         AUTO_COOLDOWNS.remove(key);
      } else {
         long gameTime = MonotonicMediaClock.nowTick();
         Long cooldownUntil = AUTO_COOLDOWNS.get(key);
         boolean coolingDown = cooldownUntil != null && gameTime < cooldownUntil;
         PadTriggerPoint target = nearestAutoPoint(document, player.getX(), player.getY(), player.getZ());
         UUID active = ACTIVE_AUTO_POINTS.get(key);
         if (target != null) {
            if (!target.pointId().equals(active) && !coolingDown) {
               PadPlaybackControlPacket.startFromServer(player, stack, deviceId, target.pointId(), 0L);
               ACTIVE_AUTO_POINTS.put(key, target.pointId());
               AUTO_COOLDOWNS.put(key, gameTime + 20L);
            }
         } else {
            ACTIVE_AUTO_POINTS.remove(key);
         }
      }
   }

   private static PadTriggerPoint nearestAutoPoint(PadDocument document, double x, double y, double z) {
      PadTriggerPoint nearest = null;
      double nearestDistance = Double.MAX_VALUE;

      for (PadTriggerPoint point : document.triggerPoints()) {
         if (point.triggerMode() == PadTriggerMode.ENTER_RADIUS) {
            double dx = point.x() - x;
            double dy = point.y() - y;
            double dz = point.z() - z;
            double distanceSqr = dx * dx + dy * dy + dz * dz;
            double radius = Math.max(1, point.radiusBlocks());
            if (distanceSqr <= radius * radius && distanceSqr < nearestDistance) {
               nearest = point;
               nearestDistance = distanceSqr;
            }
         }
      }

      return nearest;
   }

   private record HolderKey(UUID playerId, PlaybackSourceId sourceId) {
   }
}
