package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class TerrainPreviewRenderDiagnostics {
   private static final AtomicLong MATERIAL_SECTION_UPLOADS = new AtomicLong();
   private static final AtomicLong TRANSLUCENT_SECTION_UPLOADS = new AtomicLong();
   private static final AtomicLong TRANSLUCENT_RESORTS = new AtomicLong();
   private static final AtomicLong BLOCK_ENTITY_SUBMISSIONS = new AtomicLong();
   private static final AtomicLong FAILURES = new AtomicLong();

   private TerrainPreviewRenderDiagnostics() {
   }

   static void recordSectionUpload(boolean materialLod, boolean translucent) {
      if (materialLod) {
         MATERIAL_SECTION_UPLOADS.incrementAndGet();
      }

      if (translucent) {
         TRANSLUCENT_SECTION_UPLOADS.incrementAndGet();
      }
   }

   static void recordTranslucentResort() {
      TRANSLUCENT_RESORTS.incrementAndGet();
   }

   static void recordBlockEntitySubmission() {
      BLOCK_ENTITY_SUBMISSIONS.incrementAndGet();
   }

   static void recordFailure() {
      FAILURES.incrementAndGet();
   }

   public static TerrainPreviewRenderDiagnostics.Snapshot snapshot() {
      return new TerrainPreviewRenderDiagnostics.Snapshot(
         MATERIAL_SECTION_UPLOADS.get(), TRANSLUCENT_SECTION_UPLOADS.get(), TRANSLUCENT_RESORTS.get(), BLOCK_ENTITY_SUBMISSIONS.get(), FAILURES.get()
      );
   }

   public record Snapshot(long materialSectionUploads, long translucentSectionUploads, long translucentResorts, long blockEntitySubmissions, long failures) {
      public Snapshot(long materialSectionUploads, long translucentSectionUploads, long translucentResorts, long blockEntitySubmissions, long failures) {
         if (materialSectionUploads >= 0L && translucentSectionUploads >= 0L && translucentResorts >= 0L && blockEntitySubmissions >= 0L && failures >= 0L) {
            this.materialSectionUploads = materialSectionUploads;
            this.translucentSectionUploads = translucentSectionUploads;
            this.translucentResorts = translucentResorts;
            this.blockEntitySubmissions = blockEntitySubmissions;
            this.failures = failures;
         } else {
            throw new IllegalArgumentException("terrain render diagnostic counters must be non-negative");
         }
      }

      public TerrainPreviewRenderDiagnostics.Snapshot deltaFrom(TerrainPreviewRenderDiagnostics.Snapshot baseline) {
         Objects.requireNonNull(baseline, "baseline");
         return new TerrainPreviewRenderDiagnostics.Snapshot(
            delta(this.materialSectionUploads, baseline.materialSectionUploads),
            delta(this.translucentSectionUploads, baseline.translucentSectionUploads),
            delta(this.translucentResorts, baseline.translucentResorts),
            delta(this.blockEntitySubmissions, baseline.blockEntitySubmissions),
            delta(this.failures, baseline.failures)
         );
      }

      private static long delta(long current, long baseline) {
         return Math.max(0L, current - baseline);
      }
   }
}
