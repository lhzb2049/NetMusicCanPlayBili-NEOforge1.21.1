package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.PadFocusScreen;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import com.zhongbai233.net_music_can_play_bili.network.PadStatePacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class PadClient {
   private static final int FAST_SYNC_INTERVAL_TICKS = 10;
   private static final int AUTO_PLAY_SCAN_INTERVAL_TICKS = 5;
   private static final int PAD_INDEX_REBUILD_INTERVAL_TICKS = 20;
   private static final Map<PlaybackSourceId, PadDocument> DOCUMENTS = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, PadClient.PendingDocumentSync> PENDING_SYNCS = new ConcurrentHashMap<>();
   private static final Map<PlaybackSourceId, List<PadClient.IndexedPadStack>> PAD_INDEX = new HashMap<>();
   private static long localSequence;
   private static int fastSyncTicks;
   private static int autoPlayScanTicks;
   private static int padIndexRebuildTicks;
   private static boolean syncRequested;

   private PadClient() {
   }

   public static void tickHeldDeviceSync() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && minecraft.getConnection() != null) {
         if (fastSyncTicks > 0) {
            fastSyncTicks--;
         }

         if (padIndexRebuildTicks > 0) {
            padIndexRebuildTicks--;
         } else {
            rebuildPadIndex(minecraft);
         }

         if (syncRequested && fastSyncTicks <= 0) {
            sendPendingSyncs(minecraft);
         }

         if (autoPlayScanTicks > 0) {
            autoPlayScanTicks--;
         } else {
            autoPlayScanTicks = 5;
            tickAutoPlayback(minecraft);
         }
      } else {
         clearCachedDocuments();
      }
   }

   public static void openFocusScreen(InteractionHand hand) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && hand != null && hand != InteractionHand.OFF_HAND) {
         ItemStack stack = minecraft.player.getItemInHand(hand);
         if (stack.getItem() instanceof PadItem) {
            minecraft.setScreen(new PadFocusScreen(hand));
         }
      }
   }

   public static PadDocument cachedDocumentFor(ItemStack stack) {
      UUID deviceId = PadItem.readDeviceId(stack);
      PadDocument cached = deviceId != null ? DOCUMENTS.get(PlaybackSourceId.of(deviceId)) : null;
      PadDocument itemDocument = PadItem.readDocument(stack);
      if (cached == null) {
         return itemDocument;
      } else {
         PadDocument newer = compareVersion(itemDocument, cached) > 0 ? itemDocument : cached;
         return newer.copyWithLocked(itemDocument.locked());
      }
   }

   public static boolean hasLockedIndexedPad(UUID deviceId) {
      return firstLocked(indexedStacks(deviceId)) != null;
   }

   public static void markDocumentDirty(ItemStack stack, PadDocument document) {
      UUID deviceId = PadItem.readDeviceId(stack);
      if (deviceId != null && document != null) {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         DOCUMENTS.put(sourceId, document);
         PENDING_SYNCS.put(sourceId, new PadClient.PendingDocumentSync(document, System.currentTimeMillis(), ++localSequence));
         syncRequested = true;
      }
   }

   public static void receiveMirroredDocument(UUID deviceId, PadDocument document, long serverGameTime) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && deviceId != null && document != null) {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         PadDocument current = DOCUMENTS.get(sourceId);
         if (current == null || compareVersion(document, current) >= 0) {
            DOCUMENTS.put(sourceId, document);
            PENDING_SYNCS.remove(sourceId);

            for (PadClient.IndexedPadStack indexed : indexedStacks(deviceId)) {
               ItemStack stack = indexed.stack();
               boolean locked = PadItem.readLocked(stack);
               PadItem.writeDeviceId(stack, deviceId);
               PadItem.writeDocument(stack, document.copyWithLocked(locked));
            }

            rebuildPadIndex(minecraft);
         }
      }
   }

   public static void clearCachedDocuments() {
      DOCUMENTS.clear();
      PENDING_SYNCS.clear();
      PAD_INDEX.clear();
      fastSyncTicks = 0;
      autoPlayScanTicks = 0;
      padIndexRebuildTicks = 0;
      syncRequested = false;
   }

   private static void tickAutoPlayback(Minecraft minecraft) {
   }

   private static void sendPendingSyncs(Minecraft minecraft) {
      if (minecraft != null && minecraft.player != null && minecraft.getConnection() != null) {
         if (PENDING_SYNCS.isEmpty()) {
            syncRequested = false;
         } else {
            for (Entry<PlaybackSourceId, PadClient.PendingDocumentSync> entry : new ArrayList<>(PENDING_SYNCS.entrySet())) {
               PlaybackSourceId sourceId = entry.getKey();
               UUID deviceId = sourceId.value();
               PadClient.PendingDocumentSync pending = PENDING_SYNCS.remove(sourceId);
               if (pending != null && PAD_INDEX.containsKey(sourceId)) {
                  PacketDistributor.sendToServer(
                     new PadStatePacket(deviceId, pending.document(), pending.updatedAtMillis(), pending.sequence()), new CustomPacketPayload[0]
                  );
               }
            }

            syncRequested = !PENDING_SYNCS.isEmpty();
            fastSyncTicks = 10;
         }
      }
   }

   private static int compareVersion(PadDocument left, PadDocument right) {
      int timeCompare = Long.compare(left.updatedAtMillis(), right.updatedAtMillis());
      return timeCompare != 0 ? timeCompare : Long.compare(left.sequence(), right.sequence());
   }

   private static List<PadClient.IndexedPadStack> indexedStacks(UUID deviceId) {
      return deviceId == null ? List.of() : PAD_INDEX.getOrDefault(PlaybackSourceId.of(deviceId), List.of());
   }

   private static PadClient.IndexedPadStack firstLocked(List<PadClient.IndexedPadStack> stacks) {
      if (stacks == null) {
         return null;
      } else {
         for (PadClient.IndexedPadStack stack : stacks) {
            if (stack.locked()) {
               return stack;
            }
         }

         return null;
      }
   }

   private static void rebuildPadIndex(Minecraft minecraft) {
      PAD_INDEX.clear();
      padIndexRebuildTicks = 20;
      if (minecraft != null && minecraft.player != null) {
         Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
         addIndexedPad(minecraft.player.containerMenu != null ? minecraft.player.containerMenu.getCarried() : ItemStack.EMPTY, seen);
         addIndexedPad(minecraft.player.getMainHandItem(), seen);
         addIndexedPad(minecraft.player.getOffhandItem(), seen);

         for (int i = 0; i < minecraft.player.getInventory().getContainerSize(); i++) {
            addIndexedPad(minecraft.player.getInventory().getItem(i), seen);
         }
      }
   }

   private static void addIndexedPad(ItemStack stack, Set<ItemStack> seen) {
      if (PadItem.isPad(stack) && seen.add(stack)) {
         UUID deviceId = PadItem.readDeviceId(stack);
         if (deviceId != null) {
            PAD_INDEX.computeIfAbsent(PlaybackSourceId.of(deviceId), ignored -> new ArrayList<>())
               .add(new PadClient.IndexedPadStack(stack, PadItem.readLocked(stack)));
         }
      }
   }

   private record IndexedPadStack(ItemStack stack, boolean locked) {
   }

   private record PendingDocumentSync(PadDocument document, long updatedAtMillis, long sequence) {
   }
}
