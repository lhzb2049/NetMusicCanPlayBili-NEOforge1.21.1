package com.zhongbai233.net_music_can_play_bili.client.media;

import com.zhongbai233.net_music_can_play_bili.client.renderer.item.VerifyHandheldRgbaProbe;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.codec.VerifyVideoConfigProbe;
import com.zhongbai233.net_music_can_play_bili.media.local.Mp4BoxReader;
import com.zhongbai233.net_music_can_play_bili.media.pipeline.AudioPipelineFactory;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.stream.ChunkPrefetchInputStream;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4RangeSeekSupport;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeClient;
import com.zhongbai233.net_music_can_play_bili.media.stream.LocalMediaHttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor.ABGR32;

/**
 * 阶段 2（本地视频）的离线验收探针：所有检查都走**产品自己的**解析器/客户端，
 * 只把结果打成 {@code key|value} 行交给 tools/verify_local_video.py 比对。
 *
 * <p>三个子命令：
 * <ul>
 *   <li>{@code prepare <源mp4> <游戏目录> <产物目录>}：走 {@link LocalVideoSources#resolveFor} 全链路
 *       （分类 → 白名单 → 重封装 → 回环发布 → 登记 segment base），打印 resolved/failed；
 *   <li>{@code route <rawUrl> <playUrl> <游戏目录> <产物目录>}：音频侧路由结果；
 *   <li>{@code consumers <视频fmp4> <音频fmp4>}：把产物喂给产品的 init 提取器、moov 解析器、
 *       分片样本表解析器、流解析器、sidx 解析器、decoder config 提取器、音频管线工厂；
 *   <li>{@code http <file> <contentType>}：回环服务的 Range 契约（含 206 尾段裁剪、416、未知 token 404）
 *       以及产品预取客户端 {@link ChunkPrefetchInputStream} 的整文件读取校验。
 * </ul>
 *
 * <p>刻意放在 tools/java 下（不参与 src/main/java 编译），因此不进入产物 jar、不影响字节级门禁。
 */
public final class VerifyLocalVideoProbe {
   private VerifyLocalVideoProbe() {
   }

   public static void main(String[] args) throws Exception {
      if (args.length == 0) {
         System.err.println("用法: prepare|route|consumers|http ...");
         System.exit(2);
         return;
      }

      String gameDir = System.getProperty("ncpb.test.gamedir", "");
      if (!gameDir.isBlank()) {
         prepareFmlPaths(gameDir);
      }

      switch (args[0]) {
         case "prepare" -> prepare(args);
         case "route" -> route(args);
         case "consumers" -> consumers(args);
         case "http" -> http(args);
         case "expiry" -> expiry(args);
         case "admission" -> admission(args);
         case "imagergb" -> imagergb(args);
         case "rgbaorder" -> rgbaorder(args);
         case "snapshot" -> snapshot(args);
         default -> {
            System.err.println("未知子命令: " + args[0]);
            System.exit(2);
         }
      }
   }

   /**
    * 阶段 3：四个消费端共用的「这个源算不算有画面」矩阵。
    *
    * <p>参数：{@code admission <游戏目录> <本地视频> <本地图片> <白名单外视频> <不支持的扩展名> <缺失文件>}，
    * 每行打印 {@code admission|<id>|remoteVideo|videoExpected|localKind|staticImage}。
    */
   private static void admission(String[] args) {
      String gameDir = args[1];
      String video = args[2];
      String image = args[3];
      String blocked = args[4];
      String otherExt = args[5];
      String missing = args[6];
      emitAdmission("bili-only", true, "BV1xx411c7mD", gameDir);
      emitAdmission("remote-http", false, "https://example.com/a.mp4", gameDir);
      emitAdmission("local-video", false, video, gameDir);
      emitAdmission("local-image", false, image, gameDir);
      emitAdmission("local-video-with-sync", false, video + "#nmb_session=1&nmb_elapsed_ms=5", gameDir);
      emitAdmission("outside-root", false, blocked, gameDir);
      emitAdmission("unsupported-ext", false, otherExt, gameDir);
      emitAdmission("missing-file", false, missing, gameDir);
      emitAdmission("relative-path", false, "videos/a.mp4", gameDir);
      emitAdmission("keyword", false, "cc", gameDir);
      emitAdmission("blank", false, "", gameDir);
      System.out.println("admission|videoSwitch|" + LocalVideoProperties.enabled());
   }

