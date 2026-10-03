package com.zhongbai233.net_music_can_play_bili.network;

import java.util.List;
import java.util.Objects;

final class MP4PlaybackQueuePolicy {
   private MP4PlaybackQueuePolicy() {
   }

   static MP4PlaybackQueuePolicy.Reconciliation reconcile(int currentIndex, String currentSourceUrl, List<String> queueSourceUrls) {
      List<String> safeQueue = queueSourceUrls != null ? queueSourceUrls : List.of();
      int matchedIndex = indexOfSourceUrl(safeQueue, currentSourceUrl);
      if (matchedIndex >= 0) {
         return new MP4PlaybackQueuePolicy.Reconciliation(
            matchedIndex == currentIndex ? MP4PlaybackQueuePolicy.ReconcileAction.KEEP : MP4PlaybackQueuePolicy.ReconcileAction.REMAP, matchedIndex
         );
      } else {
         int selectedIndex = safeQueue.isEmpty() ? 0 : clamp(currentIndex, 0, safeQueue.size() - 1);
         return new MP4PlaybackQueuePolicy.Reconciliation(MP4PlaybackQueuePolicy.ReconcileAction.STOP, selectedIndex);
      }
   }

   static MP4PlaybackQueuePolicy.Completion completion(int currentIndex, int queueSize, int repeatMode) {
      if (queueSize <= 0) {
         return MP4PlaybackQueuePolicy.Completion.stop();
      } else if (repeatMode == 1) {
         return MP4PlaybackQueuePolicy.Completion.advance(clamp(currentIndex, 0, queueSize - 1));
      } else if (currentIndex < queueSize - 1) {
         return MP4PlaybackQueuePolicy.Completion.advance(currentIndex + 1);
      } else {
         return repeatMode == 2 ? MP4PlaybackQueuePolicy.Completion.advance(0) : MP4PlaybackQueuePolicy.Completion.stop();
      }
   }

   private static int indexOfSourceUrl(List<String> queueSourceUrls, String sourceUrl) {
      if (sourceUrl != null && !sourceUrl.isBlank()) {
         for (int index = 0; index < queueSourceUrls.size(); index++) {
            if (Objects.equals(sourceUrl, queueSourceUrls.get(index))) {
               return index;
            }
         }

         return -1;
      } else {
         return -1;
      }
   }

   private static int clamp(int value, int min, int max) {
      return Math.max(min, Math.min(max, value));
   }

   record Completion(boolean shouldAdvance, int nextIndex) {
      private static MP4PlaybackQueuePolicy.Completion advance(int nextIndex) {
         return new MP4PlaybackQueuePolicy.Completion(true, nextIndex);
      }

      private static MP4PlaybackQueuePolicy.Completion stop() {
         return new MP4PlaybackQueuePolicy.Completion(false, -1);
      }
   }

   static enum ReconcileAction {
      KEEP,
      REMAP,
      STOP;
   }

   record Reconciliation(MP4PlaybackQueuePolicy.ReconcileAction action, int selectedIndex) {
      Reconciliation(MP4PlaybackQueuePolicy.ReconcileAction action, int selectedIndex) {
         Objects.requireNonNull(action, "action");
         selectedIndex = Math.max(0, selectedIndex);
         this.action = action;
         this.selectedIndex = selectedIndex;
      }
   }
}
