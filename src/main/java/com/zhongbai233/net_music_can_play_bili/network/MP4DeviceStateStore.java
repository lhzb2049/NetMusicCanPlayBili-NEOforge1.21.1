package com.zhongbai233.net_music_can_play_bili.network;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class MP4DeviceStateStore {
   private static final Map<PlaybackSourceId, MP4DeviceStateStore.DeviceEntry> RUNTIME = new ConcurrentHashMap<>();

   private MP4DeviceStateStore() {
   }

   public static MP4DeviceStateStore.DeviceEntry getOrCreate(ServerLevel level, UUID deviceId, ItemStack stack) {
      if (deviceId == null) {
         return MP4DeviceStateStore.DeviceEntry.EMPTY;
      } else {
         PlaybackSourceId sourceId = PlaybackSourceId.of(deviceId);
         MP4DeviceStateStore.DeviceEntry runtime = RUNTIME.get(sourceId);
         if (runtime != null) {
            return runtime;
         } else {
            MP4DeviceStateStore.DeviceEntry saved = level != null ? MP4PlaybackSavedData.get(level).device(deviceId).orElse(null) : null;
            if (saved != null) {
               MP4DeviceStateStore.DeviceEntry restored = withStackQueue(saved, stack).normalized();
               RUNTIME.put(sourceId, restored);
               return restored;
            } else {
               MP4DeviceStateStore.DeviceEntry created = fromStackContents(stack).normalized();
               RUNTIME.put(sourceId, created);
               return created;
            }
         }
      }
   }

   public static MP4DeviceStateStore.DeviceEntry get(UUID deviceId) {
      return deviceId == null
         ? MP4DeviceStateStore.DeviceEntry.EMPTY
         : RUNTIME.getOrDefault(PlaybackSourceId.of(deviceId), MP4DeviceStateStore.DeviceEntry.EMPTY);
   }

   public static void update(ServerLevel level, UUID deviceId, MP4DeviceStateStore.DeviceEntry entry) {
      if (deviceId != null && entry != null) {
         MP4DeviceStateStore.DeviceEntry normalized = entry.withUpdatedGameTime(nextUpdatedGameTime(level, deviceId, entry)).normalized();
         RUNTIME.put(PlaybackSourceId.of(deviceId), normalized);
         if (level != null) {
            MP4PlaybackSavedData.get(level).putDevice(deviceId, normalized);
         }

         MP4DeviceHolderTracker.invalidate(deviceId);
      }
   }

   private static long nextUpdatedGameTime(ServerLevel level, UUID deviceId, MP4DeviceStateStore.DeviceEntry entry) {
      long base = Math.max(0L, entry.updatedGameTime());
      MP4DeviceStateStore.DeviceEntry current = RUNTIME.get(PlaybackSourceId.of(deviceId));
      if (current != null) {
         base = Math.max(base, current.updatedGameTime() + 1L);
      }

      return base;
   }

   public static void updateState(ServerLevel level, UUID deviceId, MP4Item.State state) {
      MP4DeviceStateStore.DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
      update(level, deviceId, current.withState(state));
   }

   public static void updateQueue(ServerLevel level, UUID deviceId, List<ItemStack> queue) {
      MP4DeviceStateStore.DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
      update(level, deviceId, current.withQueue(queue));
   }

   public static void syncQueueCopy(ServerLevel level, UUID deviceId, ItemStack stack) {
      if (!stack.isEmpty() && stack.getItem() instanceof MP4Item) {
         List<ItemStack> queue = MP4Item.readQueue(stack);
         MP4DeviceStateStore.DeviceEntry current = getOrCreate(level, deviceId, stack);
         if (!sameQueue(current.queue(), queue)) {
            updateQueue(level, deviceId, queue);
         }
      }
   }

   private static boolean sameQueue(List<ItemStack> left, List<ItemStack> right) {
      List<ItemStack> safeLeft = left == null ? List.of() : left;
      List<ItemStack> safeRight = right == null ? List.of() : right;
      if (safeLeft.size() != safeRight.size()) {
         return false;
      } else {
         for (int i = 0; i < safeLeft.size(); i++) {
            ItemStack leftStack = safeLeft.get(i);
            ItemStack rightStack = safeRight.get(i);
            if (leftStack.getItem() != rightStack.getItem() || !leftStack.getComponents().equals(rightStack.getComponents())) {
               return false;
            }
         }

         return true;
      }
   }

   public static void recordPlayback(
      ServerLevel level, UUID deviceId, int queueIndex, long elapsedMillis, int durationSeconds, int volumePerMille, String sessionId, boolean playing
   ) {
      recordPlayback(level, deviceId, queueIndex, elapsedMillis, durationSeconds, volumePerMille, PlaybackSessionId.parse(sessionId), playing);
   }

   public static void recordPlayback(
      ServerLevel level,
      UUID deviceId,
      int queueIndex,
      long elapsedMillis,
      int durationSeconds,
      int volumePerMille,
      Optional<PlaybackSessionId> playbackSessionId,
      boolean playing
   ) {
      MP4DeviceStateStore.DeviceEntry current = getOrCreate(level, deviceId, ItemStack.EMPTY);
      MP4Item.State old = current.state();
      int progress = progressPerMille(elapsedMillis, durationSeconds, old.progressPerMille());
      MP4Item.State state = new MP4Item.State(
         playing,
         old.shuffle(),
         old.videoEnabled(),
         old.landscape(),
         old.qualityIndex(),
         queueIndex,
         old.queueScrollOffset(),
         clamp(volumePerMille, 0, 1000),
         old.repeatMode(),
         old.playlistOpen(),
         old.lyricsEnabled(),
         old.subtitleMode(),
         old.subtitleAiEnabled(),
         progress,
         old.rotationHintShown()
      );
      update(
         level,
         deviceId,
         new MP4DeviceStateStore.DeviceEntry(state, current.queue(), Math.max(0L, elapsedMillis), Math.max(0, durationSeconds), playbackSessionId)
      );
   }

   public static void flush(ServerLevel level) {
      if (level != null && !RUNTIME.isEmpty()) {
         MP4PlaybackSavedData data = MP4PlaybackSavedData.get(level);
         RUNTIME.forEach((sourceId, entry) -> data.putDevice(sourceId.value(), entry));
      }
   }

   private static MP4DeviceStateStore.DeviceEntry fromStackContents(ItemStack stack) {
      return !stack.isEmpty() && stack.getItem() instanceof MP4Item
         ? new MP4DeviceStateStore.DeviceEntry(MP4Item.State.DEFAULT, MP4Item.readQueue(stack), 0L, 0, Optional.empty(), 0L)
         : MP4DeviceStateStore.DeviceEntry.EMPTY;
   }

   private static MP4DeviceStateStore.DeviceEntry withStackQueue(MP4DeviceStateStore.DeviceEntry entry, ItemStack stack) {
      if (entry != null && !stack.isEmpty() && stack.getItem() instanceof MP4Item) {
         List<ItemStack> queue = MP4Item.readQueue(stack);
         return queue.isEmpty()
            ? entry
            : new MP4DeviceStateStore.DeviceEntry(
               entry.state(), queue, entry.elapsedMillis(), entry.durationSeconds(), entry.playbackSessionId(), entry.updatedGameTime()
            );
      } else {
         return entry == null ? MP4DeviceStateStore.DeviceEntry.EMPTY : entry;
      }
   }

   private static int progressPerMille(long elapsedMillis, int durationSeconds, int fallback) {
      if (durationSeconds <= 0) {
         return clamp(fallback, 0, 1000);
      } else {
         long durationMillis = durationSeconds * 1000L;
         long elapsed = Math.max(0L, Math.min(durationMillis, elapsedMillis));
         return clamp((int)Math.round(elapsed * 1000.0 / durationMillis), 0, 1000);
      }
   }

   private static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   public record DeviceEntry(
      MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds, Optional<PlaybackSessionId> playbackSessionId, long updatedGameTime
   ) {
      public static final MP4DeviceStateStore.DeviceEntry EMPTY = new MP4DeviceStateStore.DeviceEntry(
         MP4Item.State.DEFAULT, List.of(), 0L, 0, Optional.empty(), 0L
      );

      public DeviceEntry(
         MP4Item.State state,
         List<ItemStack> queue,
         long elapsedMillis,
         int durationSeconds,
         Optional<PlaybackSessionId> playbackSessionId,
         long updatedGameTime
      ) {
         playbackSessionId = playbackSessionId != null ? playbackSessionId : Optional.empty();
         this.state = state;
         this.queue = queue;
         this.elapsedMillis = elapsedMillis;
         this.durationSeconds = durationSeconds;
         this.playbackSessionId = playbackSessionId;
         this.updatedGameTime = updatedGameTime;
      }

      public DeviceEntry(MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds, String sessionId, long updatedGameTime) {
         this(state, queue, elapsedMillis, durationSeconds, PlaybackSessionId.parse(sessionId), updatedGameTime);
      }

      public DeviceEntry(MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds, String sessionId) {
         this(state, queue, elapsedMillis, durationSeconds, sessionId, 0L);
      }

      public DeviceEntry(MP4Item.State state, List<ItemStack> queue, long elapsedMillis, int durationSeconds, Optional<PlaybackSessionId> playbackSessionId) {
         this(state, queue, elapsedMillis, durationSeconds, playbackSessionId, 0L);
      }

      public String sessionId() {
         return this.playbackSessionId.<String>map(session -> session.value()).orElse("");
      }

      public MP4DeviceStateStore.DeviceEntry normalized() {
         List<ItemStack> cleanQueue = this.queue == null
            ? List.of()
            : this.queue.stream().filter(MP4Item::isNetMusicDisc).limit(18L).map(stack -> stack.copyWithCount(1)).toList();
         MP4Item.State safeState = this.state == null ? MP4Item.State.DEFAULT : this.state;
         int maxIndex = Math.max(0, cleanQueue.size() - 1);
         MP4Item.State normalizedState = new MP4Item.State(
            safeState.playing(),
            safeState.shuffle(),
            safeState.videoEnabled(),
            safeState.landscape(),
            MP4DeviceStateStore.clamp(safeState.qualityIndex(), 0, 7),
            MP4DeviceStateStore.clamp(safeState.selectedQueueIndex(), 0, maxIndex),
            MP4DeviceStateStore.clamp(safeState.queueScrollOffset(), 0, 15),
            MP4DeviceStateStore.clamp(safeState.volumePerMille(), 0, 1000),
            MP4DeviceStateStore.clamp(safeState.repeatMode(), 0, 2),
            safeState.playlistOpen(),
            safeState.lyricsEnabled(),
            MP4DeviceStateStore.clamp(safeState.subtitleMode(), 0, 1),
            safeState.subtitleAiEnabled(),
            MP4DeviceStateStore.clamp(safeState.progressPerMille(), 0, 1000),
            safeState.rotationHintShown()
         );
         int duration = Math.max(0, this.durationSeconds);
         long maxElapsed = duration > 0 ? Math.max(0L, duration * 1000L - 50L) : Long.MAX_VALUE;
         return new MP4DeviceStateStore.DeviceEntry(
            normalizedState,
            cleanQueue,
            Math.max(0L, Math.min(maxElapsed, this.elapsedMillis)),
            duration,
            this.playbackSessionId,
            Math.max(0L, this.updatedGameTime)
         );
      }

      public MP4DeviceStateStore.DeviceEntry withState(MP4Item.State newState) {
         return new MP4DeviceStateStore.DeviceEntry(
               newState, this.queue, this.elapsedMillis, this.durationSeconds, this.playbackSessionId, this.updatedGameTime
            )
            .normalized();
      }

      public MP4DeviceStateStore.DeviceEntry withQueue(List<ItemStack> newQueue) {
         return new MP4DeviceStateStore.DeviceEntry(
               this.state, newQueue, this.elapsedMillis, this.durationSeconds, this.playbackSessionId, this.updatedGameTime
            )
            .normalized();
      }

      public MP4DeviceStateStore.DeviceEntry withUpdatedGameTime(long newUpdatedGameTime) {
         return new MP4DeviceStateStore.DeviceEntry(
               this.state, this.queue, this.elapsedMillis, this.durationSeconds, this.playbackSessionId, newUpdatedGameTime
            )
            .normalized();
      }

      public int durationSecondsForSelected() {
         int index = this.state.selectedQueueIndex();
         if (index >= 0 && index < this.queue.size()) {
            SongInfo info = ItemMusicCD.getSongInfo(this.queue.get(index));
            return info != null ? Math.max(0, info.songTime) : 0;
         } else {
            return 0;
         }
      }
   }
}