   private static void emitAdmission(String id, boolean remoteVideo, String rawUrl, String gameDir) {
      LocalMediaAdmission.LocalKind kind = LocalMediaAdmission.localKind(rawUrl, gameDir);
      boolean videoExpected = LocalMediaAdmission.videoExpected(remoteVideo, rawUrl, gameDir);
      boolean staticImage = LocalMediaAdmission.staticImage(rawUrl, gameDir);
      System.out.println("admission|" + id + "|" + remoteVideo + "|" + videoExpected + "|" + kind + "|" + staticImage);
   }

   /**
    * 阶段 3：本地图片 → RGBA 字节（设备屏用的那条路）。参数：
    * {@code imagergb <png> <jpg或-> <最大宽> <最大高> <最大像素> <过大图> <非图片> <缺失文件>}
    */
   private static void imagergb(String[] args) throws Exception {
      String png = args[1];
      String jpg = args.length > 2 && !"-".equals(args[2]) ? args[2] : null;
      int maxWidth = Integer.parseInt(args[3]);
      int maxHeight = Integer.parseInt(args[4]);
      long maxPixels = Long.parseLong(args[5]);
      String huge = args.length > 6 && !"-".equals(args[6]) ? args[6] : null;
      String notImage = args.length > 7 && !"-".equals(args[7]) ? args[7] : null;
      String missing = args.length > 8 && !"-".equals(args[8]) ? args[8] : null;
      emitPixels("png", png, maxWidth, maxHeight, maxPixels);
      emitPixels("png-scaled", png, 2, 2, maxPixels);
      if (jpg != null) {
         emitPixels("jpg", jpg, maxWidth, maxHeight, maxPixels);
      }

      if (huge != null) {
         emitFailure("pixel-cap", huge, maxWidth, maxHeight, 16L);
      }

      if (notImage != null) {
         emitFailure("not-image", notImage, maxWidth, maxHeight, maxPixels);
      }

      if (missing != null) {
         emitFailure("missing", missing, maxWidth, maxHeight, maxPixels);
      }
   }

   private static void emitPixels(String id, String path, int maxWidth, int maxHeight, long maxPixels) {
      try {
         LocalImageRgba.Pixels pixels = LocalImageRgba.load(Paths.get(path), maxWidth, maxHeight, maxPixels);
         StringBuilder head = new StringBuilder();

         for (int i = 0; i < Math.min(8, pixels.rgba().length); i++) {
            head.append(String.format("%02x", pixels.rgba()[i] & 0xFF));
         }

         System.out.println(
            "imagergb|" + id + "|ok|" + pixels.width() + "x" + pixels.height() + "|src=" + pixels.sourceWidth() + "x"
               + pixels.sourceHeight() + "|scaled=" + pixels.scaled() + "|bytes=" + pixels.byteLength() + "|head=" + head
               + "|sha=" + sha256(pixels.rgba()).substring(0, 16)
         );
      } catch (Exception error) {
         System.out.println("imagergb|" + id + "|fail|" + error.getClass().getSimpleName() + "|" + error.getMessage());
      }
   }

   private static void emitFailure(String id, String path, int maxWidth, int maxHeight, long maxPixels) {
      try {
         LocalImageRgba.load(Paths.get(path), maxWidth, maxHeight, maxPixels);
         System.out.println("imagergb|" + id + "|ok");
      } catch (Exception error) {
         System.out.println("imagergb|" + id + "|fail|" + error.getClass().getSimpleName() + "|" + error.getMessage());
      }
   }

