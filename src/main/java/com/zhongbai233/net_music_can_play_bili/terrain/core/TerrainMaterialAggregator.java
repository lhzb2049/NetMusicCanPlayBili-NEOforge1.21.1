package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;

public final class TerrainMaterialAggregator {
   private TerrainMaterialAggregator() {
   }

   public static <T> List<TerrainMaterialAggregator.Cell<T>> aggregate(List<TerrainMaterialAggregator.Sample<T>> visible, int size) {
      Objects.requireNonNull(visible, "visible");
      if (size > 0 && 16 % size == 0) {
         int dimension = 16 / size;
         Map<Integer, TerrainMaterialAggregator.Group<T>> groups = new LinkedHashMap<>();
         int order = 0;

         for (TerrainMaterialAggregator.Sample<T> sample : visible) {
            int gx = sample.localX() / size;
            int gy = sample.localY() / size;
            int gz = sample.localZ() / size;
            int index = (gy * dimension + gz) * dimension + gx;
            groups.computeIfAbsent(index, ignored -> new TerrainMaterialAggregator.Group<>()).accept(sample, order++);
         }

         List<TerrainMaterialAggregator.Cell<T>> cells = new ArrayList<>(groups.size());

         for (Entry<Integer, TerrainMaterialAggregator.Group<T>> entry : groups.entrySet()) {
            int index = entry.getKey();
            int gx = index % dimension;
            int gz = index / dimension % dimension;
            int gy = index / (dimension * dimension);
            cells.add(new TerrainMaterialAggregator.Cell<>(gx * size, gy * size, gz * size, size, entry.getValue().representative()));
         }

         return List.copyOf(cells);
      } else {
         throw new IllegalArgumentException("material cell size must divide section size");
      }
   }

   public record Cell<T>(int localX, int localY, int localZ, int size, TerrainMaterialAggregator.Sample<T> representative) {
      public Cell(int localX, int localY, int localZ, int size, TerrainMaterialAggregator.Sample<T> representative) {
         if (size > 0 && localX >= 0 && localY >= 0 && localZ >= 0 && localX + size <= 16 && localY + size <= 16 && localZ + size <= 16) {
            Objects.requireNonNull(representative, "representative");
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.size = size;
            this.representative = representative;
         } else {
            throw new IllegalArgumentException("terrain material cell must fit inside its section");
         }
      }
   }

   private static final class Choice<T> {
      private TerrainMaterialAggregator.Sample<T> sample;
      private int count;
      private final int firstOrder;

      private Choice(TerrainMaterialAggregator.Sample<T> sample, int count, int firstOrder) {
         this.sample = sample;
         this.count = count;
         this.firstOrder = firstOrder;
      }
   }

   private static final class Group<T> {
      private final Map<T, TerrainMaterialAggregator.Choice<T>> choices = new HashMap<>();

      private void accept(TerrainMaterialAggregator.Sample<T> sample, int order) {
         TerrainMaterialAggregator.Choice<T> choice = this.choices.get(sample.material());
         if (choice == null) {
            this.choices.put(sample.material(), new TerrainMaterialAggregator.Choice<>(sample, 1, order));
         } else {
            choice.count++;
            if (sample.localY() > choice.sample.localY()) {
               choice.sample = sample;
            }
         }
      }

      private TerrainMaterialAggregator.Sample<T> representative() {
         TerrainMaterialAggregator.Choice<T> winner = null;

         for (TerrainMaterialAggregator.Choice<T> choice : this.choices.values()) {
            if (winner == null
               || choice.count > winner.count
               || choice.count == winner.count && choice.sample.localY() > winner.sample.localY()
               || choice.count == winner.count && choice.sample.localY() == winner.sample.localY() && choice.firstOrder < winner.firstOrder) {
               winner = choice;
            }
         }

         return Objects.requireNonNull(winner, "empty material group").sample;
      }
   }

   public record Sample<T>(int localX, int localY, int localZ, T material) {
      public Sample(int localX, int localY, int localZ, T material) {
         if (localX >= 0 && localX < 16 && localY >= 0 && localY < 16 && localZ >= 0 && localZ < 16) {
            Objects.requireNonNull(material, "material");
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.material = material;
         } else {
            throw new IllegalArgumentException("terrain material sample coordinates must be within [0, 15]");
         }
      }
   }
}
