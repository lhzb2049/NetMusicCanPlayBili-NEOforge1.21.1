package com.zhongbai233.net_music_can_play_bili.gui.core;

import java.util.List;

public final class WhitelistReviewSelection {
   private WhitelistReviewSelection() {
   }

   public static int indexOf(List<String> ids, String selectedId) {
      if (ids != null && selectedId != null && !selectedId.isBlank()) {
         for (int i = 0; i < ids.size(); i++) {
            if (selectedId.equals(ids.get(i))) {
               return i;
            }
         }

         return -1;
      } else {
         return -1;
      }
   }

   public static WhitelistReviewSelection.RefreshState resolveAfterRefresh(
      List<String> ids, String selectedId, int previousSelectedIndex, int previousScrollOffset, int visibleRows
   ) {
      int size = ids == null ? 0 : ids.size();
      if (size == 0) {
         return new WhitelistReviewSelection.RefreshState(-1, 0);
      } else {
         int selectedIndex = indexOf(ids, selectedId);
         if (selectedIndex < 0) {
            int adjacentIndex = previousSelectedIndex >= 0 ? previousSelectedIndex : previousScrollOffset;
            selectedIndex = clamp(adjacentIndex, 0, size - 1);
         }

         int safeVisibleRows = Math.max(1, visibleRows);
         int scrollOffset = clamp(previousScrollOffset, 0, Math.max(0, size - safeVisibleRows));
         if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
         } else if (selectedIndex >= scrollOffset + safeVisibleRows) {
            scrollOffset = Math.max(0, selectedIndex - safeVisibleRows + 1);
         }

         return new WhitelistReviewSelection.RefreshState(selectedIndex, scrollOffset);
      }
   }

   public static String adjacentIdAfterRemoval(List<String> ids, String removedId) {
      int removedIndex = indexOf(ids, removedId);
      if (removedIndex < 0) {
         return "";
      } else {
         int adjacentIndex = removedIndex + 1 < ids.size() ? removedIndex + 1 : removedIndex - 1;
         if (adjacentIndex < 0) {
            return "";
         } else {
            String adjacentId = ids.get(adjacentIndex);
            return adjacentId == null ? "" : adjacentId;
         }
      }
   }

   public static int clampPageOffset(int requestedOffset, int totalRows, int pageSize) {
      if (totalRows > 0 && pageSize > 0) {
         int lastPageOffset = (totalRows - 1) / pageSize * pageSize;
         return Math.min(lastPageOffset, Math.max(0, requestedOffset));
      } else {
         return 0;
      }
   }

   public static boolean matchesPreview(String entryId, String rawUrl) {
      String id = normalized(entryId);
      String raw = normalized(rawUrl);
      if (id.isEmpty() || raw.isEmpty()) {
         return false;
      } else if (id.equals(raw)) {
         return true;
      } else if (id.regionMatches(true, 0, "url:", 0, 4)) {
         return id.substring(4).equals(raw);
      } else if (!id.regionMatches(true, 0, "bili:", 0, 5)) {
         return false;
      } else {
         String biliId = id.substring(5);
         return raw.equals(biliId) || raw.startsWith(biliId + "|p=");
      }
   }

   private static String normalized(String value) {
      return value == null ? "" : value.trim();
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   public record RefreshState(int selectedIndex, int scrollOffset) {
   }
}
