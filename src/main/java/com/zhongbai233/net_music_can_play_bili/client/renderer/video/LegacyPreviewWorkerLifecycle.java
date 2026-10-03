package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

final class LegacyPreviewWorkerLifecycle<W, D> {
   static final long REJECTED_GENERATION = -1L;
   private volatile boolean started;
   private volatile boolean running;
   private volatile long generation;
   private volatile W worker;
   private volatile D decoder;

   synchronized long tryBegin() {
      if (this.started) {
         return -1L;
      } else {
         this.generation++;
         this.started = true;
         this.running = true;
         this.worker = null;
         this.decoder = null;
         return this.generation;
      }
   }

   synchronized boolean bindWorker(long candidateGeneration, W candidate) {
      if (this.isActive(candidateGeneration) && candidate != null && this.worker == null) {
         this.worker = candidate;
         return true;
      } else {
         return false;
      }
   }

   synchronized boolean bindDecoder(long candidateGeneration, D candidate) {
      if (this.isActive(candidateGeneration) && candidate != null && this.decoder == null) {
         this.decoder = candidate;
         return true;
      } else {
         return false;
      }
   }

   synchronized LegacyPreviewWorkerLifecycle.Detached<W, D> stopAndDetach() {
      LegacyPreviewWorkerLifecycle.Detached<W, D> detached = new LegacyPreviewWorkerLifecycle.Detached<>(this.generation, this.worker, this.decoder);
      this.generation++;
      this.started = false;
      this.running = false;
      this.worker = null;
      this.decoder = null;
      return detached;
   }

   synchronized void requestStop() {
      this.running = false;
   }

   synchronized boolean finish(long candidateGeneration, D completedDecoder) {
      if (this.decoder == completedDecoder) {
         this.decoder = null;
      }

      if (candidateGeneration != this.generation) {
         return false;
      } else {
         this.started = false;
         this.running = false;
         this.worker = null;
         return true;
      }
   }

   boolean isActive(long candidateGeneration) {
      return this.running && candidateGeneration == this.generation;
   }

   boolean isCurrent(long candidateGeneration) {
      return candidateGeneration == this.generation;
   }

   boolean isStarted() {
      return this.started;
   }

   boolean isRunning() {
      return this.running;
   }

   LegacyPreviewWorkerLifecycle.Snapshot<W, D> snapshot() {
      return new LegacyPreviewWorkerLifecycle.Snapshot<>(this.started, this.running, this.generation, this.worker, this.decoder);
   }

   record Detached<W, D>(long generation, W worker, D decoder) {
   }

   record Snapshot<W, D>(boolean started, boolean running, long generation, W worker, D decoder) {
   }
}
