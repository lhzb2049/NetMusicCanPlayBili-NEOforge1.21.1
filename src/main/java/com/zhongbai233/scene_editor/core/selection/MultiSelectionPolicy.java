package com.zhongbai233.scene_editor.core.selection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MultiSelectionPolicy {
   private MultiSelectionPolicy() {
   }

   public static MultiSelectionPolicy.Result click(
      List<UUID> orderedElementIds, Collection<UUID> selectedElementIds, UUID primaryElementId, UUID clickedElementId, boolean shiftDown, boolean controlDown
   ) {
      Objects.requireNonNull(orderedElementIds, "orderedElementIds");
      Objects.requireNonNull(selectedElementIds, "selectedElementIds");
      Objects.requireNonNull(clickedElementId, "clickedElementId");
      if (!orderedElementIds.contains(clickedElementId)) {
         throw new IllegalArgumentException("clicked element is not in the document");
      } else {
         LinkedHashSet<UUID> selected = new LinkedHashSet<>(selectedElementIds);
         if (shiftDown && primaryElementId != null) {
            int anchor = orderedElementIds.indexOf(primaryElementId);
            int target = orderedElementIds.indexOf(clickedElementId);
            if (anchor >= 0) {
               if (!controlDown) {
                  selected.clear();
               }

               selected.addAll(orderedElementIds.subList(Math.min(anchor, target), Math.max(anchor, target) + 1));
               return new MultiSelectionPolicy.Result(List.copyOf(selected), clickedElementId);
            }
         }

         if (controlDown) {
            if (selected.contains(clickedElementId)) {
               if (selected.size() == 1) {
                  return new MultiSelectionPolicy.Result(List.copyOf(selected), primaryElementId);
               } else {
                  selected.remove(clickedElementId);
                  UUID primary = clickedElementId.equals(primaryElementId) ? last(selected) : primaryElementId;
                  return new MultiSelectionPolicy.Result(List.copyOf(selected), primary);
               }
            } else {
               selected.add(clickedElementId);
               return new MultiSelectionPolicy.Result(List.copyOf(selected), clickedElementId);
            }
         } else {
            return new MultiSelectionPolicy.Result(List.of(clickedElementId), clickedElementId);
         }
      }
   }

   private static UUID last(LinkedHashSet<UUID> values) {
      UUID result = null;

      for (UUID value : values) {
         result = value;
      }

      return result;
   }

   public record Result(List<UUID> selectedElementIds, UUID primaryElementId) {
      public Result(List<UUID> selectedElementIds, UUID primaryElementId) {
         selectedElementIds = List.copyOf(Objects.requireNonNull(selectedElementIds, "selectedElementIds"));
         if (primaryElementId != null && !selectedElementIds.contains(primaryElementId)) {
            throw new IllegalArgumentException("primary element must be selected");
         } else {
            this.selectedElementIds = selectedElementIds;
            this.primaryElementId = primaryElementId;
         }
      }
   }
}
