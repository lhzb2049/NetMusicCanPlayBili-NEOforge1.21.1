package com.zhongbai233.net_music_can_play_bili.client.pad;

public enum PadMapTileKind {
   UNKNOWN(-7890778, "等待采样"),
   GRASS(-1646379, "地面"),
   INDOOR_FLOOR(-1449517, "室内"),
   BUILDING(-2435633, "建筑"),
   WATER(-6439474, "水域"),
   TREE(-4402254, "林地"),
   FARMLAND(-2042441, "农田"),
   ROCK(-3093050, "岩地"),
   SNOW(-1514018, "雪地");

   private final int color;
   private final String label;

   private PadMapTileKind(int color, String label) {
      this.color = color;
      this.label = label;
   }

   public int color() {
      return this.color;
   }

   public String label() {
      return this.label;
   }
}
