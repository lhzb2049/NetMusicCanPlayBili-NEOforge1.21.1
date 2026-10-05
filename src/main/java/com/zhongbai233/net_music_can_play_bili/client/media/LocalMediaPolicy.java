package com.zhongbai233.net_music_can_play_bili.client.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本地文件白名单策略：只有落在允许根目录内的普通文件才放行。
 *
 * <p>判定顺序与拒绝原因都是一一对应的，便于离线矩阵逐条断言，也便于玩家看懂为什么被拒。
 * 关键点是**用真实路径（{@code toRealPath}）比对根目录**，这样符号链接指向根目录之外时也会被拒。
 */
final class LocalMediaPolicy {
   private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
      "con", "prn", "aux", "nul",
      "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
      "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
   );

   private LocalMediaPolicy() {
   }

   record Decision(boolean allowed, String reason) {
   }

   static Decision check(Path candidate, String gameDirectory) {
      if (candidate == null) {
         return deny("空路径");
      }

      Path absolute;
      try {
         absolute = candidate.toAbsolutePath().normalize();
      } catch (RuntimeException error) {
         return deny("路径无法规范化: " + error);
      }

      if (isUnc(absolute)) {
         return deny("UNC 网络路径不在白名单机制覆盖范围内，已拒绝");
      }

      if (hasReservedDeviceName(absolute)) {
         return deny("路径使用了 Windows 保留设备名，已拒绝");
      }

      if (!LocalMediaProperties.enabled()) {
         return deny("本地媒体通路未启用（-D" + LocalMediaProperties.ENABLED + "=true 开启）");
      }

      Path effective = realOrSelf(absolute);
      List<String> roots = effectiveRoots(gameDirectory);
      boolean inside = false;
      for (String root : roots) {
         Path rootPath = toPath(root);
         if (rootPath == null) {
            continue;
         }

         if (effective.startsWith(realOrSelf(rootPath.toAbsolutePath().normalize()))) {
            inside = true;
            break;
         }
      }

      if (!inside) {
         return deny("路径不在白名单根目录内（" + LocalMediaProperties.ROOTS + " 当前="
            + LocalMediaProperties.describeRoots(roots) + "）");
      }

      if (!Files.isRegularFile(effective)) {
         return deny("文件不存在或不是普通文件");
      }

      long size;
      try {
         size = Files.size(effective);
      } catch (IOException error) {
         return deny("读取文件大小失败: " + error);
      }

      long max = LocalMediaProperties.maxBytes();
      if (size > max) {
         return deny("文件大小 " + size + " 字节超过上限 " + max);
      }

      return new Decision(true, "白名单与大小校验通过");
   }

   /** 实际生效的根目录：显式配置优先，否则用默认候选里真实存在的那些。 */
   static List<String> effectiveRoots(String gameDirectory) {
      List<String> configured = LocalMediaProperties.configuredRoots();
      if (!configured.isEmpty()) {
         return configured;
      }

      List<String> out = new ArrayList<>();
      for (String candidate : LocalMediaProperties.defaultRootCandidates(gameDirectory)) {
         Path path = toPath(candidate);
         if (path != null && Files.isDirectory(realOrSelf(path.toAbsolutePath().normalize()))) {
            out.add(candidate);
         }
      }

      return List.copyOf(out);
   }

   static boolean isUnc(Path path) {
      String text = path.toString();
      return text.startsWith("\\\\") || text.startsWith("//");
   }

   static boolean hasReservedDeviceName(Path path) {
      Path name = path.getFileName();
      if (name == null) {
         return false;
      } else {
         String lower = name.toString().toLowerCase(Locale.ROOT);
         int dot = lower.indexOf(46);
         String stem = dot >= 0 ? lower.substring(0, dot) : lower;
         return RESERVED_DEVICE_NAMES.contains(stem);
      }
   }

   private static Path realOrSelf(Path path) {
      try {
         return path.toRealPath();
      } catch (IOException | RuntimeException error) {
         return path;
      }
   }

   private static Path toPath(String text) {
      try {
         return Paths.get(text);
      } catch (RuntimeException error) {
         return null;
      }
   }

   private static Decision deny(String reason) {
      return new Decision(false, reason);
   }
}
