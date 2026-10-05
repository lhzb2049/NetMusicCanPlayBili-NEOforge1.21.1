package com.zhongbai233.net_music_can_play_bili.client.media;

import java.io.File;
import java.nio.file.Paths;

/**
 * 阶段 0 的离线验收探针：把一批"用户可能填进去的地址"喂给 {@link MediaSourceClassifier}，
 * 逐条打印 `id|kind|allowed|reason`，由 tools/verify_local_media.py 比对期望值。
 *
 * <p>刻意放在 tools/java 下（不参与 src/main/java 编译），因此不会进入产物 jar、也不影响字节级门禁。
 * 只依赖纯逻辑类，可离线直接跑。
 */
public final class VerifyLocalMediaProbe {
   private VerifyLocalMediaProbe() {
   }

   public static void main(String[] args) {
      String sandboxText = System.getProperty("ncpb.test.sandbox", "");
      if (sandboxText.isBlank()) {
         System.err.println("缺少 -Dncpb.test.sandbox=<沙盒目录>");
         System.exit(2);
         return;
      }

      String sandbox = Paths.get(sandboxText).toAbsolutePath().normalize().toString();
      String peer = System.getProperty("ncpb.test.peer", sandbox);
      String forbidden = System.getProperty("ncpb.test.forbidden", sandbox);
      String forbiddenName = Paths.get(forbidden).getFileName().toString();

      emit("http-remote", "https://example.com/a.mp4", sandbox);
      emit("http-loopback", "http://127.0.0.1:8080/x.mp4", sandbox);
      emit("local-abs-video", sandbox + "\\videos\\a.mp4", sandbox);
      emit("local-abs-image", sandbox + "\\images\\b.png", sandbox);
      emit("local-uri-path-toUri", Paths.get(sandbox, "videos", "a.mp4").toUri().toString(), sandbox);
      emit("local-uri-file-toURI", new File(sandbox, "videos/a.mp4").toURI().toString(), sandbox);
      emit("local-with-sync-params", sandbox + "\\videos\\a.mp4#nmb_session=1&nmb_elapsed_ms=5", sandbox);
      emit("local-nested-inside", sandbox + "\\videos\\sub\\deep\\c.mp4", sandbox);
      emit("inside-root-extra", sandbox + "\\outside\\x.mp4", sandbox);
      emit("traversal-inside", sandbox + "\\videos\\..\\outside\\x.mp4", sandbox);
      emit("traversal-escape", sandbox + "\\..\\" + forbiddenName + "\\x.mp4", sandbox);
      emit("forbidden-root", forbidden + "\\x.mp4", sandbox);
      emit("local-missing", sandbox + "\\videos\\missing.mp4", sandbox);
      emit("local-unsupported-ext", sandbox + "\\videos\\x.mkv", sandbox);
      emit("local-unc", "\\\\server\\share\\a.mp4", sandbox);
      emit("local-device-name", sandbox + "\\videos\\CON.mp4", sandbox);
      emit("keyword-search", "cc", sandbox);
      emit("relative-path", "videos/a.mp4", sandbox);
      emit("link-escape", sandbox + "\\videos\\link\\x.mp4", sandbox);
      emit("oversized", sandbox + "\\big\\large.mp4", sandbox);
      emit("peer-root", peer + "\\d.mp4", sandbox);

      MediaLogThrottle.clear();
      System.out.println("log-throttle-first|" + MediaLogThrottle.shouldLog("k", "v"));
      System.out.println("log-throttle-repeat|" + MediaLogThrottle.shouldLog("k", "v"));
      System.out.println("log-throttle-changed|" + MediaLogThrottle.shouldLog("k", "v2"));
      System.out.println("log-throttle-size|" + (MediaLogThrottle.size() == 1));
   }

   private static void emit(String id, String raw, String gameDirectory) {
      MediaSourceClassifier.Classification classification = MediaSourceClassifier.classify(raw, gameDirectory);
      System.out.println(
         id + "|" + classification.kind() + "|" + classification.allowed() + "|" + classification.reason()
      );
   }
}
