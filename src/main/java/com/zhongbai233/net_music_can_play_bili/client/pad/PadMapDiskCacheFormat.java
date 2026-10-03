package com.zhongbai233.net_music_can_play_bili.client.pad;

final class PadMapDiskCacheFormat {
   static final PadMapDiskCacheFormat.Header CELLS = new PadMapDiskCacheFormat.Header(1313885507, 18);
   static final PadMapDiskCacheFormat.Header SNAPSHOT = new PadMapDiskCacheFormat.Header(1313885523, 18);

   private PadMapDiskCacheFormat() {
   }

   static int encodeTile(PadMapTileKind tile) {
      return tile == null ? PadMapTileKind.UNKNOWN.ordinal() : tile.ordinal();
   }

   static PadMapTileKind decodeTile(int ordinal) {
      PadMapTileKind[] values = PadMapTileKind.values();
      return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
   }

   record Header(int magic, int version) {
      boolean matches(int magic, int version) {
         return this.magic == magic && this.version == version;
      }
   }
}
