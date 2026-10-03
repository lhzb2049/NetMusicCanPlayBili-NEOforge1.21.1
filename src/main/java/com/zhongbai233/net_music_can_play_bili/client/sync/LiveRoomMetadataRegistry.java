package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LiveRoomMetadataRegistry {
   private static final ConcurrentHashMap<LiveRoomMetadataRegistry.SourceKey, LiveRoomMetadataRegistry.Snapshot> ACTIVE = new ConcurrentHashMap<>();

   private LiveRoomMetadataRegistry() {
   }

   public static void publish(
      LiveRoomMetadataRegistry.SourceKey source,
      PlaybackSessionId sessionId,
      String roomId,
      String title,
      String parentAreaName,
      String areaName,
      int liveStatus
   ) {
      ACTIVE.put(
         Objects.requireNonNull(source, "source"), new LiveRoomMetadataRegistry.Snapshot(sessionId, roomId, title, parentAreaName, areaName, liveStatus)
      );
   }

   public static Optional<LiveRoomMetadataRegistry.Snapshot> snapshot(LiveRoomMetadataRegistry.SourceKey source, String expectedRoomId) {
      if (source == null) {
         return Optional.empty();
      } else {
         LiveRoomMetadataRegistry.Snapshot snapshot = ACTIVE.get(source);
         String expected = normalize(expectedRoomId, 32);
         return snapshot == null || !expected.isEmpty() && !expected.equals(snapshot.roomId()) ? Optional.empty() : Optional.of(snapshot);
      }
   }

   public static boolean remove(LiveRoomMetadataRegistry.SourceKey source, PlaybackSessionId expectedSessionId) {
      if (source != null && expectedSessionId != null) {
         LiveRoomMetadataRegistry.Snapshot current = ACTIVE.get(source);
         return current != null && current.sessionId().equals(expectedSessionId) && ACTIVE.remove(source, current);
      } else {
         return false;
      }
   }

   public static void clear() {
      ACTIVE.clear();
   }

   public static int size() {
      return ACTIVE.size();
   }

   private static String normalize(String value, int maxLength) {
      String normalized = value == null ? "" : value.trim();
      return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
   }

   public record Snapshot(PlaybackSessionId sessionId, String roomId, String title, String parentAreaName, String areaName, int liveStatus) {
      public Snapshot(PlaybackSessionId sessionId, String roomId, String title, String parentAreaName, String areaName, int liveStatus) {
         sessionId = Objects.requireNonNull(sessionId, "sessionId");
         roomId = LiveRoomMetadataRegistry.normalize(roomId, 32);
         title = LiveRoomMetadataRegistry.normalize(title, 256);
         parentAreaName = LiveRoomMetadataRegistry.normalize(parentAreaName, 64);
         areaName = LiveRoomMetadataRegistry.normalize(areaName, 64);
         this.sessionId = sessionId;
         this.roomId = roomId;
         this.title = title;
         this.parentAreaName = parentAreaName;
         this.areaName = areaName;
         this.liveStatus = liveStatus;
      }
   }

   public record SourceKey(int x, int y, int z) {
   }
}
