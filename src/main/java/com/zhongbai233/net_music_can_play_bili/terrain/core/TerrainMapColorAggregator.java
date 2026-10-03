package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;

public final class TerrainMapColorAggregator {
   private TerrainMapColorAggregator() {
   }

   public static <T> List<TerrainMapColorAggregator.Cell<T>> aggregate(List<TerrainMapColorAggregator.Sample<T>> visible, int size) {
      Objects.requireNonNull(visible, "visible");
      if (size > 0 && 16 % size == 0) {
         int dimension = 16 / size;
         Map<Integer, TerrainMapColorAggregator.Group<T>> groups = new LinkedHashMap<>();
         int order = 0;

         for (TerrainMapColorAggregator.Sample<T> sample : visible) {
            int gx = sample.localX() / size;
            int gy = sample.localY() / size;
            int gz = sample.localZ() / size;
            int index = (gy * dimension + gz) * dimension + gx;
            groups.computeIfAbsent(index, ignored -> new TerrainMapColorAggregator.Group<>()).accept(sample, order++);
         }

         List<TerrainMapColorAggregator.Cell<T>> cells = new ArrayList<>(groups.size());

         for (Entry<Integer, TerrainMapColorAggregator.Group<T>> entry : groups.entrySet()) {
            int index = entry.getKey();
            int gx = index % dimension;
            int gz = index / dimension % dimension;
            int gy = index / (dimension * dimension);
            TerrainMapColorAggregator.Group<T> group = entry.getValue();
            cells.add(new TerrainMapColorAggregator.Cell<>(gx * size, gy * size, gz * size, size, group.averageColor(), group.representative()));
         }

         return List.copyOf(cells);
      } else {
         throw new IllegalArgumentException("map-color cell size must divide section size");
      }
   }

   public record Cell<T>(int localX, int localY, int localZ, int size, int color, TerrainMapColorAggregator.Sample<T> representative) {
      public Cell(int localX, int localY, int localZ, int size, int color, TerrainMapColorAggregator.Sample<T> representative) {
         if (size <= 0 || localX < 0 || localY < 0 || localZ < 0 || localX + size > 16 || localY + size > 16 || localZ + size > 16) {
            throw new IllegalArgumentException("terrain map-color cell must fit inside its section");
         } else if ((color & 0xFF000000) != -16777216) {
            throw new IllegalArgumentException("terrain map-color cell must be opaque");
         } else {
            Objects.requireNonNull(representative, "representative");
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.size = size;
            this.color = color;
            this.representative = representative;
         }
      }
   }

   private static final class Choice<T> {
      private TerrainMapColorAggregator.Sample<T> sample;
      private int count;
      private final int firstOrder;

      private Choice(TerrainMapColorAggregator.Sample<T> sample, int count, int firstOrder) {
         this.sample = sample;
         this.count = count;
         this.firstOrder = firstOrder;
      }
   }

   private static final class Group<T> {
      private final Map<T, TerrainMapColorAggregator.Choice<T>> choices = new HashMap<>();
      private long red;
      private long green;
      private long blue;
      private int count;

      private void accept(TerrainMapColorAggregator.Sample<T> sample, int order) {
         this.red = this.red + (sample.mapColor() >>> 16 & 0xFF);
         this.green = this.green + (sample.mapColor() >>> 8 & 0xFF);
         this.blue = this.blue + (sample.mapColor() & 0xFF);
         this.count++;
         TerrainMapColorAggregator.Choice<T> choice = this.choices.get(sample.material());
         if (choice == null) {
            this.choices.put(sample.material(), new TerrainMapColorAggregator.Choice<>(sample, 1, order));
         } else {
            choice.count++;
            if (sample.localY() > choice.sample.localY()) {
               choice.sample = sample;
            }
         }
      }

      private int averageColor() {
         int half = this.count / 2;
         int r = (int)((this.red + half) / this.count);
         int g = (int)((this.green + half) / this.count);
         int b = (int)((this.blue + half) / this.count);
         return 0xFF000000 | r << 16 | g << 8 | b;
      }

      private TerrainMapColorAggregator.Sample<T> representative() {
         TerrainMapColorAggregator.Choice<T> winner = null;

         for (TerrainMapColorAggregator.Choice<T> choice : this.choices.values()) {
            if (winner == null
               || choice.count > winner.count
               || choice.count == winner.count && choice.sample.localY() > winner.sample.localY()
               || choice.count == winner.count && choice.sample.localY() == winner.sample.localY() && choice.firstOrder < winner.firstOrder) {
               winner = choice;
            }
         }

         return Objects.requireNonNull(winner, "empty map-color group").sample;
      }
   }

   public record Sample<T>(int localX, int localY, int localZ, T material, int mapColor) {
      public Sample(int localX, int localY, int localZ, T material, int mapColor) {
         if (localX >= 0 && localX < 16 && localY >= 0 && localY < 16 && localZ >= 0 && localZ < 16) {
            Objects.requireNonNull(material, "material");
            mapColor &= 16777215;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.material = material;
            this.mapColor = mapColor;
         } else {
            throw new IllegalArgumentException("terrain map-color sample coordinates must be within [0, 15]");
         }
      }
   }
}
