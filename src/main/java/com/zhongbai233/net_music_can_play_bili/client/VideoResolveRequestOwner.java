package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.media.sync.ResolveGeneration;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class VideoResolveRequestOwner<T> {
   private final int qualityCeiling;
   private final ResolveGeneration requestGeneration;
   private final List<T> consumerPositions;
   private final AtomicBoolean cancelled = new AtomicBoolean();
   private final AtomicReference<CancellableTaskFuture<?>> task = new AtomicReference<>();

   VideoResolveRequestOwner(int qualityCeiling, ResolveGeneration requestGeneration, List<T> consumerPositions) {
      this.qualityCeiling = qualityCeiling;
      this.requestGeneration = Objects.requireNonNull(requestGeneration, "requestGeneration");
      this.consumerPositions = List.copyOf(consumerPositions);
   }

   boolean matches(long requestedElapsedMillis, int requestedQualityCeiling) {
      return this.qualityCeiling == requestedQualityCeiling;
   }

   ResolveGeneration requestGeneration() {
      return this.requestGeneration;
   }

   List<T> consumerPositions() {
      return this.consumerPositions;
   }

   void bind(CancellableTaskFuture<?> value) {
      if (value != null && this.task.compareAndSet(null, value)) {
         if (this.cancelled.get()) {
            value.cancel(true);
         }
      } else {
         if (value != null) {
            value.cancel(true);
         }
      }
   }

   void cancel() {
      if (this.cancelled.compareAndSet(false, true)) {
         CancellableTaskFuture<?> value = this.task.get();
         if (value != null) {
            value.cancel(true);
         }
      }
   }
}
