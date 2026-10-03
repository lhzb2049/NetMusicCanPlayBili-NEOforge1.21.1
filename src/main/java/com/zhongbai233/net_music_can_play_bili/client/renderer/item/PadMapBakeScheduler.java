package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;

final class PadMapBakeScheduler {
   private final long minBakeIntervalNanos;
   private PadMapSnapshot lastSnapshot;
   private PadMapSnapshot pendingSnapshot;
   private long lastSnapshotSignature;
   private long pendingSnapshotSignature;
   private long lastLayoutSignature;
   private long lastBakeNanos;

   PadMapBakeScheduler(long minBakeIntervalNanos) {
      this.minBakeIntervalNanos = Math.max(0L, minBakeIntervalNanos);
   }

   PadMapSnapshot renderedSnapshotOr(PadMapSnapshot fallback) {
      return this.lastSnapshot != null ? this.lastSnapshot : fallback;
   }

   PadMapBakeScheduler.BakeDecision request(PadMapSnapshot snapshot, long nowNanos) {
      if (snapshot == null) {
         return PadMapBakeScheduler.BakeDecision.skip();
      } else {
         long signature = snapshot.contentSignature();
         if (snapshot != this.lastSnapshot && signature != this.lastSnapshotSignature) {
            long layoutSignature = snapshot.layoutSignature();
            boolean layoutChanged = layoutSignature != this.lastLayoutSignature;
            this.pendingSnapshot = snapshot;
            this.pendingSnapshotSignature = signature;
            return !layoutChanged && this.lastSnapshot != null && nowNanos - this.lastBakeNanos < this.minBakeIntervalNanos
               ? PadMapBakeScheduler.BakeDecision.skip()
               : PadMapBakeScheduler.BakeDecision.bake(this.pendingSnapshot, this.pendingSnapshotSignature);
         } else {
            return PadMapBakeScheduler.BakeDecision.skip();
         }
      }
   }

   void complete(PadMapSnapshot snapshot, long signature, long completedNanos) {
      this.lastSnapshot = snapshot;
      this.lastSnapshotSignature = signature;
      this.lastLayoutSignature = snapshot.layoutSignature();
      this.pendingSnapshot = null;
      this.pendingSnapshotSignature = 0L;
      this.lastBakeNanos = completedNanos;
   }

   record BakeDecision(PadMapSnapshot snapshot, long signature) {
      boolean shouldBake() {
         return this.snapshot != null;
      }

      static PadMapBakeScheduler.BakeDecision skip() {
         return new PadMapBakeScheduler.BakeDecision(null, 0L);
      }

      static PadMapBakeScheduler.BakeDecision bake(PadMapSnapshot snapshot, long signature) {
         return new PadMapBakeScheduler.BakeDecision(snapshot, signature);
      }
   }
}
