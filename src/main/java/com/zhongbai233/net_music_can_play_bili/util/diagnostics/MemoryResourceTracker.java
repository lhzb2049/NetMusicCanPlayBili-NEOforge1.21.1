package com.zhongbai233.net_music_can_play_bili.util.diagnostics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryResourceTracker {
   private static final MemoryProperties.Flags PROPERTIES = MemoryProperties.flags();
   private static final boolean DIAGNOSTICS_ENABLED = PROPERTIES.diagnosticsEnabled();
   private static final boolean PROTECTION_ENABLED = PROPERTIES.protectionEnabled();
   private static final boolean TRACKING_ENABLED = DIAGNOSTICS_ENABLED || PROTECTION_ENABLED;
   private static final Map<MemoryResourceTracker.Category, MemoryResourceTracker.Counter> COUNTERS = new EnumMap<>(MemoryResourceTracker.Category.class);
   private static final MemoryResourceTracker.Usage EMPTY_USAGE = new MemoryResourceTracker.Usage(0L, 0L, 0L, 0L);

   private MemoryResourceTracker() {
   }

   public static boolean enabled() {
      return DIAGNOSTICS_ENABLED;
   }

   public static boolean trackingEnabled() {
      return TRACKING_ENABLED;
   }

   public static void allocated(MemoryResourceTracker.Category category, long bytes) {
      if (TRACKING_ENABLED && bytes > 0L) {
         MemoryResourceTracker.Counter counter = COUNTERS.get(category);
         long current = counter.current.addAndGet(bytes);
         counter.allocations.incrementAndGet();
         counter.peak.accumulateAndGet(current, Math::max);
      }
   }

   public static void freed(MemoryResourceTracker.Category category, long bytes) {
      if (TRACKING_ENABLED && bytes > 0L) {
         MemoryResourceTracker.Counter counter = COUNTERS.get(category);
         long current = counter.current.addAndGet(-bytes);
         counter.frees.incrementAndGet();
         if (current < 0L) {
            counter.current.compareAndSet(current, 0L);
         }
      }
   }

   public static MemoryResourceTracker.Usage usage(MemoryResourceTracker.Category category) {
      if (!TRACKING_ENABLED) {
         return EMPTY_USAGE;
      } else {
         MemoryResourceTracker.Counter counter = COUNTERS.get(category);
         return new MemoryResourceTracker.Usage(counter.current.get(), counter.peak.get(), counter.allocations.get(), counter.frees.get());
      }
   }

   static {
      if (TRACKING_ENABLED) {
         for (MemoryResourceTracker.Category category : MemoryResourceTracker.Category.values()) {
            COUNTERS.put(category, new MemoryResourceTracker.Counter());
         }
      }
   }

   public static enum Category {
      DECODER_NV12("decoderNv12"),
      TEXTURE_STAGING("textureStaging"),
      AUDIO_STAGING("audioStaging"),
      GPU_PBO("gpuPbo");

      private final String label;

      private Category(String label) {
         this.label = label;
      }

      public String label() {
         return this.label;
      }
   }

   private static final class Counter {
      private final AtomicLong current = new AtomicLong();
      private final AtomicLong peak = new AtomicLong();
      private final AtomicLong allocations = new AtomicLong();
      private final AtomicLong frees = new AtomicLong();
   }

   public record Usage(long currentBytes, long peakBytes, long allocations, long frees) {
   }
}
