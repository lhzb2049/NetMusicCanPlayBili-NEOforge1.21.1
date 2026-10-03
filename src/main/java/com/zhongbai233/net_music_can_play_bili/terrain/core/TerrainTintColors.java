package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.Objects;

public record TerrainTintColors(int grass, int foliage, int dryFoliage, int water) {
   public static final TerrainTintColors UNTINTED = new TerrainTintColors(-1, -1, -1, -1);

   public int color(TerrainTintColors.TintType type) {
      Objects.requireNonNull(type, "type");

      return switch (type) {
         case GRASS -> this.grass;
         case FOLIAGE -> this.foliage;
         case DRY_FOLIAGE -> this.dryFoliage;
         case WATER -> this.water;
      };
   }

   public static enum TintType {
      GRASS,
      FOLIAGE,
      DRY_FOLIAGE,
      WATER;
   }
}
