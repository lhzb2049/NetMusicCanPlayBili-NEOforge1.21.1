package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
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

public final class MP4DeviceHolderTracker {
   private static final int HELD_SYNC_INTERVAL_TICKS = 5;
   private static final int FORCED_SYNC_INTERVAL_TICKS = 300;
   private static final Map<MP4DeviceHolderTracker.HolderKey, Integer> LAST_SENT = new ConcurrentHashMap<>();
   private static final Map<MP4DeviceHolderTracker.HolderKey, Long> LAST_FORCED_SYNC = new ConcurrentHashMap<>();

   private MP4DeviceHolderTracker() {
   }

   public static void tick(MinecraftServer server) {
      if (server != null && server.getTickCount() % 5 == 0) {
         Set<MP4DeviceHolderTracker.HolderKey> active = new HashSet<>();

         for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
               scan(level, player, player.getItemInHand(InteractionHand.MAIN_HAND), active);
               scan(level, player, player.getItemInHand(InteractionHand.OFF_HAND), active);
               scan(level, player, player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY, active);
            }
         }

         LAST_SENT.keySet().removeIf(key -> !active.contains(key));
         LAST_FORCED_SYNC.keySet().removeIf(key -> !active.contains(key));
      }
   }

   public static void invalidate(UUID deviceId) {
      if (deviceId != null) {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         LAST_SENT.keySet().removeIf(key -> sourceId.equals(key.sourceId()));
      }
   }

   public static void clear() {
      LAST_SENT.clear();
   }

   private static void scan(ServerLevel level, ServerPlayer player, ItemStack stack, Set<MP4DeviceHolderTracker.HolderKey> active) {
      if (stack.getItem() instanceof MP4Item) {
         UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, player, stack);
         if (deviceId != null) {
            MP4DeviceStateStore.syncQueueCopy(level, deviceId, stack);
            MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
            boolean headphoneLinked = AudioLinkIndex.hasHeadphoneLinkedToMp4(deviceId);
            MP4DeviceHolderTracker.HolderKey key = new MP4DeviceHolderTracker.HolderKey(player.getUUID(), PlaybackSourceId.of(deviceId));
            active.add(key);
            int fingerprint = fingerprint(entry, headphoneLinked);
            long gameTime = MonotonicMediaClock.nowTick();
            Long lastForcedSync = LAST_FORCED_SYNC.get(key);
            boolean force = lastForcedSync == null || gameTime - lastForcedSync >= 300L;
            if (force || !Objects.equals(LAST_SENT.get(key), fingerprint)) {
               LAST_SENT.put(key, fingerprint);
               LAST_FORCED_SYNC.put(key, gameTime);
               PacketDistributor.sendToPlayer(player, MP4DeviceStateMirrorPacket.fromEntry(deviceId, entry, headphoneLinked), new CustomPacketPayload[0]);
            }
         }
      }
   }

   private static int fingerprint(MP4DeviceStateStore.DeviceEntry entry, boolean headphoneLinked) {
      int hash = entry.state().hashCode();
      hash = 31 * hash + Boolean.hashCode(headphoneLinked);
      hash = 31 * hash + Long.hashCode(entry.updatedGameTime());
      hash = 31 * hash + Long.hashCode(entry.elapsedMillis());
      hash = 31 * hash + entry.durationSeconds();
      hash = 31 * hash + entry.sessionId().hashCode();

      for (ItemStack stack : entry.queue()) {
         hash = 31 * hash + stack.getItem().hashCode();
         hash = 31 * hash + stack.getComponents().hashCode();
      }

      return hash;
   }

   private record HolderKey(UUID playerId, PlaybackSourceId sourceId) {
   }
}
