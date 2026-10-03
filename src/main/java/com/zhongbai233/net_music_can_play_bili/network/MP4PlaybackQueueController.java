package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class MP4PlaybackQueueController {
   private final MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions;
   private final Consumer<UUID> resolveInvalidator;
   private final MP4PlaybackQueueController.RuntimeProgressRecorder progressRecorder;
   private final BiConsumer<ServerLevel, UUID> progressFlusher;
   private final MP4PlaybackQueueController.SessionPublisher sessionPublisher;
   private final MP4PlaybackQueueController.SessionStopPublisher stopPublisher;
   private final MP4PlaybackQueueController.PlaybackStarter playbackStarter;

   MP4PlaybackQueueController(
      MP4PlaybackSourceSessionRegistry<MP4PlaybackSyncManager.Session> sessions,
      Consumer<UUID> resolveInvalidator,
      MP4PlaybackQueueController.RuntimeProgressRecorder progressRecorder,
      BiConsumer<ServerLevel, UUID> progressFlusher,
      MP4PlaybackQueueController.SessionPublisher sessionPublisher,
      MP4PlaybackQueueController.SessionStopPublisher stopPublisher,
      MP4PlaybackQueueController.PlaybackStarter playbackStarter
   ) {
      this.sessions = Objects.requireNonNull(sessions, "sessions");
      this.resolveInvalidator = Objects.requireNonNull(resolveInvalidator, "resolveInvalidator");
      this.progressRecorder = Objects.requireNonNull(progressRecorder, "progressRecorder");
      this.progressFlusher = Objects.requireNonNull(progressFlusher, "progressFlusher");
      this.sessionPublisher = Objects.requireNonNull(sessionPublisher, "sessionPublisher");
      this.stopPublisher = Objects.requireNonNull(stopPublisher, "stopPublisher");
      this.playbackStarter = Objects.requireNonNull(playbackStarter, "playbackStarter");
   }

   void reconcileQueueChange(ServerPlayer owner, UUID deviceId, List<ItemStack> newQueue) {
      if (owner != null && deviceId != null && owner.level() instanceof ServerLevel level) {
         MP4PlaybackSyncManager.Session session = this.sessions.get(deviceId);
         if (session != null) {
            List<ItemStack> safeNewQueue = newQueue != null ? newQueue : List.of();
            MP4PlaybackQueuePolicy.Reconciliation reconciliation = MP4PlaybackQueuePolicy.reconcile(
               session.queueIndex(), session.rawUrl(), sourceUrls(safeNewQueue)
            );
            long gameTime = MonotonicMediaClock.nowTick();
            long elapsedMillis = session.elapsedMillis(gameTime);
            if (reconciliation.action() != MP4PlaybackQueuePolicy.ReconcileAction.KEEP) {
               if (reconciliation.action() == MP4PlaybackQueuePolicy.ReconcileAction.REMAP) {
                  this.remapSession(level, deviceId, session, reconciliation.selectedIndex(), elapsedMillis, gameTime);
               } else {
                  this.stopRemovedSession(level, deviceId, session, safeNewQueue, reconciliation.selectedIndex());
               }
            }
         }
      }
   }

   boolean tryAdvanceQueue(ServerLevel level, ItemStack stack, MP4PlaybackSyncManager.Session session) {
      MP4DeviceStateStore.DeviceEntry deviceEntry = MP4DeviceStateStore.getOrCreate(level, session.sourceId(), stack);
      List<ItemStack> queue = queueForDevice(deviceEntry, stack);
      MP4Item.State state = deviceEntry.state();
      MP4PlaybackQueuePolicy.Completion completion = MP4PlaybackQueuePolicy.completion(session.queueIndex(), queue.size(), state.repeatMode());
      if (!completion.shouldAdvance()) {
         return false;
      } else {
         int nextIndex = completion.nextIndex();
         MP4DeviceStateStore.update(
            level,
            session.sourceId(),
            new MP4DeviceStateStore.DeviceEntry(
               new MP4Item.State(
                  true,
                  state.shuffle(),
                  state.videoEnabled(),
                  state.landscape(),
                  state.qualityIndex(),
                  nextIndex,
                  state.queueScrollOffset(),
                  state.volumePerMille(),
                  state.repeatMode(),
                  state.playlistOpen(),
                  state.lyricsEnabled(),
                  state.subtitleMode(),
                  state.subtitleAiEnabled(),
                  0,
                  state.rotationHintShown()
               ),
               queue,
               0L,
               0,
               Optional.empty()
            )
         );
         this.progressRecorder.record(session.sourceId(), nextIndex, 0L, 0, session.volumePerMille(), Optional.empty(), true);
         this.progressFlusher.accept(level, session.sourceId());
         session.markContainerChanged();
         this.playbackStarter
            .start(
               level,
               stack,
               session.ownerId(),
               session.sourceId(),
               session.sourceType(),
               session.sourceEntityId(),
               session.sourcePos(),
               session.containerSlot()
            );
         return true;
      }
   }

   static int durationSeconds(List<ItemStack> queue, int queueIndex) {
      if (queue != null && queueIndex >= 0 && queueIndex < queue.size()) {
         SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
         return songInfo != null ? Math.max(0, songInfo.songTime) : 0;
      } else {
         return 0;
      }
   }

   static List<ItemStack> queueForDevice(MP4DeviceStateStore.DeviceEntry entry, ItemStack stack) {
      List<ItemStack> itemQueue = MP4Item.readQueue(stack);
      if (!itemQueue.isEmpty()) {
         return itemQueue;
      } else {
         return entry != null ? entry.queue() : List.of();
      }
   }

   private void remapSession(ServerLevel level, UUID deviceId, MP4PlaybackSyncManager.Session session, int newIndex, long elapsedMillis, long gameTime) {
      MP4PlaybackSyncManager.Session remapped = session.withQueueIndex(newIndex, gameTime);
      if (this.sessions.replace(deviceId, session, remapped)) {
         this.progressRecorder
            .record(deviceId, newIndex, elapsedMillis, remapped.durationSeconds(), remapped.volumePerMille(), Optional.of(remapped.playbackSessionId()), true);
         MP4DeviceStateStore.recordPlayback(
            level, deviceId, newIndex, elapsedMillis, remapped.durationSeconds(), remapped.volumePerMille(), Optional.of(remapped.playbackSessionId()), true
         );
         this.sessionPublisher.publish(level, remapped, gameTime);
      }
   }

   private void stopRemovedSession(ServerLevel level, UUID deviceId, MP4PlaybackSyncManager.Session session, List<ItemStack> newQueue, int selectedIndex) {
      if (this.sessions.remove(deviceId, session)) {
         this.resolveInvalidator.accept(deviceId);
         this.stopPublisher.publish(level, session);
         int durationSeconds = durationSeconds(newQueue, selectedIndex);
         this.progressRecorder.record(deviceId, selectedIndex, 0L, durationSeconds, session.volumePerMille(), Optional.empty(), false);
         this.progressFlusher.accept(level, deviceId);
         MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, ItemStack.EMPTY);
         MP4Item.State state = entry.state();
         MP4DeviceStateStore.update(
            level,
            deviceId,
            new MP4DeviceStateStore.DeviceEntry(
               new MP4Item.State(
                  false,
                  state.shuffle(),
                  state.videoEnabled(),
                  state.landscape(),
                  state.qualityIndex(),
                  selectedIndex,
                  state.queueScrollOffset(),
                  session.volumePerMille(),
                  state.repeatMode(),
                  state.playlistOpen(),
                  state.lyricsEnabled(),
                  state.subtitleMode(),
                  state.subtitleAiEnabled(),
                  0,
                  state.rotationHintShown()
               ),
               newQueue,
               0L,
               durationSeconds,
               Optional.empty()
            )
         );
      }
   }

   private static List<String> sourceUrls(List<ItemStack> queue) {
      List<String> urls = new ArrayList<>(queue.size());

      for (ItemStack stack : queue) {
         SongInfo songInfo = ItemMusicCD.getSongInfo(stack);
         urls.add(songInfo != null ? songInfo.songUrl : null);
      }

      return urls;
   }

   @FunctionalInterface
   interface PlaybackStarter {
      void start(ServerLevel var1, ItemStack var2, UUID var3, UUID var4, int var5, int var6, BlockPos var7, int var8);
   }

   @FunctionalInterface
   interface RuntimeProgressRecorder {
      void record(UUID var1, int var2, long var3, int var5, int var6, Optional<PlaybackSessionId> var7, boolean var8);
   }

   @FunctionalInterface
   interface SessionPublisher {
      void publish(ServerLevel var1, MP4PlaybackSyncManager.Session var2, long var3);
   }

   @FunctionalInterface
   interface SessionStopPublisher {
      void publish(ServerLevel var1, MP4PlaybackSyncManager.Session var2);
   }
}
