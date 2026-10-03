package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ControlConsoleSnapshotBudget {
   public static final int MAX_BYTES = 65536;
   private static final int FIXED_PACKET_BYTES = 72;
   private static final int FIXED_ELEMENT_BYTES = 123;

   private ControlConsoleSnapshotBudget() {
   }

   public static int encodedBytes(String displayName, List<ControlConsoleElement> elements) {
      long bytes = 72 + utfBytes(displayName) + varIntBytes(elements.size());

      for (ControlConsoleElement element : elements) {
         bytes += 123L;
         bytes += utfBytes(element.type().name());
         bytes += utfBytes(element.name());
         bytes += utfBytes(element.contentMode());
         bytes += utfBytes(element.text());
         if (bytes > 2147483647L) {
            return Integer.MAX_VALUE;
         }
      }

      return (int)bytes;
   }

   public static void requireWithinLimit(String displayName, List<ControlConsoleElement> elements) {
      if (encodedBytes(displayName, elements) > 65536) {
         throw new IllegalArgumentException("control console snapshot exceeds 64 KiB");
      }
   }

   private static int utfBytes(String value) {
      int length = value.getBytes(StandardCharsets.UTF_8).length;
      return varIntBytes(length) + length;
   }

   private static int varIntBytes(int value) {
      int bytes;
      for (bytes = 1; (value & -128) != 0; bytes++) {
         value >>>= 7;
      }

      return bytes;
   }
}
