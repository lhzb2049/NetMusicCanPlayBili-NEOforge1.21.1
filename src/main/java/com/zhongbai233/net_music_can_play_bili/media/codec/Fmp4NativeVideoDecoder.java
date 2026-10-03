package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.stream.Fmp4StreamParser;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaCloseExecutor;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.slf4j.Logger;

public final class Fmp4NativeVideoDecoder implements AutoCloseable {
   private static final int CODEC_H264 = 7;
   private static final int CODEC_AV1 = 13;
   private static final Fmp4NativeVideoProperties.Decoder PROPERTIES = Fmp4NativeVideoProperties.decoder();
   private static final int FMP4_STREAM_RECOVERY_ATTEMPTS = PROPERTIES.streamRecoveryAttempts();
   private static final int DECODER_CLOSE_MAX_ATTEMPTS = 3;
   private static final long DECODER_CLOSE_INITIAL_BACKOFF_MILLIS = 25L;
   private final URL videoUrl;
   private final String liveBusKey;
   private final int codecId;
   private final int targetWidth;
   private final int targetHeight;
   private final int maxFrames;
   private final Fmp4NativeVideoDecoder.OutputFormat outputFormat;
   private final long startOffsetMillis;
   private final long totalMillis;
   private final int fps;
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private final AtomicBoolean started = new AtomicBoolean(false);
   private final AtomicBoolean finished = new AtomicBoolean(false);
   private final CompletableFuture<Void> workerExit = new CompletableFuture<>();
   private final AtomicReference<Fmp4NativeVideoDecoder.DecoderCloseState> decoderCloseState = new AtomicReference<>(
      Fmp4NativeVideoDecoder.DecoderCloseState.OPEN
   );
   private final CompletableFuture<Void> decoderCloseCompletion = new CompletableFuture<>();
   private final CompletableFuture<Void> termination;
   private final Fmp4NativeVideoDecoder.TrackedInputRegistry trackedInputs = new Fmp4NativeVideoDecoder.TrackedInputRegistry();
   private final VideoNativeDecoder decoder;
   private final Fmp4NativeVideoDecodePump decodePump;
   private final Fmp4VideoStreamSeeker streamSeeker;
   private int[] pendingSampleSizes = new int[0];
   private long[] pendingSamplePtsNanos = new long[0];
   private int pendingSampleIndex;
   private int streamTimescale;
   private volatile IOException failure;
   private volatile Thread worker;
   private int parsedMoofCount;
   private int parsedSampleCount;

