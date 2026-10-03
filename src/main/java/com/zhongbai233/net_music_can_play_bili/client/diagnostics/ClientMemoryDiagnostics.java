package com.zhongbai233.net_music_can_play_bili.client.diagnostics;

import com.mojang.logging.LogUtils;
import com.sun.management.OperatingSystemMXBean;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.media.codec.VideoNativeDecoder;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryProperties;
import com.zhongbai233.net_music_can_play_bili.util.diagnostics.MemoryResourceTracker;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import org.slf4j.Logger;

public final class ClientMemoryDiagnostics {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final boolean ENABLED = MemoryResourceTracker.enabled();
   private static final long REPORT_INTERVAL_NANOS = MemoryProperties.reportIntervalNanos();
   private static volatile long nextReportNanoTime;

   private ClientMemoryDiagnostics() {
   }

   public static void tick() {
      if (ENABLED) {
         long now = System.nanoTime();
         if (now >= nextReportNanoTime) {
            nextReportNanoTime = saturatedAdd(now, REPORT_INTERVAL_NANOS);
            report("periodic");
         }
      }
   }

   public static void report(String reason) {
      if (ENABLED) {
         MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
         MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
         ClientMemoryDiagnostics.BufferUsage direct = bufferUsage("direct");
         ClientMemoryDiagnostics.BufferUsage mapped = bufferUsage("mapped");
         long committedVirtual = committedVirtualMemory();
         long gcCount = 0L;
         long gcMillis = 0L;

         for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0L, gc.getCollectionCount());
            gcMillis += Math.max(0L, gc.getCollectionTime());
         }

         MemoryResourceTracker.Usage decoder = MemoryResourceTracker.usage(MemoryResourceTracker.Category.DECODER_NV12);
         MemoryResourceTracker.Usage staging = MemoryResourceTracker.usage(MemoryResourceTracker.Category.TEXTURE_STAGING);
         MemoryResourceTracker.Usage audio = MemoryResourceTracker.usage(MemoryResourceTracker.Category.AUDIO_STAGING);
         MemoryResourceTracker.Usage pbo = MemoryResourceTracker.usage(MemoryResourceTracker.Category.GPU_PBO);
         long ownedNative = decoder.currentBytes() + staging.currentBytes() + audio.currentBytes();
         long ownedNativePeak = decoder.peakBytes() + staging.peakBytes() + audio.peakBytes();
         LOGGER.info(
            "NCPB内存[{}]: heap={}/{}/{} nonHeap={}/{} direct={}/{}({}) mapped={}/{}({}) processCommit={} threads={} gc={}/{}ms",
            new Object[]{
               reason,
               mib(heap.getUsed()),
               mib(heap.getCommitted()),
               mib(heap.getMax()),
               mib(nonHeap.getUsed()),
               mib(nonHeap.getCommitted()),
               mib(direct.memoryUsed()),
               mib(direct.capacity()),
               direct.count(),
               mib(mapped.memoryUsed()),
               mib(mapped.capacity()),
               mapped.count(),
               mib(committedVirtual),
               ManagementFactory.getThreadMXBean().getThreadCount(),
               gcCount,
               gcMillis
            }
         );
         LOGGER.info(
            "NCPB自有内存: nativeExact={} peakSum={} [decoderNv12={}/{} staging={}/{} audio={}/{}] gpuPboEstimate={}/{}; 不含普通GPU纹理/FBO、OpenAL驱动缓冲，数值不可与processCommit直接相加",
            new Object[]{
               mib(ownedNative),
               mib(ownedNativePeak),
               mib(decoder.currentBytes()),
               mib(decoder.peakBytes()),
               mib(staging.currentBytes()),
               mib(staging.peakBytes()),
               mib(audio.currentBytes()),
               mib(audio.peakBytes()),
               mib(pbo.currentBytes()),
               mib(pbo.peakBytes())
            }
         );
         VideoNativeDecoder.NativeMemoryStats nativeStats = VideoNativeDecoder.nativeMemoryStats();
         if (nativeStats.available()) {
            LOGGER.info(
               "NCPB FFmpeg内存: avHeap={}/{} alloc/realloc/free={}/{}/{} d3d11Textures={}/{} d3d11Surfaces={}/{} d3d11LogicalEstimate={}/{}; D3D11逻辑容量不等于实际显存或进程提交",
               new Object[]{
                  mib(nativeStats.ffmpegCurrentBytes()),
                  mib(nativeStats.ffmpegPeakBytes()),
                  nativeStats.allocations(),
                  nativeStats.reallocations(),
                  nativeStats.frees(),
                  nativeStats.d3d11TextureCurrent(),
                  nativeStats.d3d11TexturePeak(),
                  nativeStats.d3d11SurfaceCurrent(),
                  nativeStats.d3d11SurfacePeak(),
                  mib(nativeStats.d3d11LogicalBytesCurrent()),
                  mib(nativeStats.d3d11LogicalBytesPeak())
               }
            );
         }

         for (String line : ModernTurntableVideoClient.describeVideoLifecycle()) {
            LOGGER.info("NCPB视频生命周期: {}", line);
         }
      }
   }

   private static ClientMemoryDiagnostics.BufferUsage bufferUsage(String requestedName) {
      long count = 0L;
      long memoryUsed = 0L;
      long capacity = 0L;

      for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
         if (pool.getName().toLowerCase(Locale.ROOT).startsWith(requestedName)) {
            count += Math.max(0L, pool.getCount());
            memoryUsed += Math.max(0L, pool.getMemoryUsed());
            capacity += Math.max(0L, pool.getTotalCapacity());
         }
      }

      return new ClientMemoryDiagnostics.BufferUsage(count, memoryUsed, capacity);
   }

   private static long committedVirtualMemory() {
      return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean extended
         ? Math.max(0L, extended.getCommittedVirtualMemorySize())
         : -1L;
   }

   private static String mib(long bytes) {
      return bytes < 0L ? "n/a" : String.format(Locale.ROOT, "%.1fMiB", bytes / 1048576.0);
   }

   private static long saturatedAdd(long left, long right) {
      return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   private record BufferUsage(long count, long memoryUsed, long capacity) {
   }
}