   /**
    * 通道顺序（红到底在哪个字节）：用 MC 自己的 {@code FastColor.ABGR32} 取色器来钉，
    * 不靠「参数名应该是什么」的推断。
    *
    * <p>三行证据：
    * <ul>
    *   <li>{@code abgr32}：{@code ABGR32.color(0x11,0x22,0x33,0x44)} 拆出来的 red/green/blue/alpha；
    *   <li>{@code handheld}：Pad/MP4 物品上传路径的打包函数（探针同包取用）拆出来的四个通道；
    *   <li>{@code legacy}：老的写法 {@code ABGR32.color(a, r, g, b)} —— 应当把**蓝**塞进红槽（这就是画面发蓝的原因）；
    *   <li>{@code image}：本地图片第一个像素的 R,G,B,A 字节（应当与 handheld 的口径一致）。
    * </ul>
    */
   private static void rgbaorder(String[] args) throws Exception {
      int packed = ABGR32.color(0x11, 0x22, 0x33, 0x44);
      System.out.println("rgbaorder|abgr32|" + hex32(packed) + "|" + channels(packed));

      int r = 0x44;
      int g = 0x33;
      int b = 0x22;
      int a = 0x11;
      int handheld = VerifyHandheldRgbaProbe.pack(r, g, b, a);
      System.out.println("rgbaorder|handheld|" + hex32(handheld) + "|" + channels(handheld));

      int legacy = ABGR32.color(a, r, g, b);
      System.out.println("rgbaorder|legacy|" + hex32(legacy) + "|" + channels(legacy));

      if (args.length > 1) {
         LocalImageRgba.Pixels pixels = LocalImageRgba.load(Paths.get(args[1]), 64, 64, 1_000_000L);
         int[] first = new int[4];

         for (int i = 0; i < 4; i++) {
            first[i] = pixels.rgba()[i] & 0xFF;
         }

         System.out.println("rgbaorder|image|" + hex32(handheld(first[0], first[1], first[2], first[3]))
            + "|r=" + first[0] + ",g=" + first[1] + ",b=" + first[2] + ",a=" + first[3]);
      }
   }

   private static int handheld(int r, int g, int b, int a) {
      return VerifyHandheldRgbaProbe.pack(r, g, b, a);
   }

   private static String channels(int pixel) {
      return "r=" + ABGR32.red(pixel) + ",g=" + ABGR32.green(pixel) + ",b=" + ABGR32.blue(pixel) + ",a=" + ABGR32.alpha(pixel);
   }

   private static String hex32(int value) {
      return String.format("%08x", value);
   }