   public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, true);
   }

   public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames, boolean outputFrames) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, false);
   }

   public Fmp4NativeVideoDecoder(String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames, boolean outputFrames, boolean outputYuv420) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, null);
   }

   public Fmp4NativeVideoDecoder(
      String videoUrl, int codecId, int targetWidth, int targetHeight, int maxFrames, boolean outputFrames, boolean outputYuv420, String requestedHwaccel
   ) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel, 0L);
   }

   public Fmp4NativeVideoDecoder(
      String videoUrl,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      boolean outputYuv420,
      String requestedHwaccel,
      long startOffsetMillis
   ) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel, startOffsetMillis, 0L);
   }

   public Fmp4NativeVideoDecoder(
      String videoUrl,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      boolean outputYuv420,
      String requestedHwaccel,
      long startOffsetMillis,
      long totalMillis
   ) throws IOException {
      this(videoUrl, codecId, targetWidth, targetHeight, maxFrames, outputFrames, outputYuv420, requestedHwaccel, startOffsetMillis, totalMillis, 60);
   }

   public Fmp4NativeVideoDecoder(
      String videoUrl,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      boolean outputYuv420,
      String requestedHwaccel,
      long startOffsetMillis,
      long totalMillis,
      int fps
   ) throws IOException {
      this(
         videoUrl,
         codecId,
         targetWidth,
         targetHeight,
         maxFrames,
         outputFrames,
         outputYuv420 ? Fmp4NativeVideoDecoder.OutputFormat.NV12 : Fmp4NativeVideoDecoder.OutputFormat.RGBA,
         requestedHwaccel,
         startOffsetMillis,
         totalMillis,
         fps
      );
   }

   public Fmp4NativeVideoDecoder(
      String videoUrl,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      Fmp4NativeVideoDecoder.OutputFormat outputFormat,
      String requestedHwaccel,
      long startOffsetMillis,
      long totalMillis,
      int fps
   ) throws IOException {
      this(
         URI.create(videoUrl).toURL(),
         null,
         codecId,
         targetWidth,
         targetHeight,
         maxFrames,
         outputFrames,
         outputFormat,
         requestedHwaccel,
         startOffsetMillis,
         totalMillis,
         fps
      );
   }

   public static Fmp4NativeVideoDecoder forLiveBus(
      String busKey, int targetWidth, int targetHeight, Fmp4NativeVideoDecoder.OutputFormat outputFormat, String requestedHwaccel, int fps
   ) throws IOException {
      if (busKey != null && !busKey.isBlank()) {
         return new Fmp4NativeVideoDecoder(null, busKey, 7, targetWidth, targetHeight, Integer.MAX_VALUE, true, outputFormat, requestedHwaccel, 0L, 0L, fps);
      } else {
         throw new IOException("直播视频总线 key 为空");
      }
   }

   private Fmp4NativeVideoDecoder(
      URL videoUrl,
      String liveBusKey,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      Fmp4NativeVideoDecoder.OutputFormat outputFormat,
      String requestedHwaccel,
      long startOffsetMillis,
      long totalMillis,
      int fps
   ) throws IOException {
      this.videoUrl = videoUrl;
      this.liveBusKey = liveBusKey;
      if (codecId != 7 && codecId != 13) {
         throw new IOException("不支持的视频 codecId=" + codecId + "（仅支持 7=H.264, 13=AV1）");
      } else {
         this.codecId = codecId;
         this.targetWidth = targetWidth;
         this.targetHeight = targetHeight;
         this.maxFrames = Math.max(1, maxFrames);
         this.outputFormat = outputFormat != null ? outputFormat : Fmp4NativeVideoDecoder.OutputFormat.RGBA;
         this.startOffsetMillis = Math.max(0L, startOffsetMillis);
         this.totalMillis = Math.max(0L, totalMillis);
         this.fps = Math.max(1, fps);
         this.streamSeeker = videoUrl != null
            ? new Fmp4VideoStreamSeeker(videoUrl, this.startOffsetMillis, this.totalMillis, this.trackedInputs, this.closed)
            : null;
         this.decoder = new VideoNativeDecoder(this.codecId, targetWidth, targetHeight);
         this.decodePump = new Fmp4NativeVideoDecodePump(
            this.decoder,
            this.codecId,
            targetWidth,
            targetHeight,
            this.maxFrames,
            outputFrames,
            this.outputFormat,
            this.startOffsetMillis,
            this.fps,
            this.closed
         );
         this.termination = completeAfter(this.workerExit, this::startNativeDecoderClose);
         if (requestedHwaccel != null) {
            this.decoder.setRequestedHwaccel(requestedHwaccel);
         }

         try {
            if (!this.decoder.open()) {
               throw new IOException("无法打开 native 视频 decoder: codecId=" + codecId + ", hwaccel=" + requestedHwaccel);
            }
         } catch (RuntimeException var16) {
            this.decoder.close();
            throw new IOException("无法打开 native 视频 decoder: codecId=" + codecId + ", hwaccel=" + requestedHwaccel, var16);
         }
      }
   }

   public String actualHwaccel() {
      return this.decoder.actualHwaccel();
   }

   public boolean isHardwareAccelerated() {
      return this.decoder.isHardwareAccelerated();
   }

   public static void registerSegmentBase(String videoUrl, long initStart, long initEnd, long indexStart, long indexEnd) {
      Fmp4VideoStreamSeeker.registerSegmentBase(videoUrl, initStart, initEnd, indexStart, indexEnd);
   }

   public byte[] getNextFrame() throws IOException {
      Fmp4NativeVideoDecoder.DecodedFrame frame = this.getNextDecodedFrame();
      if (frame == null) {
         return null;
      } else {
         byte[] var2;
         try {
            var2 = Arrays.copyOf(frame.data(), frame.data().length);
         } finally {
            frame.close();
         }

         return var2;
      }
   }

   public Fmp4NativeVideoDecoder.DecodedFrame getNextDecodedFrame() throws IOException {
      this.decodePump.requireRegularGetter();
      return this.decodePump.awaitNextFrame(this::ensureStarted, this.finished::get, () -> this.failure, this::signalCancel);
   }

   public Fmp4NativeVideoDecoder.DecodedFrame getNextDecodedFrameWithAv1FirstFrameProbe() throws IOException {
      synchronized (this) {
         this.decodePump.beginFirstFrameProbe(this.started.get());
         this.ensureStarted();
      }

      return this.decodePump.awaitNextFrame(this::ensureStarted, this.finished::get, () -> this.failure, this::signalCancel);
   }

   public void commitAv1FirstFrameProbe(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
      this.decodePump.commitFirstFrame(frame);
   }

   public void rejectAv1FirstFrameProbeFrame(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
      this.decodePump.rejectFirstFrame(frame);
   }

   public int getTotalFrames() {
      return this.decodePump.totalFrames();
   }

   private void ensureStarted() {
      synchronized (this) {
         if (!this.started.get() && !this.closed.get()) {
            this.started.set(true);
            Thread created = NetMusicThreadFactory.daemonThread("bili-native-video-decoder", () -> {
               try {
                  this.parseAndDecode();
               } catch (IOException var5x) {
                  this.failure = var5x;
               } finally {
                  this.finished.set(true);
                  this.closed.set(true);
                  this.trackedInputs.beginClose();
                  this.workerExit.complete(null);
               }
            });
            this.worker = created;

            try {
               created.start();
            } catch (Error | RuntimeException var5) {
               this.finished.set(true);
               this.closed.set(true);
               this.trackedInputs.beginClose();
               this.workerExit.complete(null);
               throw var5;
            }
         }
      }
   }

   private void parseAndDecode() throws IOException {
      if (this.liveBusKey != null) {
         this.decodeLiveBusStream();
      } else {
         long streamStartOffsetMillis = this.startOffsetMillis;
         int recoveries = 0;

         while (!this.closed.get() && (this.decodePump.totalFrames() < this.maxFrames || this.decodePump.hasActiveUncommittedProbe())) {
            try {
               this.parseStreamOnce(streamStartOffsetMillis);
               this.decodePump.drainNaturalEndOfStream();
               break;
            } catch (UnsupportedAudioFileException var5) {
               throw new IOException(var5);
            } catch (IOException var6) {
               if (this.closed.get() || !isRecoverableStreamException(var6) || recoveries >= FMP4_STREAM_RECOVERY_ATTEMPTS) {
                  throw var6;
               }

               recoveries++;
               streamStartOffsetMillis = this.estimateCurrentOffsetMillis();
               logger()
                  .warn(
                     "Native video stream interrupted ({}), recovery {}/{} from ~{}ms",
                     new Object[]{var6.getMessage(), recoveries, FMP4_STREAM_RECOVERY_ATTEMPTS, streamStartOffsetMillis}
                  );
               this.resetParserStateForRecovery();
            }
         }
      }
   }

   private void decodeLiveBusStream() throws IOException {
      LiveVideoSampleBus bus = LiveVideoSampleBus.find(this.liveBusKey);
      if (bus == null) {
         throw new IOException("直播视频总线不存在: " + this.liveBusKey);
      } else {
         logger().debug("直播视频总线解码开始: key={} output={} target={}x{}", new Object[]{this.liveBusKey, this.outputFormat, this.targetWidth, this.targetHeight});
         byte[] activeConfig = null;

         while (!this.closed.get()) {
            LiveVideoSampleBus.VideoSample sample;
            try {
               sample = bus.poll(250L);
            } catch (InterruptedException var5) {
               Thread.currentThread().interrupt();
               return;
            }

            if (sample == null) {
               if (bus.isClosed()) {
                  logger().debug("直播视频总线已关闭，解码结束: key={} frames={}", this.liveBusKey, this.decodePump.totalFrames());
                  return;
               }
            } else {
               if (sample.avcConfig() != activeConfig) {
                  Fmp4NativeVideoDecoder.DecoderConfig config = Fmp4VideoDecoderConfigParser.parseAvcC(sample.avcConfig());
                  if (config == null || config.packetPrefix().length == 0) {
                     throw new IOException("直播视频 avcC 无效: bytes=" + (sample.avcConfig() != null ? sample.avcConfig().length : 0));
                  }

                  this.decodePump.configure(config);
                  this.decodePump.resetAfterRecovery();
                  activeConfig = sample.avcConfig();
                  logger()
                     .debug(
                        "直播视频 avcC 已应用: key={} configBytes={} nalLengthSize={}",
                        new Object[]{this.liveBusKey, config.packetPrefix().length, config.nalLengthSize()}
                     );
               }

               this.decodePump.decodeSample(sample.data(), sample.ptsNanos());
            }
         }
      }
   }

   private void parseStreamOnce(long offsetMillis) throws IOException, UnsupportedAudioFileException {
      Fmp4VideoStreamSeeker.StreamStart seekStart = this.streamSeeker.open(offsetMillis);
      InputStream stream = this.trackInput(seekStart.stream());

      try {
         logger()
            .debug(
               "视频 fMP4 解码流开始: offset={}ms fragment={}s residual={}s output={} target={}x{} codecId={}",
               new Object[]{
                  offsetMillis, seekStart.fragmentSeconds(), seekStart.residualSeconds(), this.outputFormat, this.targetWidth, this.targetHeight, this.codecId
               }
            );
         this.decodePump.setSeekWindow(seekStart.residualSeconds(), seekStart.fragmentSeconds(), offsetMillis);
         Fmp4StreamParser.ContainerKind containerKind = new Fmp4StreamParser()
            .parse(
               stream,
               this.closed,
               new Fmp4StreamParser.Callback() {
                  @Override
                  public void onMoov(Fmp4ToMp4Converter.ParseResult parseResult, byte[] moovData) throws IOException {
                     Fmp4NativeVideoDecoder.DecoderConfig config = Fmp4NativeVideoDecoder.extractDecoderConfig(moovData, Fmp4NativeVideoDecoder.this.codecId);
                     if (config != null && (Fmp4NativeVideoDecoder.this.codecId != 7 || config.packetPrefix().length != 0)) {
                        Fmp4NativeVideoDecoder.this.decodePump.configure(config);
                        int videoTimescale = Fmp4ToMp4Converter.parseVideoTimescale(moovData);
                        Fmp4NativeVideoDecoder.this.streamTimescale = videoTimescale > 0 ? videoTimescale : parseResult.timescale;
                        Fmp4NativeVideoDecoder.logger()
                           .debug(
                              "视频 fMP4 moov 已解析: configBytes={} nalLengthSize={} timescale={} moovBytes={}",
                              new Object[]{config.packetPrefix().length, config.nalLengthSize(), Fmp4NativeVideoDecoder.this.streamTimescale, moovData.length}
                           );
                     } else {
                        throw new IOException("无法从 moov/stsd 提取视频 decoder config: codecId=" + Fmp4NativeVideoDecoder.this.codecId);
                     }
                  }

                  @Override
                  public void onMoof(int[] sampleSizes, byte[] moofData) {
                     Fmp4ToMp4Converter.SampleTable table = Fmp4ToMp4Converter.extractSampleTableFromMoof(
                        moofData,
                        Fmp4NativeVideoDecoder.this.streamTimescale > 0 ? Fmp4NativeVideoDecoder.this.streamTimescale : Fmp4NativeVideoDecoder.this.fps,
                        Fmp4NativeVideoDecoder.this.fps
                     );
                     Fmp4NativeVideoDecoder.this.pendingSampleSizes = table.sampleSizes().length > 0
                        ? table.sampleSizes()
                        : (sampleSizes != null ? sampleSizes : new int[0]);
                     Fmp4NativeVideoDecoder.this.pendingSamplePtsNanos = table.ptsNanos();
                     Fmp4NativeVideoDecoder.this.pendingSampleIndex = 0;
                     Fmp4NativeVideoDecoder.this.parsedMoofCount++;
                     Fmp4NativeVideoDecoder.this.decodePump.setParsedMoofCount(Fmp4NativeVideoDecoder.this.parsedMoofCount);
                     Fmp4NativeVideoDecoder.this.parsedSampleCount = Fmp4NativeVideoDecoder.this.parsedSampleCount
                        + Fmp4NativeVideoDecoder.this.pendingSampleSizes.length;
                     if (Fmp4NativeVideoDecoder.this.parsedMoofCount <= 2) {
                        Fmp4NativeVideoDecoder.logger()
                           .debug(
                              "视频 fMP4 moof 已解析: index={} samples={} firstSampleBytes={} firstPtsNanos={}",
                              new Object[]{
                                 Fmp4NativeVideoDecoder.this.parsedMoofCount,
                                 Fmp4NativeVideoDecoder.this.pendingSampleSizes.length,
                                 Fmp4NativeVideoDecoder.this.pendingSampleSizes.length > 0 ? Fmp4NativeVideoDecoder.this.pendingSampleSizes[0] : 0,
                                 Fmp4NativeVideoDecoder.this.pendingSamplePtsNanos.length > 0 ? Fmp4NativeVideoDecoder.this.pendingSamplePtsNanos[0] : -1L
                              }
                           );
                     }
                  }

                  @Override
                  public void onMdat(InputStream payload, long size) throws IOException {
                     if (!Fmp4NativeVideoDecoder.this.decodePump.isConfigured()) {
                        throw new IOException("mdat arrived before video decoder config");
                     } else if (Fmp4NativeVideoDecoder.this.pendingSampleSizes.length == 0) {
                        Fmp4NativeVideoDecoder.logger().debug("视频 fMP4 mdat 无样本表: bytes={}", size);
                        byte[] all = Fmp4StreamParser.readFully(payload, size);
                        Fmp4NativeVideoDecoder.this.decodePump.decodeSample(all, Fmp4NativeVideoDecoder.this.nextSamplePtsNanos());
                     } else {
                        if (Fmp4NativeVideoDecoder.this.parsedMoofCount <= 2) {
                           Fmp4NativeVideoDecoder.logger()
                              .debug(
                                 "视频 fMP4 mdat 开始: bytes={} samples={} parsedSamples={}",
                                 new Object[]{size, Fmp4NativeVideoDecoder.this.pendingSampleSizes.length, Fmp4NativeVideoDecoder.this.parsedSampleCount}
                              );
                        }

                        for (int sampleSize : Fmp4NativeVideoDecoder.this.pendingSampleSizes) {
                           if (Fmp4NativeVideoDecoder.this.closed.get()
                              || Fmp4NativeVideoDecoder.this.decodePump.totalFrames() >= Fmp4NativeVideoDecoder.this.maxFrames) {
                              return;
                           }

                           if (sampleSize > 0) {
                              byte[] sample = Fmp4StreamParser.readFully(payload, sampleSize);
                              Fmp4NativeVideoDecoder.this.decodePump.decodeSample(sample, Fmp4NativeVideoDecoder.this.nextSamplePtsNanos());
                           }
                        }
                     }
                  }

                  @Override
                  public void onRawEac3(InputStream payload) throws IOException, UnsupportedAudioFileException {
                     throw new UnsupportedAudioFileException("video stream is not fMP4 video");
                  }
               }
            );
         if (containerKind == Fmp4StreamParser.ContainerKind.OTHER_AUDIO) {
            throw new UnsupportedAudioFileException("video stream is not fMP4 video");
         }
      } finally {
         this.closeInputTracked(stream);
      }
   }

   private InputStream trackInput(InputStream stream) throws IOException {
      InputStream tracked = this.trackedInputs.track(stream);
      if (this.closed.get()) {
         this.trackedInputs.beginClose();
         throw new IOException("native video decoder closed");
      } else {
         return tracked;
      }
   }

   private void closeInputTracked(InputStream stream) {
      this.trackedInputs.closeAsync(stream);
   }

   private long estimateCurrentOffsetMillis() {
      return this.decodePump.estimateCurrentOffsetMillis(this.totalMillis);
   }

   private void resetParserStateForRecovery() {
      this.pendingSampleSizes = new int[0];
      this.pendingSamplePtsNanos = new long[0];
      this.pendingSampleIndex = 0;
      this.streamTimescale = 0;
      this.decodePump.resetAfterRecovery();
   }

   private static boolean isRecoverableStreamException(IOException error) {
      for (Throwable current = error; current != null; current = current.getCause()) {
         if (current instanceof EOFException) {
            return true;
         }

         if (current instanceof IOException && current.getMessage() != null) {
            String message = current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("closed") || message.contains("eof reached") || message.contains("ended early")) {
               return true;
            }
         }
      }

      return false;
   }

   private long nextSamplePtsNanos() {
      int index = this.pendingSampleIndex++;
      return this.pendingSamplePtsNanos != null && index >= 0 && index < this.pendingSamplePtsNanos.length ? this.pendingSamplePtsNanos[index] : -1L;
   }

   static Fmp4NativeVideoDecoder.DecoderConfig extractDecoderConfig(byte[] moovData, int codecId) {
      return Fmp4VideoDecoderConfigParser.extract(moovData, codecId);
   }

   static byte[] parseAv1ConfigObus(byte[] av1C) {
      return Fmp4VideoDecoderConfigParser.parseAv1ConfigObus(av1C);
   }

   @Override
   public void close() {
      this.requestClose();
      Thread thread = this.worker;
      if (thread != null && thread != Thread.currentThread()) {
         try {
            thread.join(2000L);
         } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
         }

         if (thread.isAlive()) {
            logger().warn("Native video decoder worker did not stop within 2000ms: {}", this.videoUrl);
         }
      }

      if (thread == null || !thread.isAlive()) {
         this.workerExit.complete(null);
      }

      this.decodePump.releaseResources();
   }

   public void requestClose() {
      Thread thread;
      synchronized (this) {
         this.closed.set(true);
         this.decodePump.cancelProbe();
         thread = this.worker;
         if (thread != null) {
            thread.interrupt();
         }
      }

      this.trackedInputs.beginClose();
      if (thread == null || !thread.isAlive()) {
         this.workerExit.complete(null);
      }
   }

   private void signalCancel() {
      this.requestClose();
   }

   public CompletableFuture<Void> terminationFuture() {
      return this.termination.copy();
   }

   private CompletableFuture<Void> startNativeDecoderClose() {
      return MediaCloseExecutor.closeAsyncIsolatedStrict(this::closeDecoderOnce, "native video decoder handle")
         .thenCompose(ignored -> this.decoderCloseCompletion);
   }

   static CompletableFuture<Void> completeAfter(CompletableFuture<Void> prerequisite, Supplier<CompletableFuture<Void>> completionStarter) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      prerequisite.whenComplete((ignored, prerequisiteError) -> {
         if (prerequisiteError != null) {
            result.completeExceptionally(prerequisiteError);
         } else {
            CompletableFuture<Void> completion;
            try {
               completion = completionStarter.get();
               if (completion == null) {
                  throw new IllegalStateException("native video completion starter returned null");
               }
            } catch (Throwable var6) {
               result.completeExceptionally(var6);
               return;
            }

            completion.whenComplete((closeIgnored, closeError) -> {
               if (closeError == null) {
                  result.complete(null);
               } else {
                  result.completeExceptionally(closeError);
               }
            });
         }
      });
      return result;
   }

   private void closeDecoderOnce() {
      Fmp4NativeVideoDecoder.DecoderCloseState current = this.decoderCloseState.get();
      if (current == Fmp4NativeVideoDecoder.DecoderCloseState.OPEN) {
         if (this.decoderCloseState.compareAndSet(Fmp4NativeVideoDecoder.DecoderCloseState.OPEN, Fmp4NativeVideoDecoder.DecoderCloseState.CLOSING)) {
            Throwable closeFailure = closeWithBoundedRetry(
               this.decoder::close,
               3,
               25L,
               Thread::sleep,
               (attempt, delayMillis, error) -> logger()
                  .warn("Native video decoder handle close attempt {}/{} failed; retrying in {}ms", new Object[]{attempt, 3, delayMillis, error})
            );
            if (closeFailure == null) {
               this.decoderCloseState.set(Fmp4NativeVideoDecoder.DecoderCloseState.CLOSED);
               this.decoderCloseCompletion.complete(null);
            } else {
               this.decoderCloseState.set(Fmp4NativeVideoDecoder.DecoderCloseState.FAILED);
               this.decoderCloseCompletion.completeExceptionally(closeFailure);
               logger().error("Native video decoder handle close failed after {} attempts; termination is exceptional", 3, closeFailure);
            }
         }
      }
   }

   static Throwable closeWithBoundedRetry(
      Fmp4NativeVideoDecoder.CloseOperation operation,
      int maxAttempts,
      long initialBackoffMillis,
      Fmp4NativeVideoDecoder.RetrySleeper sleeper,
      Fmp4NativeVideoDecoder.CloseRetryListener retryListener
   ) {
      return NativeDecoderCloseRetry.close(operation, maxAttempts, initialBackoffMillis, sleeper, retryListener);
   }

   private static Logger logger() {
      return Fmp4NativeVideoDecoder.LoggerHolder.INSTANCE;
   }

   @FunctionalInterface
   interface CloseOperation extends NativeDecoderCloseRetry.CloseOperation {
   }

   @FunctionalInterface
   interface CloseRetryListener extends NativeDecoderCloseRetry.CloseRetryListener {
   }

   public static final class DecodedFrame implements AutoCloseable {
      private final byte[] data;
      private final ByteBuffer buffer;
      private final int byteLength;
      private final Fmp4NativeVideoDecoder.DecodedFrame.Format format;
      private final Fmp4NativeVideoDecoder.DecodedFrame.SharedRelease release;
      private long ptsNanos;
      private long nativeGetNanos;
      private long queueWaitNanos;
      private long probeTicket = -1L;
      private final AtomicBoolean closed = new AtomicBoolean(false);

      DecodedFrame(byte[] data, Fmp4NativeVideoDecoder.DecodedFrame.Format format, Runnable release) {
         this(data, null, data != null ? data.length : 0, format, release, -1L, -1L, -1L);
      }

      DecodedFrame(ByteBuffer buffer, int byteLength, Fmp4NativeVideoDecoder.DecodedFrame.Format format, Runnable release) {
         this(null, buffer, byteLength, format, release, -1L, -1L, -1L);
      }

      private DecodedFrame(
         byte[] data,
         ByteBuffer buffer,
         int byteLength,
         Fmp4NativeVideoDecoder.DecodedFrame.Format format,
         Runnable release,
         long ptsNanos,
         long nativeGetNanos,
         long queueWaitNanos
      ) {
         this(data, buffer, byteLength, format, new Fmp4NativeVideoDecoder.DecodedFrame.SharedRelease(release), ptsNanos, nativeGetNanos, queueWaitNanos);
      }

      private DecodedFrame(
         byte[] data,
         ByteBuffer buffer,
         int byteLength,
         Fmp4NativeVideoDecoder.DecodedFrame.Format format,
         Fmp4NativeVideoDecoder.DecodedFrame.SharedRelease release,
         long ptsNanos,
         long nativeGetNanos,
         long queueWaitNanos
      ) {
         this.data = data;
         this.buffer = buffer;
         this.byteLength = Math.max(0, byteLength);
         this.format = format != null ? format : Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA;
         this.release = release;
         this.ptsNanos = ptsNanos;
         this.nativeGetNanos = nativeGetNanos;
         this.queueWaitNanos = queueWaitNanos;
      }

      static Fmp4NativeVideoDecoder.DecodedFrame wrap(byte[] rgba) {
         return wrap(rgba, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA);
      }

      static Fmp4NativeVideoDecoder.DecodedFrame wrap(byte[] data, Fmp4NativeVideoDecoder.DecodedFrame.Format format) {
         return data != null ? new Fmp4NativeVideoDecoder.DecodedFrame(data, format, null) : null;
      }

      public byte[] data() {
         if (this.data == null && this.buffer != null) {
            ByteBuffer src = this.bufferSlice();
            byte[] copy = new byte[src.remaining()];
            src.get(copy);
            return copy;
         } else {
            return this.data;
         }
      }

      public ByteBuffer buffer() {
         return this.bufferSlice();
      }

      public int byteLength() {
         if (this.byteLength > 0) {
            return this.byteLength;
         } else {
            return this.data != null ? this.data.length : 0;
         }
      }

      private ByteBuffer bufferSlice() {
         if (this.buffer == null) {
            return null;
         } else {
            ByteBuffer duplicate = this.buffer.duplicate();
            duplicate.position(0);
            duplicate.limit(Math.min(this.buffer.capacity(), this.byteLength()));
            return duplicate.slice().order(this.buffer.order());
         }
      }

      public Fmp4NativeVideoDecoder.DecodedFrame.Format format() {
         return this.format;
      }

      public byte[] rgba() {
         if (this.format != Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA) {
            throw new IllegalStateException("decoded frame is " + this.format + ", not RGBA");
         } else {
            return this.data;
         }
      }

      public long ptsNanos() {
         return this.ptsNanos;
      }

      public long nativeGetNanos() {
         return this.nativeGetNanos;
      }

      public long queueWaitNanos() {
         return this.queueWaitNanos;
      }

      long probeTicket() {
         return this.probeTicket;
      }

      Fmp4NativeVideoDecoder.DecodedFrame withPtsNanos(long ptsNanos) {
         this.ptsNanos = ptsNanos;
         return this;
      }

      Fmp4NativeVideoDecoder.DecodedFrame withNativeGetNanos(long nativeGetNanos) {
         this.nativeGetNanos = nativeGetNanos;
         return this;
      }

      Fmp4NativeVideoDecoder.DecodedFrame withQueueWaitNanos(long queueWaitNanos) {
         this.queueWaitNanos = queueWaitNanos;
         return this;
      }

      Fmp4NativeVideoDecoder.DecodedFrame withProbeTicket(long probeTicket) {
         this.probeTicket = probeTicket;
         return this;
      }

      public Fmp4NativeVideoDecoder.DecodedFrame retain() {
         if (!this.closed.get() && this.release.tryRetain()) {
            return new Fmp4NativeVideoDecoder.DecodedFrame(
                  this.data, this.buffer, this.byteLength, this.format, this.release, this.ptsNanos, this.nativeGetNanos, this.queueWaitNanos
               )
               .withProbeTicket(this.probeTicket);
         } else {
            throw new IllegalStateException("decoded frame is already closed");
         }
      }

      @Override
      public void close() {
         if (this.closed.compareAndSet(false, true)) {
            this.release.release();
         }
      }

      public static enum Format {
         RGBA,
         YUV420P,
         NV12;
      }

      private static final class SharedRelease {
         private final AtomicInteger references = new AtomicInteger(1);
         private final Runnable action;

         private SharedRelease(Runnable action) {
            this.action = action;
         }

         private boolean tryRetain() {
            int current;
            do {
               current = this.references.get();
               if (current == 0) {
                  return false;
               }
            } while (!this.references.compareAndSet(current, current + 1));

            return true;
         }

         private void release() {
            if (this.references.decrementAndGet() == 0 && this.action != null) {
               this.action.run();
            }
         }
      }
   }

   private static enum DecoderCloseState {
      OPEN,
      CLOSING,
      CLOSED,
      FAILED;
   }

   record DecoderConfig(int nalLengthSize, byte[] packetPrefix) {
   }

   @FunctionalInterface
   interface InputCloseScheduler extends NativeVideoTrackedInputs.InputCloseScheduler {
   }

   private static final class LoggerHolder {
      private static final Logger INSTANCE = LogUtils.getLogger();
   }

   public static enum OutputFormat {
      RGBA,
      YUV420P,
      NV12;
   }

   @FunctionalInterface
   interface RetrySleeper extends NativeDecoderCloseRetry.RetrySleeper {
   }

   static final class TrackedInputRegistry extends NativeVideoTrackedInputs {
      TrackedInputRegistry() {
      }

      TrackedInputRegistry(NativeVideoTrackedInputs.InputCloseScheduler closeScheduler) {
         super(closeScheduler);
      }
   }
}
