package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSurfaceMesh;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TerrainPreviewFrame(
   long generation,
   int originX,
   int originY,
   int originZ,
   double coreCenterX,
   double coreCenterY,
   double coreCenterZ,
   TerrainBounds bounds,
   List<TerrainOverviewCell> overviewCells,
   List<TerrainWireframeMesher.Segment> wireframeSegments,
   List<TerrainSurfaceMesh> highDetailMeshes,
   List<TerrainBlockSectionSnapshot> fullDetailSections,
   Set<TerrainSectionKey> fullDetailSectionKeys,
   Set<TerrainSectionKey> removedSections,
   List<TerrainBlockEntityPreview> blockEntities,
   int pendingSections,
   int sampledSections
) {
   private static final TerrainPreviewFrame EMPTY = new TerrainPreviewFrame(
      0L, 0, 0, 0, 0.0, 0.0, 0.0, new TerrainBounds(0, 0, 0, 0, 0, 0), List.of(), List.of(), List.of(), List.of(), Set.of(), Set.of(), List.of(), 0, 0
   );

   public TerrainPreviewFrame(
      long generation,
      int originX,
      int originY,
      int originZ,
      double coreCenterX,
      double coreCenterY,
      double coreCenterZ,
      TerrainBounds bounds,
      List<TerrainOverviewCell> overviewCells,
      List<TerrainWireframeMesher.Segment> wireframeSegments,
      List<TerrainSurfaceMesh> highDetailMeshes,
      List<TerrainBlockSectionSnapshot> fullDetailSections,
      Set<TerrainSectionKey> fullDetailSectionKeys,
      Set<TerrainSectionKey> removedSections,
      int pendingSections,
      int sampledSections
   ) {
      this(
         generation,
         originX,
         originY,
         originZ,
         coreCenterX,
         coreCenterY,
         coreCenterZ,
         bounds,
         overviewCells,
         wireframeSegments,
         highDetailMeshes,
         fullDetailSections,
         fullDetailSectionKeys,
         removedSections,
         List.of(),
         pendingSections,
         sampledSections
      );
   }

   public TerrainPreviewFrame(
      long generation,
      int originX,
      int originY,
      int originZ,
      double coreCenterX,
      double coreCenterY,
      double coreCenterZ,
      TerrainBounds bounds,
      List<TerrainOverviewCell> overviewCells,
      List<TerrainWireframeMesher.Segment> wireframeSegments,
      List<TerrainSurfaceMesh> highDetailMeshes,
      List<TerrainBlockSectionSnapshot> fullDetailSections,
      Set<TerrainSectionKey> fullDetailSectionKeys,
      Set<TerrainSectionKey> removedSections,
      List<TerrainBlockEntityPreview> blockEntities,
      int pendingSections,
      int sampledSections
   ) {
      Objects.requireNonNull(bounds, "bounds");
      if (Double.isFinite(coreCenterX) && Double.isFinite(coreCenterY) && Double.isFinite(coreCenterZ)) {
         overviewCells = List.copyOf(Objects.requireNonNull(overviewCells, "overviewCells"));
         wireframeSegments = List.copyOf(Objects.requireNonNull(wireframeSegments, "wireframeSegments"));
         highDetailMeshes = List.copyOf(Objects.requireNonNull(highDetailMeshes, "highDetailMeshes"));
         fullDetailSections = List.copyOf(Objects.requireNonNull(fullDetailSections, "fullDetailSections"));
         fullDetailSectionKeys = Set.copyOf(Objects.requireNonNull(fullDetailSectionKeys, "fullDetailSectionKeys"));
         removedSections = Set.copyOf(Objects.requireNonNull(removedSections, "removedSections"));
         blockEntities = List.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
         if (generation >= 0L && pendingSections >= 0 && sampledSections >= 0) {
            this.generation = generation;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.coreCenterX = coreCenterX;
            this.coreCenterY = coreCenterY;
            this.coreCenterZ = coreCenterZ;
            this.bounds = bounds;
            this.overviewCells = overviewCells;
            this.wireframeSegments = wireframeSegments;
            this.highDetailMeshes = highDetailMeshes;
            this.fullDetailSections = fullDetailSections;
            this.fullDetailSectionKeys = fullDetailSectionKeys;
            this.removedSections = removedSections;
            this.blockEntities = blockEntities;
            this.pendingSections = pendingSections;
            this.sampledSections = sampledSections;
         } else {
            throw new IllegalArgumentException("terrain frame counters must be non-negative");
         }
      } else {
         throw new IllegalArgumentException("terrain core center must be finite");
      }
   }

   public static TerrainPreviewFrame empty() {
      return EMPTY;
   }
}
