package com.zhongbai233.net_music_can_play_bili.media.codec;

import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

final class Fmp4NativeVideoDecodePump {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int CODEC_AV1 = 13;
   private static final Fmp4NativeVideoProperties.Decoder PROPERTIES = Fmp4NativeVideoProperties.decoder();
   private static final Fmp4NativeVideoProperties.Seek SEEK = Fmp4NativeVideoProperties.seek();
   private static final Fmp4NativeVideoProperties.FirstFrameProbe FIRST_FRAME_PROBE = Fmp4NativeVideoProperties.firstFrameProbe();
   private static final int MAX_PENDING_FRAMES = PROPERTIES.maxPendingFrames();
   private static final long SAFE_NO_COPY_DROP_GUARD_NANOS = Math.max(0L, SEEK.noCopyDropGuardMillis() * 1000000L);
   private static final boolean REUSE_OUTPUT_BUFFERS = PROPERTIES.reuseOutputBuffers();
   private static final boolean DIRECT_NV12_BUFFERS = PROPERTIES.directNv12Buffers();
   private static final byte[] DECODE_ONLY_FRAME = new byte[0];
   private final VideoNativeDecoder decoder;
   private final int codecId;
   private final int targetWidth;
   private final int targetHeight;
   private final int maxFrames;
   private final boolean outputFrames;
   private final Fmp4NativeVideoDecoder.OutputFormat outputFormat;
   private final long startOffsetMillis;
   private final int fps;
   private final AtomicBoolean closed;
   private final BlockingQueue<Fmp4NativeVideoDecodePump.QueuedDecodedFrame> frames = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
   private final BlockingQueue<byte[]> reusableBuffers = new ArrayBlockingQueue<>(MAX_PENDING_FRAMES);
   private final NativeNv12BufferPool nativeNv12Buffers = new NativeNv12BufferPool(MAX_PENDING_FRAMES);
   private final ArrayDeque<Long> pendingDecodedPtsNanos = new ArrayDeque<>();
   private byte[] decoderConfig;
   private int nalLengthSize = 4;
   private boolean sentConfig;
   private int totalFrames;
   private int fallbackFramesToDrop;
   private long timelineStartNanos;
   private long dropBeforeMediaPtsNanos;
   private long lastDecodedMediaPtsNanos = -1L;
   private int parsedMoofCount;
   private volatile int sentPacketCount;
   private volatile Av1FirstFrameProbe activeFirstFrameProbe;
   private volatile Thread firstFrameProbeConsumer;
   private boolean decoderStageLogged;
   private int receivedFrameCount;
   private int droppedFrameCount;
   private boolean dropStageLogged;
   private boolean outputStageLogged;

   Fmp4NativeVideoDecodePump(
      VideoNativeDecoder decoder,
      int codecId,
      int targetWidth,
      int targetHeight,
      int maxFrames,
      boolean outputFrames,
      Fmp4NativeVideoDecoder.OutputFormat outputFormat,
      long startOffsetMillis,
      int fps,
      AtomicBoolean closed
   ) {
      this.decoder = decoder;
      this.codecId = codecId;
      this.targetWidth = targetWidth;
      this.targetHeight = targetHeight;
      this.maxFrames = Math.max(1, maxFrames);
      this.outputFrames = outputFrames;
      this.outputFormat = outputFormat;
      this.startOffsetMillis = Math.max(0L, startOffsetMillis);
      this.fps = Math.max(1, fps);
      this.closed = closed;
   }

   synchronized void beginFirstFrameProbe(boolean workerStarted) throws IOException {
      if (this.codecId != 13) {
         throw new IOException("AV1 首帧预算只能用于 AV1 decoder: codecId=" + this.codecId);
      } else {
         Thread currentConsumer = Thread.currentThread();
         if (this.firstFrameProbeConsumer != null && this.firstFrameProbeConsumer != currentConsumer) {
            throw new IOException("AV1 首帧探测只允许单消费者");
         } else {
            this.firstFrameProbeConsumer = currentConsumer;
            if (this.activeFirstFrameProbe == null && workerStarted) {
               throw new IOException("AV1 首帧预算必须在 decoder worker 启动前设置");
            } else {
               if (this.activeFirstFrameProbe == null) {
                  this.activeFirstFrameProbe = new Av1FirstFrameProbe(System.nanoTime(), FIRST_FRAME_PROBE.timeoutMillis(), FIRST_FRAME_PROBE.maxPackets());
               }
            }
         }
      }
   }

