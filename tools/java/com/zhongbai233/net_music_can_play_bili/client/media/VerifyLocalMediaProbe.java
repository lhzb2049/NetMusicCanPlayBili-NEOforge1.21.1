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

      // --- 阶段 1：本地图片的纯逻辑缩放（不涉及 Minecraft 类）---
      int[] white = new int[8 * 8];
      java.util.Arrays.fill(white, 0xFFFFFFFF);
      int[] whiteFit = LocalImageScaler.fit(8, 8, 2048, 2048);
      int[] scaledWhite = LocalImageScaler.scale(white, 8, 8, whiteFit[0], whiteFit[1]);
      System.out.println("scaler-identity-dims|" + whiteFit[0] + "x" + whiteFit[1]);
      System.out.println("scaler-identity-same-array|" + (scaledWhite == white));

      int[] aspectFit = LocalImageScaler.fit(4000, 3000, 2048, 2048);
      System.out.println("scaler-aspect-dims|" + aspectFit[0] + "x" + aspectFit[1]);

      int[] twoByTwo = new int[]{0xFF000000, 0xFF646464, 0xFFC8C8C8, 0xFFFFFFFF};
      System.out.println("scaler-box-average|" + Integer.toHexString(LocalImageScaler.scale(twoByTwo, 2, 2, 1, 1)[0]));

      int[] downFit = LocalImageScaler.fit(8, 8, 4, 4);
      int[] downWhite = LocalImageScaler.scale(white, 8, 8, downFit[0], downFit[1]);
      System.out.println("scaler-downscale-dims|" + downFit[0] + "x" + downFit[1]);
      System.out.println("scaler-downscale-length|" + downWhite.length);
      System.out.println("scaler-downscale-pixel|" + Integer.toHexString(downWhite[0]));

      int[] zeroFit = LocalImageScaler.fit(0, 0, 2048, 2048);
      System.out.println("scaler-zero-guard|" + zeroFit[0] + "x" + zeroFit[1]);
      int[] upFit = LocalImageScaler.fit(4, 4, 8, 8);
      System.out.println("scaler-no-upscale|" + upFit[0] + "x" + upFit[1]);

      // --- 阶段 1：图片文件头解析（解码前的保护性检查，纯逻辑）---
      System.out.println("header-png-dims|" + dimsOf(pngHeader(1920, 1080)));
      System.out.println("header-jpeg-dims|" + dimsOf(jpegHeader(640, 480)));
      System.out.println("header-unknown|" + dimsOf(new byte[]{
         (byte) 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
      }));
      System.out.println("header-exceeds-yes|" + LocalImageHeaderProbe.exceeds(20000, 20000, 64000000L));
      System.out.println("header-exceeds-no|" + LocalImageHeaderProbe.exceeds(4000, 3000, 64000000L));

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

   private static String dimsOf(byte[] head) {
      int[] dims = LocalImageHeaderProbe.dimensions(head);
      return dims == null ? "null" : dims[0] + "x" + dims[1];
   }

   private static byte[] pngHeader(int width, int height) {
      byte[] head = new byte[24];
      byte[] signature = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
      System.arraycopy(signature, 0, head, 0, signature.length);
      head[11] = 13;
      head[12] = 'I';
      head[13] = 'H';
      head[14] = 'D';
      head[15] = 'R';
      putInt(head, 16, width);
      putInt(head, 20, height);
      return head;
   }

   private static byte[] jpegHeader(int width, int height) {
      byte[] head = new byte[20];
      head[0] = (byte) 0xFF;
      head[1] = (byte) 0xD8;
      head[2] = (byte) 0xFF;
      head[3] = (byte) 0xC0;
      head[5] = 17;
      head[6] = 8;
      head[7] = (byte) (height >> 8);
      head[8] = (byte) height;
      head[9] = (byte) (width >> 8);
      head[10] = (byte) width;
      return head;
   }

   private static void putInt(byte[] data, int offset, int value) {
      data[offset] = (byte) (value >>> 24);
      data[offset + 1] = (byte) (value >>> 16);
      data[offset + 2] = (byte) (value >>> 8);
      data[offset + 3] = (byte) value;
   }
}
