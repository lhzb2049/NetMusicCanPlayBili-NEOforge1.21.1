package com.zhongbai233.net_music_can_play_bili.client.media;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 本地媒体通路开关与白名单根目录（全部为系统属性，与模组其余开关一致）。
 *
 * <p>刻意不依赖任何 Minecraft 类，便于离线跑矩阵验证。
 */
final class LocalMediaProperties {
   /** 本地媒体通路总开关；阶段 0 默认关闭（此时只做识别与判定，不读文件）。 */
   static final String ENABLED = "ncpb.local.enabled";
   /** 白名单根目录，多个用 {@code ;} 或 {@code ,} 分隔；留空则用默认根目录。 */
   static final String ROOTS = "ncpb.local.roots";
   /** 单文件大小上限（字节）。 */
   static final String MAX_BYTES = "ncpb.local.max_bytes";

   private static final long DEFAULT_MAX_BYTES = 2L * 1024L * 1024L * 1024L;

   private LocalMediaProperties() {
   }

   static boolean enabled() {
      return NcpbSystemProperties.booleanValue(ENABLED, false);
   }

   /** 显式配置的白名单根（已去空、去重，保持书写顺序）；未配置时返回空列表。 */
   static List<String> configuredRoots() {
      String raw = NcpbSystemProperties.stringValue(ROOTS, "");
      if (raw.isBlank()) {
         return List.of();
      } else {
         List<String> out = new ArrayList<>();
         for (String part : raw.split("[;,]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
               out.add(trimmed);
            }
         }

         return List.copyOf(out);
      }
   }

   static long maxBytes() {
      long value = NcpbSystemProperties.longValue(MAX_BYTES, DEFAULT_MAX_BYTES);
      return value > 0L ? value : DEFAULT_MAX_BYTES;
   }

   /** 默认根目录：游戏目录 + 用户 Videos / Downloads / Desktop。 */
   static List<String> defaultRootCandidates(String gameDirectory) {
      List<String> out = new ArrayList<>();
      if (gameDirectory != null && !gameDirectory.isBlank()) {
         out.add(gameDirectory);
      }

      String home = System.getProperty("user.home", "");
      if (!home.isBlank()) {
         for (String sub : Arrays.asList("Videos", "Downloads", "Desktop")) {
            out.add(home + java.io.File.separator + sub);
         }
      }

      return List.copyOf(out);
   }

   static String describeRoots(List<String> roots) {
      return roots.isEmpty() ? "(空)" : String.join(" | ", roots);
   }
}
