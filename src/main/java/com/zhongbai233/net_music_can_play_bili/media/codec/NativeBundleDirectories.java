package com.zhongbai233.net_music_can_play_bili.media.codec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class NativeBundleDirectories {
   private static final List<Pattern> LEGACY_NATIVE_FILE_PATTERNS = List.of(
      Pattern.compile("^(avutil|swscale|avcodec|swresample)-\\d+\\.dll$"),
      Pattern.compile("^lib(avutil|swscale|avcodec|swresample)\\.\\d+\\.dylib$"),
      Pattern.compile("^lib(avutil|swscale|avcodec|swresample)\\.so\\.\\d+$")
   );
   private static final Set<String> LEGACY_NATIVE_FILE_NAMES = Set.of(
      "eac3_jni.dll", "video_jni.dll", "libiconv-2.dll", "libwinpthread-1.dll", "libeac3_jni.dylib", "libvideo_jni.dylib", "libeac3_jni.so", "libvideo_jni.so"
   );

   private NativeBundleDirectories() {
   }

   static void pruneObsoleteBundles(Path platformNativeDir, String currentFingerprint) throws IOException {
      Path normalizedRoot = platformNativeDir.toAbsolutePath().normalize();
      Path currentDir = normalizedRoot.resolve(currentFingerprint).normalize();
      if (currentDir.getParent().equals(normalizedRoot) && Files.isDirectory(currentDir)) {
         try (Stream<Path> children = Files.list(normalizedRoot)) {
            for (Path child : children.toList()) {
               Path normalizedChild = child.toAbsolutePath().normalize();
               if (!normalizedChild.equals(currentDir) && Files.isDirectory(normalizedChild, LinkOption.NOFOLLOW_LINKS)) {
                  deleteDirectoryTree(normalizedChild);
               }
            }
         }

         try (Stream<Path> children = Files.list(normalizedRoot)) {
            for (Path childx : children.toList()) {
               if (Files.isRegularFile(childx, LinkOption.NOFOLLOW_LINKS) && isLegacyNativeFile(childx.getFileName().toString())) {
                  Files.deleteIfExists(childx);
               }
            }
         }
      } else {
         throw new IOException("当前 native bundle 目录无效: " + currentDir);
      }
   }

   private static boolean isLegacyNativeFile(String fileName) {
      return LEGACY_NATIVE_FILE_NAMES.contains(fileName) || LEGACY_NATIVE_FILE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(fileName).matches());
   }

   private static void deleteDirectoryTree(Path directory) throws IOException {
      try (Stream<Path> paths = Files.walk(directory)) {
         for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
         }
      }
   }
}
