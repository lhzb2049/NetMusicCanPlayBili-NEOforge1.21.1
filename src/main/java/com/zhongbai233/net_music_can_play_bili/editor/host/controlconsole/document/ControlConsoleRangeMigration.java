package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

public final class ControlConsoleRangeMigration {
   private static final int LAST_SCHEMA_WITH_LEGACY_DEFAULT = 4;
   private static final double LEGACY_DEFAULT_X = 8.0;
   private static final double LEGACY_DEFAULT_Y = 4.0;
   private static final double LEGACY_DEFAULT_Z = 8.0;

   private ControlConsoleRangeMigration() {
   }

   public static ControlConsoleRangeMigration.Range migrate(int sourceSchemaVersion, double x, double y, double z) {
      return sourceSchemaVersion <= 4 && Double.compare(x, 8.0) == 0 && Double.compare(y, 4.0) == 0 && Double.compare(z, 8.0) == 0
         ? new ControlConsoleRangeMigration.Range(64.0, 32.0, 64.0)
         : new ControlConsoleRangeMigration.Range(x, y, z);
   }

   public record Range(double x, double y, double z) {
   }
}
