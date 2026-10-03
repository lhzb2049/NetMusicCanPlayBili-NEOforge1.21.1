package com.zhongbai233.net_music_can_play_bili.client.pad;

final class PadMapJobProgress {
   private final int totalCells;
   private final int cellsPerChunkBudget;
   private final int initialBurstCells;
   private final int previewChunks;
   private int cursor;
   private int lastPreviewCursor;
   private int steps;

   PadMapJobProgress(int totalCells, int cellsPerChunkBudget, int initialBurstCells, int previewChunks) {
      this.totalCells = Math.max(0, totalCells);
      this.cellsPerChunkBudget = Math.max(1, cellsPerChunkBudget);
      this.initialBurstCells = Math.max(0, initialBurstCells);
      this.previewChunks = Math.max(1, previewChunks);
   }

   PadMapJobProgress.Step beginStep(int chunkBudget) {
      this.steps++;
      int cellsPerStep = Math.max(1, chunkBudget) * this.cellsPerChunkBudget;
      if (this.steps == 1) {
         cellsPerStep = Math.max(cellsPerStep, this.initialBurstCells);
      }

      int start = this.cursor;
      int end = Math.min(this.totalCells, this.cursor + cellsPerStep);
      this.cursor = end;
      return new PadMapJobProgress.Step(start, end);
   }

   boolean done() {
      return this.cursor >= this.totalCells;
   }

   boolean shouldPublishPreview() {
      if (this.cursor <= 0 || this.cursor == this.lastPreviewCursor) {
         return false;
      } else {
         return this.lastPreviewCursor == 0 ? true : this.cursor - this.lastPreviewCursor >= this.previewChunks * this.cellsPerChunkBudget;
      }
   }

   void markPreviewPublished() {
      this.lastPreviewCursor = this.cursor;
   }

   int steps() {
      return this.steps;
   }

   int doneCells() {
      return Math.min(this.cursor, this.totalCells);
   }

   int totalCells() {
      return this.totalCells;
   }

   int percent() {
      return this.totalCells <= 0 ? 100 : Math.round(this.doneCells() * 100.0F / this.totalCells);
   }

   record Step(int startInclusive, int endExclusive) {
   }
}