   void requireRegularGetter() throws IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe != null) {
         Av1FirstFrameProbe.Decision decision = probe.decision();
         if (decision != Av1FirstFrameProbe.Decision.COMMITTED && decision != Av1FirstFrameProbe.Decision.CANCELLED) {
            throw new IOException("AV1 首帧探测进行中不允许使用无界 getter");
         }
      }
   }

   void commitFirstFrame(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      long ticket = frame != null ? frame.probeTicket() : -1L;
      if (probe == null || !probe.commit(ticket)) {
         throw new IOException("AV1 首帧候选提交已失效: ticket=" + ticket);
      }
   }

   void rejectFirstFrame(Fmp4NativeVideoDecoder.DecodedFrame frame) throws IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      long ticket = frame != null ? frame.probeTicket() : -1L;
      if (probe == null) {
         throw new IOException("AV1 首帧候选拒绝已失效: ticket=" + ticket);
      } else if (!probe.reject(ticket) && probe.decision() != Av1FirstFrameProbe.Decision.CANCELLED) {
         throw new IOException("AV1 首帧候选拒绝已失效: ticket=" + ticket);
      }
   }

   Fmp4NativeVideoDecoder.DecodedFrame awaitNextFrame(Runnable ensureStarted, BooleanSupplier finished, Supplier<IOException> failure, Runnable cancel) throws IOException {
      ensureStarted.run();
      long waitStartNs = System.nanoTime();

      Fmp4NativeVideoDecodePump.QueuedDecodedFrame ready;
      do {
         ready = this.frames.poll();
         if (ready != null) {
            return this.acceptQueuedFrame(ready, waitStartNs);
         }

         if (finished.getAsBoolean()) {
            IOException decodeFailure = failure.get();
            if (decodeFailure != null) {
               throw decodeFailure;
            }

            return null;
         }

         if (this.closed.get()) {
            return null;
         }

         IOException budgetFailure = this.firstFrameBudgetFailureIfIdle();
         if (budgetFailure != null) {
            cancel.run();
            throw budgetFailure;
         }

         try {
            ready = this.frames.poll(this.nextFramePollNanos(), TimeUnit.NANOSECONDS);
         } catch (InterruptedException var10) {
            Thread.currentThread().interrupt();
            if (!this.closed.get() && !finished.getAsBoolean()) {
               throw new IOException("等待 native 视频帧时被中断", var10);
            }

            return null;
         }
      } while (ready == null);

      return this.acceptQueuedFrame(ready, waitStartNs);
   }

   void configure(Fmp4NativeVideoDecoder.DecoderConfig config) {
      this.decoderConfig = config.packetPrefix();
      this.nalLengthSize = config.nalLengthSize();
   }

   boolean isConfigured() {
      return this.decoderConfig != null;
   }

   void setSeekWindow(float residualSeconds, double fragmentSeconds, long requestedOffsetMillis) {
      this.fallbackFramesToDrop = Math.max(0, Math.round(residualSeconds * this.fps));
      this.timelineStartNanos = Math.max(0L, Math.round(fragmentSeconds * 1.0E9));
      this.dropBeforeMediaPtsNanos = Math.max(0L, requestedOffsetMillis * 1000000L);
   }

   void setParsedMoofCount(int parsedMoofCount) {
      this.parsedMoofCount = parsedMoofCount;
   }

   void resetAfterRecovery() {
      this.pendingDecodedPtsNanos.clear();
      this.fallbackFramesToDrop = 0;
      this.timelineStartNanos = 0L;
      this.dropBeforeMediaPtsNanos = 0L;
      this.sentConfig = false;
      this.decoder.flush();
   }

   long estimateCurrentOffsetMillis(long totalMillis) {
      if (this.lastDecodedMediaPtsNanos >= 0L) {
         long offset = this.lastDecodedMediaPtsNanos / 1000000L;
         return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
      } else {
         long decodedMillis = Math.round(this.totalFrames * 1000.0 / this.fps);
         long offset = Math.max(0L, this.startOffsetMillis + decodedMillis);
         return totalMillis > 0L ? Math.min(totalMillis, offset) : offset;
      }
   }

   int totalFrames() {
      return this.totalFrames;
   }

   boolean hasActiveUncommittedProbe() {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe == null) {
         return false;
      } else {
         Av1FirstFrameProbe.Decision decision = probe.decision();
         return decision == Av1FirstFrameProbe.Decision.CONTINUE
            || decision == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT
            || decision == Av1FirstFrameProbe.Decision.FRAME_PENDING;
      }
   }

   void decodeSample(byte[] mp4Sample, long samplePtsNanos) throws IOException {
      if (mp4Sample.length != 0 && this.totalFrames < this.maxFrames) {
         byte[] packet = this.packetizeSample(mp4Sample);
         Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
         Av1FirstFrameProbe.PacketPermit packetPermit = null;
         if (probe != null) {
            Av1FirstFrameProbe.PacketAdmission admission = probe.beginPacketNow();
            if (admission.admitted()) {
               packetPermit = admission.permit();
            } else if (!admission.bypassedAfterCommit()) {
               Av1FirstFrameProbe.Decision decision = admission.decision();
               if (decision != Av1FirstFrameProbe.Decision.CANCELLED && !this.closed.get()) {
                  if (isProbeFailure(decision)) {
                     throw this.firstFrameProbeFailure(decision, probe, Math.max(0L, System.nanoTime() - probe.startedNanos()));
                  }

                  throw new IOException("AV1 首帧探测拒绝新媒体包: decision=" + decision);
               }

               return;
            }
         }

         boolean sent;
         try {
            sent = this.decoder.sendPacket(packet, samplePtsNanos);
         } catch (Error | RuntimeException var13) {
            if (probe != null && packetPermit != null) {
               probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
            }

            throw var13;
         }

         if (!sent) {
            if (probe != null && packetPermit != null) {
               probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.SEND_REJECTED);
            }

            LOGGER.error(
               "视频 native sendPacket 失败: codecId={} sampleBytes={} packetBytes={} ptsNanos={} sentPackets={} parsedMoofs={}",
               new Object[]{this.codecId, mp4Sample.length, packet.length, samplePtsNanos, this.sentPacketCount, this.parsedMoofCount}
            );
            throw new IOException("VideoNativeDecoder.sendPacket failed codecId=" + this.codecId + ", packet=" + packet.length);
         } else {
            this.sentPacketCount++;
            if (probe != null && packetPermit != null) {
               Av1FirstFrameProbe.PacketTransition transition = probe.markPacketSent(packetPermit);
               if (!transition.applied()) {
                  probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
                  throw new IOException("AV1 首帧探测 packet permit 已失效: ordinal=" + packetPermit.ordinal() + ", decision=" + transition.decision());
               }

               if (transition.decision() == Av1FirstFrameProbe.Decision.CANCELLED || this.closed.get()) {
                  probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
                  return;
               }
            }

            if (this.sentPacketCount <= 3) {
               LOGGER.debug(
                  "视频 native packet 已发送: index={} sampleBytes={} packetBytes={} ptsNanos={}",
                  new Object[]{this.sentPacketCount, mp4Sample.length, packet.length, samplePtsNanos}
               );
            }

            this.pendingDecodedPtsNanos.addLast(samplePtsNanos);

            try {
               this.drainFrames(packetPermit);
            } finally {
               if (probe != null && packetPermit != null) {
                  probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
               }
            }
         }
      }
   }

   void drainNaturalEndOfStream() throws IOException {
      if (!this.closed.get() && this.totalFrames < this.maxFrames) {
         Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
         Av1FirstFrameProbe.PacketPermit eofPermit = null;
         if (probe != null && probe.decision() != Av1FirstFrameProbe.Decision.COMMITTED) {
            Av1FirstFrameProbe.PacketAdmission admission = probe.beginEndOfStreamDrainNow();
            if (!admission.admitted()) {
               if (isProbeFailure(admission.decision())) {
                  throw this.firstFrameProbeFailure(admission.decision(), probe, Math.max(0L, System.nanoTime() - probe.startedNanos()));
               }

               if (admission.decision() == Av1FirstFrameProbe.Decision.CANCELLED) {
                  return;
               }

               throw new IOException("AV1 EOF drain 无法取得首帧探测 lease: decision=" + admission.decision());
            }

            eofPermit = admission.permit();
         }

         if (!this.decoder.sendEndOfStream()) {
            if (probe != null && eofPermit != null) {
               probe.endPacket(eofPermit, Av1FirstFrameProbe.PacketEnd.ABORTED);
            }

            LOGGER.debug("当前 native bundle 不支持视频 EOF drain；保持 v38 兼容行为");
         } else {
            try {
               this.drainFrames(eofPermit);
            } finally {
               if (probe != null && eofPermit != null) {
                  probe.endPacket(eofPermit, Av1FirstFrameProbe.PacketEnd.DRAINED);
               }
            }
         }
      }
   }

   void cancelProbe() {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe != null) {
         probe.cancel();
      }
   }

   void releaseResources() {
      Fmp4NativeVideoDecodePump.QueuedDecodedFrame queued;
      while ((queued = this.frames.poll()) != null) {
         queued.frame().close();
      }

      this.reusableBuffers.clear();
      this.nativeNv12Buffers.retire();
   }

   private Fmp4NativeVideoDecoder.DecodedFrame acceptQueuedFrame(Fmp4NativeVideoDecodePump.QueuedDecodedFrame queued, long waitStartNanos) {
      return queued.frame().withProbeTicket(queued.probeTicket()).withQueueWaitNanos(System.nanoTime() - waitStartNanos);
   }

   private long nextFramePollNanos() {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      Av1FirstFrameProbe.Decision decision = probe != null ? probe.decision() : null;
      if (probe != null && decision != Av1FirstFrameProbe.Decision.COMMITTED) {
         long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(probe.timeoutMillis());
         long remainingNanos = timeoutNanos - Math.max(0L, System.nanoTime() - probe.startedNanos());
         return remainingNanos > 0L || decision != Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT && decision != Av1FirstFrameProbe.Decision.FRAME_PENDING
            ? Math.max(1L, Math.min(TimeUnit.MILLISECONDS.toNanos(50L), remainingNanos))
            : TimeUnit.MILLISECONDS.toNanos(50L);
      } else {
         return TimeUnit.MILLISECONDS.toNanos(250L);
      }
   }

   private IOException firstFrameBudgetFailureIfIdle() {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe == null) {
         return null;
      } else {
         Av1FirstFrameProbe.Decision decision = probe.evaluateConsumerTimeNow();
         long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
         return isProbeFailure(decision) ? this.firstFrameProbeFailure(decision, probe, elapsedNanos) : null;
      }
   }

   private IOException firstFrameProbeFailure(Av1FirstFrameProbe.Decision decision, Av1FirstFrameProbe probe, long elapsedNanos) {
      String exhausted = decision == Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED ? "packet" : "time";
      return new IOException(
         "AV1 首帧探测预算耗尽: exhausted="
            + exhausted
            + ", elapsedMs="
            + TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
            + ", sentPackets="
            + probe.successfulPackets()
            + ", timeoutMs="
            + probe.timeoutMillis()
            + ", maxPackets="
            + probe.maxPackets()
      );
   }

   private static boolean isProbeFailure(Av1FirstFrameProbe.Decision decision) {
      return decision == Av1FirstFrameProbe.Decision.TIME_EXHAUSTED || decision == Av1FirstFrameProbe.Decision.PACKET_EXHAUSTED;
   }

   private byte[] packetizeSample(byte[] mp4Sample) throws IOException {
      if (this.codecId == 13) {
         if (!this.sentConfig && this.decoderConfig.length != 0) {
            ByteArrayOutputStream obu = new ByteArrayOutputStream(this.decoderConfig.length + mp4Sample.length);
            obu.write(this.decoderConfig);
            obu.write(mp4Sample);
            this.sentConfig = true;
            return obu.toByteArray();
         } else {
            this.sentConfig = true;
            return mp4Sample;
         }
      } else {
         ByteArrayOutputStream annexB = new ByteArrayOutputStream(mp4Sample.length + this.decoderConfig.length + 32);
         if (!this.sentConfig || Fmp4VideoDecoderConfigParser.isH264KeyframeSample(mp4Sample, this.nalLengthSize)) {
            annexB.write(this.decoderConfig);
            this.sentConfig = true;
         }

         Fmp4VideoDecoderConfigParser.writeLengthPrefixedSampleAsAnnexB(mp4Sample, this.nalLengthSize, annexB);
         return annexB.toByteArray();
      }
   }

   private void drainFrames(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
      if (!this.outputFrames) {
         this.drainFramesNoOutput(packetPermit);
      } else {
         while (!this.closed.get() && (packetPermit != null || this.totalFrames < this.maxFrames)) {
            if (!this.drainDropFrameNoOutput(packetPermit)) {
               long nativeStartNs = System.nanoTime();
               Fmp4NativeVideoDecoder.DecodedFrame frame = this.getNextOutputFrame();
               long frameReadyNanos = System.nanoTime();
               long nativeGetNs = System.nanoTime() - nativeStartNs;
               if (frame == null) {
                  if (!this.decoderStageLogged && this.sentPacketCount <= 3) {
                     this.decoderStageLogged = true;
                     LOGGER.debug(
                        "视频 native 暂无输出帧: sentPackets={} parsedMoofs={} pendingPts={} nativeGet={}us",
                        new Object[]{this.sentPacketCount, this.parsedMoofCount, this.pendingDecodedPtsNanos.size(), nativeGetNs / 1000L}
                     );
                  }

                  this.finishProbePacketDrain(packetPermit);
                  return;
               }

               if (packetPermit != null && this.totalFrames >= this.maxFrames) {
                  if (!this.pendingDecodedPtsNanos.isEmpty()) {
                     this.pendingDecodedPtsNanos.removeFirst();
                  }

                  frame.close();
               } else {
                  boolean enqueued = false;
                  boolean discardFrame = false;

                  try {
                     frame = frame.withNativeGetNanos(nativeGetNs);
                     Long fallbackPtsNanos = this.pendingDecodedPtsNanos.isEmpty() ? null : this.pendingDecodedPtsNanos.removeFirst();
                     long samplePtsNanos = this.decoder.lastFramePtsNanos();
                     if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
                        samplePtsNanos = fallbackPtsNanos;
                     }

                     long mediaPtsNanos = samplePtsNanos >= 0L
                        ? samplePtsNanos
                        : this.timelineStartNanos + Math.round((this.totalFrames + 1) * 1.0E9 / this.fps);
                     this.lastDecodedMediaPtsNanos = mediaPtsNanos;
                     if (this.shouldDropDecodedFrame(mediaPtsNanos, samplePtsNanos >= 0L)) {
                        this.droppedFrameCount++;
                        if (!this.dropStageLogged && this.droppedFrameCount <= 3) {
                           this.dropStageLogged = true;
                           LOGGER.debug(
                              "视频 native 输出帧被目标 PTS 丢弃: dropIndex={} samplePts={}ms target={}ms realPts={} pendingPts={}",
                              new Object[]{
                                 this.droppedFrameCount,
                                 samplePtsNanos / 1000000L,
                                 this.dropBeforeMediaPtsNanos / 1000000L,
                                 samplePtsNanos >= 0L,
                                 this.pendingDecodedPtsNanos.size()
                              }
                           );
                        }
                     } else {
                        frame = frame.withPtsNanos(Math.max(0L, mediaPtsNanos - this.startOffsetMillis * 1000000L));
                        Fmp4NativeVideoDecodePump.FrameOffer frameOffer = Fmp4NativeVideoDecodePump.FrameOffer.notOffered();

                        while (true) {
                           if (!this.closed.get()) {
                              try {
                                 frameOffer = this.offerDecodedFrame(frame, packetPermit, frameReadyNanos, 250L, TimeUnit.MILLISECONDS);
                                 if (frameOffer.discarded()) {
                                    discardFrame = true;
                                 } else {
                                    if (!frameOffer.offered()) {
                                       continue;
                                    }

                                    enqueued = true;
                                 }
                              } catch (InterruptedException var21) {
                                 Thread.currentThread().interrupt();
                                 if (!this.closed.get()) {
                                    throw new IOException("等待 AV1 首帧内部队列时被中断", var21);
                                 }

                                 return;
                              }
                           }

                           if (this.closed.get()) {
                              return;
                           }

                           if (discardFrame) {
                              break;
                           }

                           boolean provisionalProbeFrame = frameOffer.probeTicket() > 0L;
                           if (!provisionalProbeFrame) {
                              this.totalFrames++;
                           }

                           if (!this.awaitProbeFrameDecision(frameOffer.probeTicket())) {
                              return;
                           }

                           if (provisionalProbeFrame && this.activeFirstFrameProbe.decision() == Av1FirstFrameProbe.Decision.COMMITTED) {
                              this.totalFrames++;
                           }
                           break;
                        }
                     }
                  } finally {
                     if (!enqueued) {
                        frame.close();
                     }
                  }
               }
            }
         }
      }
   }

   private boolean drainDropFrameNoOutput(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
      boolean hasPendingRealPts = !this.pendingDecodedPtsNanos.isEmpty()
         && this.pendingDecodedPtsNanos.peekFirst() != null
         && this.pendingDecodedPtsNanos.peekFirst() >= 0L;
      boolean shouldDropByPts = this.dropBeforeMediaPtsNanos > 0L
         && hasPendingRealPts
         && this.pendingDecodedPtsNanos.peekFirst() + SAFE_NO_COPY_DROP_GUARD_NANOS + 1000000L < this.dropBeforeMediaPtsNanos;
      boolean shouldDropByFallback = this.fallbackFramesToDrop > 0 && !hasPendingRealPts;
      if (!shouldDropByPts && !shouldDropByFallback) {
         return false;
      } else if (!this.decoder.receiveFrameNoCopy()) {
         return false;
      } else {
         long frameReadyNanos = System.nanoTime();
         this.receivedFrameCount++;
         Long fallbackPtsNanos = this.pendingDecodedPtsNanos.isEmpty() ? null : this.pendingDecodedPtsNanos.removeFirst();
         long samplePtsNanos = this.decoder.lastFramePtsNanos();
         if (samplePtsNanos < 0L && fallbackPtsNanos != null) {
            samplePtsNanos = fallbackPtsNanos;
         }

         if (samplePtsNanos >= 0L) {
            this.lastDecodedMediaPtsNanos = samplePtsNanos;
            if (samplePtsNanos + 1000000L >= this.dropBeforeMediaPtsNanos) {
               this.dropBeforeMediaPtsNanos = 0L;
            }
         }

         if (shouldDropByFallback && this.fallbackFramesToDrop > 0) {
            this.fallbackFramesToDrop--;
         }

         this.acknowledgeDroppedProbeFrame(packetPermit, frameReadyNanos);
         this.droppedFrameCount++;
         if (!this.dropStageLogged && this.droppedFrameCount <= 3) {
            this.dropStageLogged = true;
            LOGGER.debug(
               "视频 native 无拷贝输出帧被目标 PTS 丢弃: dropIndex={} samplePts={}ms target={}ms realPts={} pendingPts={}",
               new Object[]{
                  this.droppedFrameCount,
                  samplePtsNanos / 1000000L,
                  this.dropBeforeMediaPtsNanos / 1000000L,
                  samplePtsNanos >= 0L,
                  this.pendingDecodedPtsNanos.size()
               }
            );
         }

         return true;
      }
   }

   private void acknowledgeDroppedProbeFrame(Av1FirstFrameProbe.PacketPermit packetPermit, long frameReadyNanos) throws IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe != null && packetPermit != null) {
         Av1FirstFrameProbe.FramePreparation preparation = probe.prepareFrame(packetPermit, frameReadyNanos);
         if (preparation.hasTicket()) {
            probe.reject(preparation.ticket());
         } else if (isProbeFailure(preparation.decision())) {
            throw this.firstFrameProbeFailure(preparation.decision(), probe, Math.max(0L, System.nanoTime() - probe.startedNanos()));
         }
      }
   }

   private boolean shouldDropDecodedFrame(long mediaPtsNanos, boolean hasRealPts) {
      if (!hasRealPts) {
         if (this.fallbackFramesToDrop > 0) {
            this.fallbackFramesToDrop--;
            return true;
         } else {
            return false;
         }
      } else {
         return this.dropBeforeMediaPtsNanos > 0L && mediaPtsNanos + 1000000L < this.dropBeforeMediaPtsNanos;
      }
   }

   private Fmp4NativeVideoDecoder.DecodedFrame getNextRgbaFrame() {
      if (!REUSE_OUTPUT_BUFFERS) {
         return Fmp4NativeVideoDecoder.DecodedFrame.wrap(this.decoder.getVideoFrame(), Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA);
      } else {
         byte[] buffer = this.reusableBuffers.poll();
         byte[] output = buffer != null ? buffer : new byte[Math.max(1, this.targetWidth) * Math.max(1, this.targetHeight) * 4];
         if (!this.decoder.getVideoFrameInto(output)) {
            this.reusableBuffers.offer(output);
            return null;
         } else {
            return new Fmp4NativeVideoDecoder.DecodedFrame(output, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA, () -> this.reusableBuffers.offer(output));
         }
      }
   }

   private Fmp4NativeVideoDecoder.DecodedFrame getNextYuv420Frame() {
      return Fmp4NativeVideoDecoder.DecodedFrame.wrap(this.decoder.getVideoFrameYuv420(), Fmp4NativeVideoDecoder.DecodedFrame.Format.YUV420P);
   }

   private Fmp4NativeVideoDecoder.DecodedFrame getNextNv12Frame() {
      if (DIRECT_NV12_BUFFERS) {
         int byteCount = Math.max(1, this.targetWidth) * Math.max(1, this.targetHeight) * 3 / 2;
         NativeNv12BufferPool.NativeNv12Buffer slot = this.nativeNv12Buffers.acquire(byteCount);
         if (slot == null) {
            return null;
         } else if (!this.decoder.getVideoFrameNv12Into(slot.buffer())) {
            this.nativeNv12Buffers.release(slot);
            return null;
         } else {
            return new Fmp4NativeVideoDecoder.DecodedFrame(
               slot.buffer(), byteCount, Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12, () -> this.nativeNv12Buffers.release(slot)
            );
         }
      } else {
         return Fmp4NativeVideoDecoder.DecodedFrame.wrap(this.decoder.getVideoFrameNv12(), Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12);
      }
   }

   private Fmp4NativeVideoDecoder.DecodedFrame getNextOutputFrame() {
      long startedNs = System.nanoTime();
      if (!this.outputStageLogged) {
         LOGGER.debug(
            "视频 native 开始取输出帧: format={} target={}x{} received={} dropped={} sent={}",
            new Object[]{this.outputFormat, this.targetWidth, this.targetHeight, this.receivedFrameCount, this.droppedFrameCount, this.sentPacketCount}
         );
      }
      Fmp4NativeVideoDecoder.DecodedFrame frame = switch (this.outputFormat) {
         case NV12 -> this.getNextNv12Frame();
         case YUV420P -> this.getNextYuv420Frame();
         case RGBA -> this.getNextRgbaFrame();
      };
      if (!this.outputStageLogged) {
         this.outputStageLogged = true;
         LOGGER.debug(
            "视频 native 完成取输出帧: frame={} elapsed={}ms received={} dropped={} sent={}",
            new Object[]{frame != null, (System.nanoTime() - startedNs) / 1000000L, this.receivedFrameCount, this.droppedFrameCount, this.sentPacketCount}
         );
      }

      return frame;
   }

   private void drainFramesNoOutput(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
      while (!this.closed.get() && (packetPermit != null || this.totalFrames < this.maxFrames)) {
         if (!this.decoder.receiveFrameNoCopy()) {
            this.finishProbePacketDrain(packetPermit);
            return;
         }

         long frameReadyNanos = System.nanoTime();
         if (packetPermit == null || this.totalFrames < this.maxFrames) {
            Fmp4NativeVideoDecodePump.FrameOffer frameOffer = Fmp4NativeVideoDecodePump.FrameOffer.notOffered();
            boolean discardFrame = false;

            while (!this.closed.get()) {
               try {
                  frameOffer = this.offerDecodedFrame(
                     Fmp4NativeVideoDecoder.DecodedFrame.wrap(DECODE_ONLY_FRAME, Fmp4NativeVideoDecoder.DecodedFrame.Format.RGBA),
                     packetPermit,
                     frameReadyNanos,
                     250L,
                     TimeUnit.MILLISECONDS
                  );
                  if (frameOffer.discarded()) {
                     discardFrame = true;
                     break;
                  }

                  if (frameOffer.offered()) {
                     break;
                  }
               } catch (InterruptedException var7) {
                  Thread.currentThread().interrupt();
                  if (this.closed.get()) {
                     return;
                  }

                  throw new IOException("等待 AV1 首帧内部队列时被中断", var7);
               }
            }

            if (this.closed.get()) {
               return;
            }

            if (!discardFrame) {
               boolean provisionalProbeFrame = frameOffer.probeTicket() > 0L;
               if (!provisionalProbeFrame) {
                  this.totalFrames++;
               }

               if (!this.awaitProbeFrameDecision(frameOffer.probeTicket())) {
                  return;
               }

               if (provisionalProbeFrame && this.activeFirstFrameProbe.decision() == Av1FirstFrameProbe.Decision.COMMITTED) {
                  this.totalFrames++;
               }
            }
         } else if (!this.pendingDecodedPtsNanos.isEmpty()) {
            this.pendingDecodedPtsNanos.removeFirst();
         }
      }
   }

   private Fmp4NativeVideoDecodePump.FrameOffer offerDecodedFrame(
      Fmp4NativeVideoDecoder.DecodedFrame frame, Av1FirstFrameProbe.PacketPermit packetPermit, long frameReadyNanos, long timeout, TimeUnit unit
   ) throws InterruptedException, IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      long ticket = -1L;
      long probeElapsedNanos = -1L;
      if (probe != null) {
         Av1FirstFrameProbe.FramePreparation preparation = probe.prepareFrame(packetPermit, frameReadyNanos);
         probeElapsedNanos = probe.pendingFrameElapsedNanos();
         if (isProbeFailure(preparation.decision())) {
            throw this.firstFrameProbeFailure(preparation.decision(), probe, Math.max(0L, System.nanoTime() - probe.startedNanos()));
         }

         if (preparation.decision() == Av1FirstFrameProbe.Decision.CANCELLED) {
            return Fmp4NativeVideoDecodePump.FrameOffer.notOffered();
         }

         if (preparation.decision() == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT) {
            return Fmp4NativeVideoDecodePump.FrameOffer.discardedFrame();
         }

         if (preparation.hasTicket()) {
            ticket = preparation.ticket();
         } else {
            probeElapsedNanos = -1L;
         }
      }

      boolean offered = false;

      Fmp4NativeVideoDecodePump.FrameOffer var14;
      try {
         offered = this.frames.offer(new Fmp4NativeVideoDecodePump.QueuedDecodedFrame(frame, probeElapsedNanos, ticket), timeout, unit);
         var14 = new Fmp4NativeVideoDecodePump.FrameOffer(offered, false, offered ? ticket : -1L);
      } finally {
         if (!offered && probe != null && ticket > 0L) {
            probe.cancelPreparedFrame(ticket);
         }
      }

      return var14;
   }

   private boolean awaitProbeFrameDecision(long ticket) throws IOException {
      if (ticket <= 0L) {
         return !this.closed.get();
      } else {
         Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
         if (probe == null) {
            throw new IOException("AV1 首帧探测 ticket 已丢失: ticket=" + ticket);
         } else {
            Av1FirstFrameProbe.Decision decision;
            try {
               decision = probe.awaitFrameDecision(ticket, this.closed);
            } catch (InterruptedException var7) {
               Thread.currentThread().interrupt();
               if (this.closed.get()) {
                  return false;
               }

               throw new IOException("等待 AV1 首帧候选确认时被中断", var7);
            }

            if (decision == Av1FirstFrameProbe.Decision.COMMITTED
               || decision == Av1FirstFrameProbe.Decision.CONTINUE
               || decision == Av1FirstFrameProbe.Decision.DRAIN_IN_FLIGHT) {
               return !this.closed.get();
            } else if (decision != Av1FirstFrameProbe.Decision.CANCELLED && !this.closed.get()) {
               long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
               if (isProbeFailure(decision)) {
                  throw this.firstFrameProbeFailure(decision, probe, elapsedNanos);
               } else {
                  throw new IOException("AV1 首帧探测未能完成 ticket 决策: ticket=" + ticket + ", decision=" + decision);
               }
            } else {
               return false;
            }
         }
      }
   }

   private void finishProbePacketDrain(Av1FirstFrameProbe.PacketPermit packetPermit) throws IOException {
      Av1FirstFrameProbe probe = this.activeFirstFrameProbe;
      if (probe != null && packetPermit != null) {
         long elapsedNanos = Math.max(0L, System.nanoTime() - probe.startedNanos());
         Av1FirstFrameProbe.PacketTransition transition = probe.endPacket(packetPermit, Av1FirstFrameProbe.PacketEnd.DRAINED);
         if (!transition.applied()) {
            throw new IOException("AV1 首帧探测未能完成 packet drain: ordinal=" + packetPermit.ordinal() + ", decision=" + transition.decision());
         } else {
            Av1FirstFrameProbe.Decision decision = transition.decision();
            if (isProbeFailure(decision)) {
               throw this.firstFrameProbeFailure(decision, probe, elapsedNanos);
            } else if (decision == Av1FirstFrameProbe.Decision.FRAME_PENDING) {
               throw new IOException("AV1 首帧探测在 packet drain 边界仍有未确认帧");
            }
         }
      }
   }

   private record FrameOffer(boolean offered, boolean discarded, long probeTicket) {
      private static Fmp4NativeVideoDecodePump.FrameOffer notOffered() {
         return new Fmp4NativeVideoDecodePump.FrameOffer(false, false, -1L);
      }

      private static Fmp4NativeVideoDecodePump.FrameOffer discardedFrame() {
         return new Fmp4NativeVideoDecodePump.FrameOffer(false, true, -1L);
      }
   }

   private record QueuedDecodedFrame(Fmp4NativeVideoDecoder.DecodedFrame frame, long probeElapsedNanos, long probeTicket) {
   }
}