   /**
    * 本地图片静态帧的标志位（决定渲染类型）：{@code emissiveRgba} 必须是 false ——
    * true 会走 entityTranslucentEmissive（半透明、不写深度），天空的云就会穿到图片前面（实测过）。
    * 这里直接调用产品里那个包私有工厂，把标志位钉住。
    */
   private static void snapshot(String[] args) {
      ResourceLocation id = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "verify/local_image_probe");
      VideoBillboardState.ProjectorFrameSnapshot frame = ClientLocalImageProjection.imageSnapshot(id, 640, 360);
      System.out.println(
         "snapshot|hasFrame=" + frame.hasFrame() + "|yuv=" + frame.yuv() + "|emissiveRgba=" + frame.emissiveRgba()
            + "|loadingProgressOverlay=" + frame.loadingProgressOverlay() + "|rgbaDepthOffset=" + frame.rgbaDepthOffset()
            + "|size=" + frame.width() + "x" + frame.height() + "|texture=" + (frame.rgbaTexture() != null)
            + "|format=" + frame.format()
      );
   }

   /**
    * 产品的 B 站请求头/配置链路（{@code BiliConfig} → {@code FMLPaths.CONFIGDIR.get()}）在游戏里由 FML 初始化；
    * 离线跑探针时必须自己把这两个目录指到沙盒，否则 {@code HttpRangeClient} 一进日志头就会 NPE。
    * 用反射调用，是为了「拿不到就不致命」：缺了它只有 HTTP 相关检查会退化，纯逻辑检查照跑。
    */
   private static void prepareFmlPaths(String gameDir) {
      try {
         Class<?> type = Class.forName("net.neoforged.fml.loading.FMLPaths");
         type.getMethod("loadAbsolutePaths", Path.class).invoke(null, Paths.get(gameDir));
         System.out.println("fmlpaths|ok");
      } catch (Throwable error) {
         System.out.println("fmlpaths|unavailable|" + error.getClass().getSimpleName());
      }
   }

   private static void prepare(String[] args) throws Exception {
      String source = args[1];
      String gameDir = args[2];
      Path workDir = Paths.get(args[3]);
      LocalVideoSources.Resolution resolution = LocalVideoSources.resolveFor(source, gameDir, workDir, 30_000L);
      LocalVideoSources.Prepared prepared = resolution.prepared();
      if (prepared == null) {
         System.out.println("prepare|failed|" + resolution.reason());
         return;
      }

      System.out.println("prepare|ok|" + prepared.videoFile().getFileName() + "|" + (prepared.audioFile() != null ? prepared.audioFile().getFileName() : "-"));
      System.out.println("prepared|durationMillis|" + prepared.durationMillis());
      System.out.println("prepared|width|" + prepared.width());
      System.out.println("prepared|height|" + prepared.height());
      System.out.println("prepared|fps|" + prepared.fps());
      System.out.println("prepared|codecId|" + prepared.codecId());
      System.out.println("prepared|audioCodec|" + prepared.audioCodec());
      System.out.println("prepared|hasAudio|" + prepared.hasAudio());
      System.out.println("prepared|videoUrlHost|" + hostOf(prepared.videoUrl()));
      System.out.println("prepared|audioUrlHost|" + (prepared.hasAudio() ? hostOf(prepared.audioUrl()) : "-"));
      System.out.println("prepared|videoLayout|" + prepared.videoInitEnd() + "/" + prepared.videoIndexStart() + "/" + prepared.videoIndexEnd());
      System.out.println("prepared|audioLayout|" + prepared.audioInitEnd() + "/" + prepared.audioIndexStart() + "/" + prepared.audioIndexEnd());
      // 第二次调用必须命中缓存（同一个 key，不再重封装）
      LocalVideoSources.Resolution again = LocalVideoSources.resolveFor(source, gameDir, workDir, 30_000L);
      System.out.println("prepare|cached|" + (again.prepared() != null && again.prepared().videoUrl().equals(prepared.videoUrl())));
      System.out.println("prepare|fileCount|" + countFiles(workDir));
   }

   private static void route(String[] args) throws Exception {
      LocalVideoSources.AudioRouting routing = LocalVideoSources.routeAudioFor(args[1], args[2], args[3], Paths.get(args[4]));
      System.out.println("route|handled|" + routing.handled());
      System.out.println("route|audioAvailable|" + routing.audioAvailable());
      System.out.println("route|urlHost|" + (routing.url() != null ? hostOf(routing.url()) : "-"));
   }

   private static void consumers(String[] args) throws Exception {
      Path videoFile = Paths.get(args[1]);
      Path audioFile = args.length > 2 && !"-".equals(args[2]) ? Paths.get(args[2]) : null;
      byte[] video = Files.readAllBytes(videoFile);
      System.out.println("file|videoSha256|" + sha256(video));
      System.out.println("file|videoBytes|" + video.length);
      initChecks(video, "video");
      sampleTableChecks(video, "video");
      streamWalk(video, "video");
      sidxChecks(video, "video");
      if (audioFile != null) {
         byte[] audio = Files.readAllBytes(audioFile);
         System.out.println("file|audioSha256|" + sha256(audio));
         System.out.println("file|audioBytes|" + audio.length);
         initChecks(audio, "audio");
         audioPipelineCheck(audio);
         sidxChecks(audio, "audio");
      }

      // 负例：把 moov 的类型改坏，init 提取必须失败（证明这一项检查真的会说不）
      if (video.length > 400) {
         byte[] corrupted = video.clone();

         for (int i = 0; i + 4 <= corrupted.length; i++) {
            if (corrupted[i] == 'm' && corrupted[i + 1] == 'o' && corrupted[i + 2] == 'o' && corrupted[i + 3] == 'v') {
               corrupted[i + 3] = 'x';
               break;
            }
         }

         Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(corrupted, corrupted.length, (payload, moov) -> moov.timescale);
         System.out.println("init|corrupt|" + (init == null ? "fail" : "ok"));
      }
   }

   private static void initChecks(byte[] data, String label) {
      Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(data, data.length, (payload, moov) -> {
         int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(payload);
         return videoTimescale > 0 ? videoTimescale : moov.timescale;
      });
      System.out.println("init|" + label + "|" + (init == null ? "fail" : "ok") + "|" + (init != null ? init.timescale() : -1) + "|" + (init != null ? init.bytes().length : -1));
      if (init == null) {
         return;
      }

      byte[] moovPayload = moovPayloadOf(init.bytes());
      if (moovPayload == null) {
         System.out.println("moov|" + label + "|missing");
         return;
      }

      Fmp4ToMp4Converter.ParseResult parsed = Fmp4ToMp4Converter.parseMoov(moovPayload);
      System.out.println("moov|" + label + "|timescale=" + parsed.timescale + "|asc=" + (parsed.asc != null ? parsed.asc.length : -1)
         + "|videoTimescale=" + Fmp4ToMp4Converter.parseVideoTimescale(moovPayload)
         + "|codecs=" + Fmp4ToMp4Converter.listAudioCodecs(moovPayload));
      System.out.println("decoderconfig|" + label + "|" + VerifyVideoConfigProbe.describe(moovPayload, label.equals("video") ? 7 : 13));
   }

   /** 从 init 段（ftyp + moov）里取出 moov 的负载，交给产品的 moov 解析器。 */
   private static byte[] moovPayloadOf(byte[] data) {
      for (Mp4BoxReader.Box box : Mp4BoxReader.children(data, 0, data.length)) {
         if ("moov".equals(box.type())) {
            return Mp4BoxReader.payloadBytes(data, box);
         }
      }

      return null;
   }

   private static void sampleTableChecks(byte[] data, String label) {
      int moofCount = 0;
      long totalSamples = 0L;
      long totalBytes = 0L;
      long lastPts = -1L;
      boolean monotonic = true;

      for (int position = 0; position + 8 <= data.length; ) {
         long size = Mp4BoxReader.u32(data, position);
         String type = Mp4BoxReader.fourCc(data, position + 4);
         int headerSize = 8;
         if (size == 1L) {
            size = Mp4BoxReader.u64(data, position + 8);
            headerSize = 16;
         }

         if (size < headerSize) {
            break;
         }

         if ("moof".equals(type)) {
            byte[] moof = new byte[(int)size];
            System.arraycopy(data, position, moof, 0, moof.length);
            byte[] payload = new byte[moof.length - headerSize];
            System.arraycopy(moof, headerSize, payload, 0, payload.length);
            Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(payload, 1000, 30);
            moofCount++;
            totalSamples += table.sampleSizes().length;

            for (int sampleSize : table.sampleSizes()) {
               totalBytes += Math.max(0, sampleSize);
            }

            for (long pts : table.ptsNanos()) {
               if (pts < lastPts) {
                  monotonic = false;
               }

               lastPts = pts;
            }
         }

         position += (int)size;
      }

      System.out.println("sampletable|" + label + "|moofs=" + moofCount + "|samples=" + totalSamples + "|bytes=" + totalBytes + "|monotonicPts=" + monotonic);
   }

   private static void streamWalk(byte[] data, String label) throws Exception {
      AtomicLong moofs = new AtomicLong();
      AtomicLong mdatBytes = new AtomicLong();
      AtomicLong samples = new AtomicLong();
      AtomicBoolean moov = new AtomicBoolean(false);
      Fmp4StreamParser.ContainerKind kind = new Fmp4StreamParser()
         .parse(new java.io.ByteArrayInputStream(data), new AtomicBoolean(false), new Fmp4StreamParser.Callback() {
            @Override
            public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData) {
               moov.set(true);
            }

            @Override
            public void onMoof(int[] sampleSizes, byte[] moofData) {
               moofs.incrementAndGet();
               if (sampleSizes != null) {
                  samples.addAndGet(sampleSizes.length);
               }
            }

            @Override
            public void onMdat(InputStream payload, long size) throws java.io.IOException {
               mdatBytes.addAndGet(size);
               Fmp4StreamParser.skipFully(payload, size);
            }

            @Override
            public void onRawEac3(InputStream payload) {
            }
         });
      System.out.println(
         "stream|" + label + "|kind=" + kind + "|moov=" + moov.get() + "|moofs=" + moofs.get() + "|samples=" + samples.get() + "|mdatBytes=" + mdatBytes.get()
      );
   }

   private static void sidxChecks(byte[] data, String label) {
      int indexStart = -1;

      for (int position = 0; position + 8 <= data.length; ) {
         long size = Mp4BoxReader.u32(data, position);
         String type = Mp4BoxReader.fourCc(data, position + 4);
         int headerSize = 8;
         if (size == 1L) {
            size = Mp4BoxReader.u64(data, position + 8);
            headerSize = 16;
         }

         if (size < headerSize) {
            break;
         }

         if ("sidx".equals(type)) {
            indexStart = position;
            break;
         }

         position += (int)size;
      }

      if (indexStart < 0) {
         System.out.println("sidx|" + label + "|missing");
         return;
      }

      byte[] tail = new byte[data.length - indexStart];
      System.arraycopy(data, indexStart, tail, 0, tail.length);
      List<Fmp4RangeSeekSupport.SidxEntry> entries = Fmp4RangeSeekSupport.parseSidx(tail, indexStart) != null
         ? Fmp4RangeSeekSupport.parseSidx(tail, indexStart).entries()
         : List.of();
      long lastEntryMillis = -1L;
      int moofStarts = 0;

      for (Fmp4RangeSeekSupport.SidxEntry entry : entries) {
         lastEntryMillis = Math.round(entry.timeSeconds() * 1000.0);
         long start = entry.byteStart();
         if (start >= 0L && start + 8 <= data.length && "moof".equals(Mp4BoxReader.fourCc(data, (int)start + 4))) {
            moofStarts++;
         }
      }

      System.out.println(
         "sidx|" + label + "|entries=" + entries.size() + "|moofStarts=" + moofStarts + "|lastEntryMillis=" + lastEntryMillis
            + "|firstByteStart=" + (entries.isEmpty() ? -1L : entries.get(0).byteStart())
      );
   }

   private static void audioPipelineCheck(byte[] audio) {
      try {
         Fmp4RangeSeekSupport.InitSegment init = Fmp4RangeSeekSupport.extractInitSegment(audio, audio.length, (payload, moov) -> moov.timescale);
         if (init == null) {
            System.out.println("pipeline|audio|no-init");
            return;
         }

         byte[] moovPayload = moovPayloadOf(init.bytes());
         if (moovPayload == null) {
            System.out.println("pipeline|audio|no-moov");
            return;
         }

         Fmp4ToMp4Converter.ParseResult parsed = Fmp4ToMp4Converter.parseMoov(moovPayload);
         BlockingAudioPipe pipe = new BlockingAudioPipe(1 << 20);
         AtomicBoolean closed = new AtomicBoolean(false);
         AudioPipelineFactory.Selection selection = AudioPipelineFactory.selectFmp4(
            parsed, Fmp4ToMp4Converter.listAudioCodecs(moovPayload), pipe, closed, null, 0.0F
         );
         if (selection instanceof AudioPipelineFactory.Supported supported) {
            System.out.println("pipeline|audio|supported|" + supported.pipeline().container() + "/" + supported.pipeline().codec());
            supported.pipeline().close();
         } else if (selection instanceof AudioPipelineFactory.Unsupported unsupported) {
            System.out.println("pipeline|audio|unsupported|" + unsupported.reason());
         } else {
            System.out.println("pipeline|audio|unknown");
         }

         pipe.close();
      } catch (Throwable error) {
         // 离线环境缺少 Minecraft 类时会走到这里：如实报告，不当成失败
         System.out.println("pipeline|audio|unavailable|" + error.getClass().getSimpleName());
      }
   }

   private static void http(String[] args) throws Exception {
      Path file = Paths.get(args[1]);
      String contentType = args[2];
      byte[] expected = Files.readAllBytes(file);
      LocalMediaHttpServer server = LocalMediaHttpServer.instance();
      LocalMediaHttpServer.Published published = server.publish(
         "verify" + Long.toHexString(System.nanoTime()), file, contentType, file.getFileName().toString(), 300_000L
      );
      System.out.println("http|url|" + published.url());
      System.out.println("http|host|" + hostOf(published.url()));
      System.out.println("http|publishedLength|" + published.length());

      try (ChunkPrefetchInputStream stream = new ChunkPrefetchInputStream(URI.create(published.url()).toURL())) {
         ByteArrayOutputStream out = new ByteArrayOutputStream();
         byte[] buffer = new byte[65536];
         int read;

         while ((read = stream.read(buffer)) >= 0) {
            if (read > 0) {
               out.write(buffer, 0, read);
            }
         }

         byte[] fetched = out.toByteArray();
         System.out.println("http|prefetchSha256|" + sha256(fetched));
         System.out.println("http|prefetchMatches|" + sha256(fetched).equals(sha256(expected)));
      }

      HttpRangeClient client = new HttpRangeClient();
      URL url = URI.create(published.url()).toURL();
      try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, 0L, 99L)) {
         response.body().readNBytes(200);
         System.out.println("http|range0|" + response.statusCode() + "|" + response.totalLength() + "|" + response.contentLength());
      }

      long length = published.length();
      try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, Math.max(0L, length - 10L), length + 1000L)) {
         response.body().readNBytes(64);
         System.out.println("http|tail|" + response.statusCode() + "|" + response.rangeStart() + "|" + response.rangeEndInclusive() + "|" + response.totalLength());
      }

      try (HttpRangeClient.CdnResponse response = client.getRangeDirect(url, length + 10L, length + 20L)) {
         response.body().readNBytes(64);
         System.out.println("http|beyond|" + response.statusCode());
      }

      String badUrl = published.url().replace("/ncpb/", "/ncpb/deadbeef/");
      try (HttpRangeClient.CdnResponse response = client.getRangeDirect(URI.create(badUrl).toURL(), 0L, 9L)) {
         response.body().readNBytes(64);
         System.out.println("http|unknownToken|" + response.statusCode());
      }
   }

   /**
    * token 过期与滑动续期：先用很短的 TTL 发布，
    *   1. 立刻请求 → 期望 206；
    *   2. 静置超过 TTL 再请求 → 期望 404（过期确实生效，不是永远有效）；
    *   3. 重新发布后每 400ms 请求一次，持续 2 秒（> TTL）→ 每次都必须是 206（滑动续期生效）。
    * 第 3 步是关键：长视频播放时间会超过 TTL，没有续期就会播到一半 404。
    */
   private static void expiry(String[] args) throws Exception {
      Path file = Paths.get(args[1]);
      String contentType = args[2];
      long ttlMillis = args.length > 3 ? Long.parseLong(args[3]) : 1200L;
      LocalMediaHttpServer server = LocalMediaHttpServer.instance();
      HttpRangeClient client = new HttpRangeClient();
      String name = file.getFileName().toString();

      LocalMediaHttpServer.Published first = server.publish("expiry1", file, contentType, name, ttlMillis);
      System.out.println("expiry|fresh|" + statusOf(client, first.url()));
      Thread.sleep(ttlMillis + 500L);
      System.out.println("expiry|expired|" + statusOf(client, first.url()));

      LocalMediaHttpServer.Published second = server.publish("expiry2", file, contentType, name, ttlMillis);
      StringBuilder sliding = new StringBuilder();
      for (int i = 0; i < 5; i++) {
         Thread.sleep(400L);
         if (i > 0) {
            sliding.append(',');
         }

         sliding.append(statusOf(client, second.url()));
      }

      System.out.println("expiry|sliding|" + sliding);
   }

   private static int statusOf(HttpRangeClient client, String url) {
      try (HttpRangeClient.CdnResponse response = client.getRangeDirect(URI.create(url).toURL(), 0L, 31L)) {
         response.body().readNBytes(64);
         return response.statusCode();
      } catch (Exception error) {
         return -1;
      }
   }

   private static int countFiles(Path workDir) throws Exception {
      int count = 0;

      try (var files = Files.newDirectoryStream(workDir, "*.mp4")) {
         for (Path ignored : files) {
            count++;
         }
      } catch (java.io.IOException error) {
         return -1;
      }

      return count;
   }

   private static String hostOf(String url) {
      try {
         String host = URI.create(url).getHost();
         return host != null ? host : "unknown";
      } catch (RuntimeException error) {
         return "unknown";
      }
   }

   private static String sha256(byte[] data) throws Exception {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(data);
      StringBuilder hex = new StringBuilder(bytes.length * 2);

      for (byte value : bytes) {
         hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
      }

      return hex.toString();
   }
}
