package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

public class Eac3NativeDecoder implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Pattern WINDOWS_FFMPEG_LIB = Pattern.compile("^(avutil|swscale|avcodec)-(\\d+)\\.dll$");
   private static final Pattern MACOS_FFMPEG_LIB = Pattern.compile("^lib(avutil|swscale|avcodec)\\.(\\d+)\\.dylib$");
   private static final Pattern LINUX_FFMPEG_LIB = Pattern.compile("^lib(avutil|swscale|avcodec)\\.so\\.(\\d+)$");
   private static volatile boolean loaderInitialized;
   private static volatile boolean nativeAvailable;
   private static volatile String ffmpegVersion;
   private long handle;
   private boolean open;
   private int openFailures;
   private int decodeFailures;
   private long totalFrames;
   private int lastNbSamples;
   private int lastNbChannels;

   public Eac3NativeDecoder() {
      ensureLoaderReady();
   }

   public float[][] decodeFrame(byte[] ec3Frame) {
      if (!nativeAvailable) {
         return null;
      } else if (!this.ensureOpen()) {
         return null;
      } else {
         float[][] planar = Eac3Jni.decode(this.handle, ec3Frame, 0, ec3Frame.length);
         if (planar == null) {
            if (this.decodeFailures++ < 3) {
               LOGGER.warn("Eac3Native decode 失败 (连续 {} 次)", this.decodeFailures);
            }

            return null;
         } else {
            this.decodeFailures = 0;
            this.totalFrames++;
            int channels = planar.length;
            int samples = channels > 0 ? planar[0].length : 0;
            float[][] pcm = new float[samples][channels];

            for (int ch = 0; ch < channels; ch++) {
               float[] src = planar[ch];
               if (src != null) {
                  int n = Math.min(samples, src.length);

                  for (int i = 0; i < n; i++) {
                     pcm[i][ch] = src[i];
                  }
               }
            }

            if (samples != this.lastNbSamples || channels != this.lastNbChannels) {
               LOGGER.debug("Eac3Native 解码: {}samples × {}ch (FFmpeg {})", new Object[]{samples, channels, ffmpegVersion});
               this.lastNbSamples = samples;
               this.lastNbChannels = channels;
            }

            return pcm;
         }
      }
   }

   public void flush() {
      if (this.open && this.handle != 0L) {
         Eac3Jni.flush(this.handle);
      }
   }

   @Override
   public void close() {
      this.open = false;
      if (this.handle != 0L) {
         Eac3Jni.close(this.handle);
         this.handle = 0L;
      }

      LOGGER.debug("Eac3Native 已关闭 (解码 {} 帧)", this.totalFrames);
   }

   public long totalFrames() {
      return this.totalFrames;
   }

   public static synchronized void preload() {
      ensureLoaderReady();
   }

   public static boolean isNativeAvailable() {
      ensureLoaderReady();
      return nativeAvailable;
   }

   private static synchronized void ensureLoaderReady() {
      if (!loaderInitialized) {
         try {
            loadEmbeddedNatives();
            ffmpegVersion = Eac3Jni.version();
            nativeAvailable = true;
            LOGGER.info("FFmpeg media native 解码器加载成功: {}", ffmpegVersion);
         } catch (Throwable var4) {
            nativeAvailable = false;
            LOGGER.error("FFmpeg media native 加载失败，Dolby/视频原生解码将不可用，自动降级。", var4);
         } finally {
            loaderInitialized = true;
         }
      }
   }

   private static void loadEmbeddedNatives() throws IOException {
      String os = System.getProperty("os.name").toLowerCase();
      String arch = System.getProperty("os.arch").toLowerCase();
      boolean isArm = arch.contains("aarch64") || arch.contains("arm");
      String platformDir;
      boolean isWindows;
      if (os.contains("win")) {
         platformDir = isArm ? "windows-arm64" : "windows-x86_64";
         isWindows = true;
      } else if (!os.contains("mac") && !os.contains("darwin")) {
         platformDir = isArm ? "linux-arm64" : "linux-x86_64";
         isWindows = false;
      } else {
         platformDir = isArm ? "macos-arm64" : "macos-x86_64";
         isWindows = false;
      }

      Eac3NativeDecoder.NativeLibrarySet libraries = discoverNativeLibraries(platformDir, os, isWindows);
      String bundleFingerprint = nativeBundleFingerprint();
      String[] runtimeLibs = isWindows ? new String[]{"libwinpthread-1"} : new String[0];
      boolean[] runtimeLibPresent = new boolean[runtimeLibs.length];
      Path platformNativeDir = gameConfigDir().resolve("net_music_can_play_bili").resolve("natives").resolve(platformDir);
      Path nativeDir = platformNativeDir.resolve(bundleFingerprint);
      Files.createDirectories(nativeDir);

      for (int i = 0; i < runtimeLibs.length; i++) {
         String fileName = nativeFileName(runtimeLibs[i], os, isWindows);
         runtimeLibPresent[i] = extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), false);
      }

      for (String fileName : libraries.ffmpegLibraries()) {
         extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
      }

      for (String fileName : libraries.jniLibraries()) {
         extractEmbeddedNative(platformDir, fileName, nativeDir.resolve(fileName), true);
      }

      try {
         NativeBundleDirectories.pruneObsoleteBundles(platformNativeDir, bundleFingerprint);
      } catch (IOException var13) {
         LOGGER.warn("清理旧 FFmpeg native bundle 失败: path={}", platformNativeDir, var13);
      }

      for (int i = 0; i < runtimeLibs.length; i++) {
         if (runtimeLibPresent[i]) {
            String fn = nativeFileName(runtimeLibs[i], os, isWindows);
            LOGGER.debug("加载 native 运行时依赖: {}", nativeDir.resolve(fn));
            System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
         }
      }

      for (String fn : libraries.ffmpegLibraries()) {
         LOGGER.debug("加载 FFmpeg native 库: {}", nativeDir.resolve(fn));
         System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
      }

      for (String fn : libraries.jniLibraries()) {
         LOGGER.debug("加载 JNI native 库: {}", nativeDir.resolve(fn));
         System.load(nativeDir.resolve(fn).toAbsolutePath().toString());
      }

      LOGGER.info("FFmpeg native 库提取并加载: os={}, arch={}, bundle={}, path={}", new Object[]{os, arch, bundleFingerprint, nativeDir});
   }

   private static boolean extractEmbeddedNative(String platformDir, String fileName, Path target, boolean required) throws IOException {
      String resPath = "/native/" + platformDir + "/" + fileName;

      boolean var16;
      try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
         InputStream source = in != null ? in : openFilesystemNativeResource(platformDir, fileName);
         if (source == null) {
            if (required) {
               throw new IOException("内嵌 native 库缺失: " + resPath);
            }

            return false;
         }

         InputStream var8 = source;

         byte[] bundled;
         try {
            bundled = source.readAllBytes();
         } catch (Throwable var13) {
            if (source != null) {
               try {
                  var8.close();
               } catch (Throwable var12) {
                  var13.addSuppressed(var12);
               }
            }

            throw var13;
         }

         if (source != null) {
            source.close();
         }

         if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bundled)) {
            writeNativeAtomically(target, bundled);
         }

         var16 = true;
      }

      return var16;
   }

   private static void writeNativeAtomically(Path target, byte[] bundled) throws IOException {
      Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
      boolean moved = false;

      try {
         Files.write(temp, bundled);
         temp.toFile().setReadable(true, false);
         temp.toFile().setExecutable(true, false);

         try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException var8) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
         }

         moved = true;
      } finally {
         if (!moved) {
            Files.deleteIfExists(temp);
         }
      }
   }

   private static String nativeBundleFingerprint() throws IOException {
      String resPath = "/native/README.md";

      String var5;
      try (InputStream in = Eac3NativeDecoder.class.getResourceAsStream(resPath)) {
         InputStream source = in != null ? in : openFilesystemNativeResource("README.md");
         if (source == null) {
            throw new IOException("内嵌 native 指纹文件缺失: " + resPath);
         }

         try {
            InputStream impossible = source;

            try {
               byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.readAllBytes());
               var5 = HexFormat.of().formatHex(digest, 0, 8);
            } catch (Throwable var8) {
               if (source != null) {
                  try {
                     impossible.close();
                  } catch (Throwable var7) {
                     var8.addSuppressed(var7);
                  }
               }

               throw var8;
            }

            if (source != null) {
               source.close();
            }
         } catch (NoSuchAlgorithmException var9) {
            throw new IllegalStateException("JVM 缺少 SHA-256", var9);
         }
      }

      return var5;
   }

   private static InputStream openFilesystemNativeResource(String platformDir, String fileName) throws IOException {
      return openFilesystemNativeResource(platformDir + "/" + fileName);
   }

   private static InputStream openFilesystemNativeResource(String relativeResourcePath) throws IOException {
      CodeSource codeSource = Eac3NativeDecoder.class.getProtectionDomain().getCodeSource();
      if (codeSource != null && codeSource.getLocation() != null) {
         try {
            Path workspace = findWorkspaceRoot(Path.of(codeSource.getLocation().toURI()));
            if (workspace == null) {
               return null;
            } else {
               Path relative = Path.of("native").resolve(relativeResourcePath.replace('/', File.separatorChar));

               for (Path root : List.of(
                  workspace.resolve("build").resolve("resources").resolve("main"), workspace.resolve("src").resolve("main").resolve("resources")
               )) {
                  Path candidate = root.resolve(relative);
                  if (Files.isRegularFile(candidate)) {
                     return Files.newInputStream(candidate);
                  }
               }

               return null;
            }
         } catch (URISyntaxException var7) {
            throw new IOException("native 资源 code source URI 无效", var7);
         }
      } else {
         return null;
      }
   }

   private static Eac3NativeDecoder.NativeLibrarySet discoverNativeLibraries(String platformDir, String os, boolean isWindows) throws IOException {
      Set<String> resourceNames = listNativeResourceFileNames(platformDir);
      boolean isMac = os.contains("mac") || os.contains("darwin");
      Pattern ffmpegPattern = isWindows ? WINDOWS_FFMPEG_LIB : (isMac ? MACOS_FFMPEG_LIB : LINUX_FFMPEG_LIB);
      List<String> ffmpeg = new ArrayList<>();

      for (String base : List.of("avutil", "swscale", "avcodec")) {
         ffmpeg.add(selectVersionedLibrary(resourceNames, ffmpegPattern, base, platformDir));
      }

      List<String> jni = List.of(nativeFileName("eac3_jni", os, isWindows), nativeFileName("video_jni", os, isWindows));

      for (String fileName : jni) {
         if (!resourceNames.contains(fileName)) {
            throw new IOException("内嵌 JNI native 库缺失: /native/" + platformDir + "/" + fileName);
         }
      }

      return new Eac3NativeDecoder.NativeLibrarySet(List.copyOf(ffmpeg), jni);
   }

   private static String selectVersionedLibrary(Set<String> resourceNames, Pattern pattern, String base, String platformDir) throws IOException {
      return resourceNames.stream()
         .map(pattern::matcher)
         .filter(matcher -> matcher.matches())
         .filter(matcher -> matcher.group(1).equals(base))
         .max(Comparator.comparingInt(matcher -> Integer.parseInt(matcher.group(2))))
         .map(matcher -> matcher.group())
         .orElseThrow(() -> new IOException("内嵌 FFmpeg native 库缺失: /native/" + platformDir + "/" + base + " (versioned)"));
   }

   private static Set<String> listNativeResourceFileNames(String platformDir) throws IOException {
      String resourceDir = "native/" + platformDir;
      ClassLoader loader = Eac3NativeDecoder.class.getClassLoader();
      Set<String> names = new HashSet<>();
      Enumeration<URL> urls = loader.getResources(resourceDir);

      while (urls.hasMoreElements()) {
         URL url = urls.nextElement();
         String protocol = url.getProtocol();
         if ("file".equals(protocol)) {
            listFileResourceNames(url, names);
         } else if ("jar".equals(protocol)) {
            listJarResourceNames(url, resourceDir, names);
         }
      }

      listManifestResourceNames(platformDir, names);
      if (names.isEmpty()) {
         listCodeSourceResourceNames(resourceDir, names);
      }

      return names;
   }

   private static void listManifestResourceNames(String platformDir, Set<String> names) throws IOException {
      String prefix = platformDir + "/";

      try (InputStream embedded = Eac3NativeDecoder.class.getResourceAsStream("/native/SHA256SUMS")) {
         InputStream source = embedded != null ? embedded : openFilesystemNativeResource("SHA256SUMS");
         if (source == null) {
            return;
         }

         InputStream var5 = source;

         try {
            String manifest = new String(source.readAllBytes(), StandardCharsets.UTF_8);

            for (String rawLine : manifest.split("\\R")) {
               String line = rawLine.trim();
               if (!line.isEmpty() && !line.startsWith("#")) {
                  String[] fields = line.split("\\s+", 2);
                  if (fields.length == 2 && fields[1].startsWith(prefix)) {
                     String fileName = fields[1].substring(prefix.length());
                     if (!fileName.isEmpty() && !fileName.contains("/") && !fileName.contains("\\")) {
                        names.add(fileName);
                     }
                  }
               }
            }
         } catch (Throwable var16) {
            if (source != null) {
               try {
                  var5.close();
               } catch (Throwable var15) {
                  var16.addSuppressed(var15);
               }
            }

            throw var16;
         }

         if (source != null) {
            source.close();
         }
      }
   }

   private static void listFileResourceNames(URL url, Set<String> names) throws IOException {
      try {
         Path dir = Path.of(url.toURI());
         if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
               stream.filter(x$0 -> Files.isRegularFile(x$0)).map(path -> path.getFileName().toString()).forEach(names::add);
            }
         }
      } catch (URISyntaxException var8) {
         throw new IOException("native 资源目录 URI 无效: " + url, var8);
      }
   }

   private static void listJarResourceNames(URL url, String resourceDir, Set<String> names) throws IOException {
      JarURLConnection connection = (JarURLConnection)url.openConnection();
      String prefix = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";

      try (JarFile jar = connection.getJarFile()) {
         listJarEntries(jar, prefix, names);
      }
   }

   private static void listCodeSourceResourceNames(String resourceDir, Set<String> names) throws IOException {
      CodeSource codeSource = Eac3NativeDecoder.class.getProtectionDomain().getCodeSource();
      if (codeSource != null && codeSource.getLocation() != null) {
         try {
            Path location = Path.of(codeSource.getLocation().toURI());
            if (Files.isDirectory(location)) {
               Path dir = location.resolve(resourceDir.replace('/', File.separatorChar));
               listNativeResourceDirectory(dir, names);
               Path workspace = findWorkspaceRoot(location);
               if (workspace != null) {
                  listNativeResourceDirectory(
                     workspace.resolve("build").resolve("resources").resolve("main").resolve(resourceDir.replace('/', File.separatorChar)), names
                  );
                  listNativeResourceDirectory(
                     workspace.resolve("src").resolve("main").resolve("resources").resolve(resourceDir.replace('/', File.separatorChar)), names
                  );
               }
            } else if (Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar")) {
               String prefix = resourceDir.endsWith("/") ? resourceDir : resourceDir + "/";

               try (JarFile jar = new JarFile(location.toFile())) {
                  listJarEntries(jar, prefix, names);
               }
            }
         } catch (URISyntaxException var10) {
            throw new IOException("native 资源 code source URI 无效", var10);
         }
      }
   }

   private static Path findWorkspaceRoot(Path location) {
      for (Path current = location.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
         if (Files.isRegularFile(current.resolve("build.gradle")) || Files.isRegularFile(current.resolve("settings.gradle"))) {
            return current;
         }
      }

      return null;
   }

   private static void listNativeResourceDirectory(Path dir, Set<String> names) throws IOException {
      if (Files.isDirectory(dir)) {
         try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(x$0 -> Files.isRegularFile(x$0)).map(path -> path.getFileName().toString()).forEach(names::add);
         }
      }
   }

   private static void listJarEntries(JarFile jar, String prefix, Set<String> names) {
      Enumeration<JarEntry> entries = jar.entries();

      while (entries.hasMoreElements()) {
         JarEntry entry = entries.nextElement();
         if (!entry.isDirectory()) {
            String name = entry.getName();
            if (name.startsWith(prefix)) {
               String fileName = name.substring(prefix.length());
               if (!fileName.isEmpty() && !fileName.contains("/")) {
                  names.add(fileName);
               }
            }
         }
      }
   }

   private static String nativeFileName(String base, String os, boolean isWindows) {
      if (isWindows) {
         return base + ".dll";
      } else {
         boolean isMac = os.contains("mac") || os.contains("darwin");
         return "lib" + base + (isMac ? ".dylib" : ".so");
      }
   }

   private boolean ensureOpen() {
      if (this.open && this.handle != 0L) {
         return true;
      } else if (this.openFailures > 2) {
         return false;
      } else {
         try {
            this.handle = Eac3Jni.decoderOpen();
            if (this.handle == 0L) {
               this.openFailures++;
               LOGGER.error("Eac3Native: 打开解码器失败");
               return false;
            } else {
               this.open = true;
               this.openFailures = 0;
               LOGGER.debug("Eac3Native 解码器就绪");
               return true;
            }
         } catch (Exception var2) {
            this.openFailures++;
            LOGGER.error("Eac3Native 初始化异常", var2);
            return false;
         }
      }
   }

   private static Path gameConfigDir() {
      try {
         return FMLPaths.CONFIGDIR.get();
      } catch (Throwable var1) {
         return Path.of("config").toAbsolutePath();
      }
   }

   private record NativeLibrarySet(List<String> ffmpegLibraries, List<String> jniLibraries) {
   }
}
