package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TerrainSectionCaptureJob {
   private static final long ESTIMATED_CELL_BYTES = 24L;
   private final TerrainSectionKey key;
   private final long generation;
   private final TerrainSectionCaptureJob.TerrainCellReader reader;
   private final TerrainCellSample[] cells = new TerrainCellSample[4096];
   private int cursor;

   public TerrainSectionCaptureJob(TerrainSectionKey key, long generation, TerrainSectionCaptureJob.TerrainCellReader reader) {
      this.key = Objects.requireNonNull(key, "key");
      if (generation < 0L) {
         throw new IllegalArgumentException("capture generation must be non-negative");
      } else {
         this.generation = generation;
         this.reader = Objects.requireNonNull(reader, "reader");
      }
   }

   public TerrainSectionCaptureJob.StepResult step(TerrainSamplingBudget.Meter meter) {
      Objects.requireNonNull(meter, "meter");
      if (this.done()) {
         return new TerrainSectionCaptureJob.StepResult(0, true);
      } else if (!meter.tryAcquireSection()) {
         return new TerrainSectionCaptureJob.StepResult(0, false);
      } else {
         int sampled;
         for (sampled = 0; !this.done() && meter.tryAcquireCell(); sampled++) {
            int localX = this.cursor % 16;
            int localZ = this.cursor / 16 % 16;
            int localY = this.cursor / 256;
            TerrainCellSample sample = this.reader.read(this.key.minBlockX() + localX, this.key.minBlockY() + localY, this.key.minBlockZ() + localZ);
            this.cells[this.cursor] = sample != null ? sample : TerrainCellSample.unknown();
            this.cursor++;
         }

         return new TerrainSectionCaptureJob.StepResult(sampled, this.done());
      }
   }

   public boolean done() {
      return this.cursor >= this.cells.length;
   }

   public int sampledCells() {
      return this.cursor;
   }

   public Optional<TerrainSectionSnapshot> completedSnapshot() {
      if (!this.done()) {
         return Optional.empty();
      } else {
         List<TerrainCellSample> immutableCells = new ArrayList<>(this.cells.length);
         Collections.addAll(immutableCells, this.cells);
         return Optional.of(new TerrainSectionSnapshot(this.key, this.generation, immutableCells, 24L * this.cells.length));
      }
   }

   public record StepResult(int sampledCells, boolean completed) {
   }

   @FunctionalInterface
   public interface TerrainCellReader {
      TerrainCellSample read(int var1, int var2, int var3);
   }
}
