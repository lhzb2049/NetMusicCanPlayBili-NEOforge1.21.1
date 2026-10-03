package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Objects;
import java.util.function.LongSupplier;

public record TerrainSamplingBudget(int maxCells, int maxSections, long maxNanos) {
   public TerrainSamplingBudget(int maxCells, int maxSections, long maxNanos) {
      if (maxCells > 0 && maxSections > 0 && maxNanos > 0L) {
         this.maxCells = maxCells;
         this.maxSections = maxSections;
         this.maxNanos = maxNanos;
      } else {
         throw new IllegalArgumentException("terrain sampling budgets must be positive");
      }
   }

   public static TerrainSamplingBudget normal() {
      return new TerrainSamplingBudget(512, 2, 1500000L);
   }

   public TerrainSamplingBudget.Meter start() {
      return this.start(System::nanoTime);
   }

   TerrainSamplingBudget.Meter start(LongSupplier clock) {
      return new TerrainSamplingBudget.Meter(this, clock);
   }

   public static final class Meter {
      private final TerrainSamplingBudget budget;
      private final LongSupplier clock;
      private final long startNanos;
      private int cells;
      private int sections;

      private Meter(TerrainSamplingBudget budget, LongSupplier clock) {
         this.budget = budget;
         this.clock = Objects.requireNonNull(clock, "clock");
         this.startNanos = clock.getAsLong();
      }

      public boolean tryAcquireSection() {
         if (this.sections < this.budget.maxSections && !this.expired()) {
            this.sections++;
            return true;
         } else {
            return false;
         }
      }

      public boolean tryAcquireCell() {
         if (this.cells < this.budget.maxCells && !this.expired()) {
            this.cells++;
            return true;
         } else {
            return false;
         }
      }

      public boolean exhausted() {
         return this.cells >= this.budget.maxCells || this.sections >= this.budget.maxSections || this.expired();
      }

      public int consumedCells() {
         return this.cells;
      }

      public int consumedSections() {
         return this.sections;
      }

      public long elapsedNanos() {
         return Math.max(0L, this.clock.getAsLong() - this.startNanos);
      }

      private boolean expired() {
         return this.elapsedNanos() >= this.budget.maxNanos;
      }
   }
}
