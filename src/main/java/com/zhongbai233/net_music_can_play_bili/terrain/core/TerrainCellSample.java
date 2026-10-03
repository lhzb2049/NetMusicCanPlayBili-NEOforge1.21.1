package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Objects;

public record TerrainCellSample(
   TerrainCellSample.Availability availability,
   TerrainCellSample.RenderCategory renderCategory,
   String blockId,
   String fluidId,
   boolean hasBlockEntity,
   boolean dynamicModel
) {
   public TerrainCellSample(
      TerrainCellSample.Availability availability,
      TerrainCellSample.RenderCategory renderCategory,
      String blockId,
      String fluidId,
      boolean hasBlockEntity,
      boolean dynamicModel
   ) {
      Objects.requireNonNull(availability, "availability");
      Objects.requireNonNull(renderCategory, "renderCategory");
      blockId = normalizedId(blockId);
      fluidId = normalizedId(fluidId);
      if (availability == TerrainCellSample.Availability.UNKNOWN && renderCategory != TerrainCellSample.RenderCategory.UNKNOWN) {
         throw new IllegalArgumentException("unknown cells must use UNKNOWN render category");
      } else {
         this.availability = availability;
         this.renderCategory = renderCategory;
         this.blockId = blockId;
         this.fluidId = fluidId;
         this.hasBlockEntity = hasBlockEntity;
         this.dynamicModel = dynamicModel;
      }
   }

   public static TerrainCellSample unknown() {
      return new TerrainCellSample(TerrainCellSample.Availability.UNKNOWN, TerrainCellSample.RenderCategory.UNKNOWN, "", "", false, false);
   }

   public static TerrainCellSample air() {
      return new TerrainCellSample(TerrainCellSample.Availability.LOADED, TerrainCellSample.RenderCategory.AIR, "minecraft:air", "", false, false);
   }

   public boolean hasFluid() {
      return !this.fluidId.isEmpty();
   }

   private static String normalizedId(String value) {
      return value == null ? "" : value.trim();
   }

   public static enum Availability {
      LOADED,
      UNKNOWN;
   }

   public static enum RenderCategory {
      AIR,
      MODEL,
      ENTITY_ANIMATED,
      INVISIBLE,
      UNKNOWN;
   }
}
