package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class VideoConsumerRegistry<T> {
   private final AtomicReference<List<T>> projectors = new AtomicReference<>(List.of());
   private volatile boolean guiConsumer;

   void replaceProjectors(Collection<? extends T> replacements) {
      if (replacements != null && !replacements.isEmpty()) {
         LinkedHashSet<T> distinct = new LinkedHashSet<>();

         for (T replacement : replacements) {
            if (replacement != null) {
               distinct.add(replacement);
            }
         }

         this.projectors.set(List.copyOf(distinct));
      } else {
         this.projectors.set(List.of());
      }
   }

   void addProjector(T projector) {
      if (projector != null) {
         this.projectors.updateAndGet(current -> {
            if (current.contains(projector)) {
               return current;
            } else {
               ArrayList<T> updated = new ArrayList<>(current);
               updated.add(projector);
               return List.copyOf(updated);
            }
         });
      }
   }

   void removeProjector(T projector) {
      if (projector != null) {
         this.projectors.updateAndGet(current -> {
            if (!current.contains(projector)) {
               return current;
            } else {
               ArrayList<T> updated = new ArrayList<>(current);
               updated.remove(projector);
               return List.copyOf(updated);
            }
         });
      }
   }

   boolean containsProjector(T projector) {
      return projector != null && this.projectors.get().contains(projector);
   }

   List<T> projectors() {
      return this.projectors.get();
   }

   boolean hasProjectors() {
      return !this.projectors.get().isEmpty();
   }

   int projectorCount() {
      return this.projectors.get().size();
   }

   void setGuiConsumer(boolean value) {
      this.guiConsumer = value;
   }

   boolean hasGuiConsumer() {
      return this.guiConsumer;
   }

   boolean hasDirectConsumer() {
      return this.guiConsumer || this.hasProjectors();
   }
}
