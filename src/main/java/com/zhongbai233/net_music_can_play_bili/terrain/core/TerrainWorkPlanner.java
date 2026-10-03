package com.zhongbai233.net_music_can_play_bili.terrain.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TerrainWorkPlanner {
   private final int maxPending;
   private final Map<TerrainSectionKey, TerrainWorkPlanner.Request> requests = new HashMap<>();
   private TerrainSectionKey cameraSection = new TerrainSectionKey(0, 0, 0);
   private TerrainSectionKey selectedSection;

   public TerrainWorkPlanner(int maxPending) {
      if (maxPending <= 0) {
         throw new IllegalArgumentException("maxPending must be positive");
      } else {
         this.maxPending = maxPending;
      }
   }

   public synchronized void updateCamera(TerrainSectionKey section) {
      this.cameraSection = Objects.requireNonNull(section, "section");
   }

   public synchronized void updateSelected(TerrainSectionKey section) {
      this.selectedSection = section;
   }

   public synchronized void updateCoverage(TerrainBounds bounds, TerrainLodLevel lod) {
      Objects.requireNonNull(bounds, "bounds");
      Objects.requireNonNull(lod, "lod");
      int minX = Math.floorDiv(bounds.minX(), 16);
      int minY = Math.floorDiv(bounds.minY(), 16);
      int minZ = Math.floorDiv(bounds.minZ(), 16);
      int maxX = Math.floorDiv(bounds.maxX(), 16);
      int maxY = Math.floorDiv(bounds.maxY(), 16);
      int maxZ = Math.floorDiv(bounds.maxZ(), 16);

      for (int y = minY; y <= maxY; y++) {
         for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
               this.enqueue(new TerrainSectionKey(x, y, z), lod, TerrainWorkPriority.FAR);
            }
         }
      }
   }

   public synchronized void markDirty(TerrainSectionKey section, TerrainLodLevel lod) {
      this.enqueue(section, lod, TerrainWorkPriority.DIRTY_VISIBLE);
   }

   public synchronized List<TerrainWorkPlanner.WorkItem> nextWork(int limit) {
      if (limit > 0 && !this.requests.isEmpty()) {
         List<TerrainWorkPlanner.Request> items = new ArrayList<>(this.requests.values());
         items.sort(
            Comparator.<TerrainWorkPlanner.Request>comparingInt(requestx -> this.effectivePriority(requestx).ordinal())
               .thenComparingInt(requestx -> this.distance(requestx.section()))
         );
         int count = Math.min(limit, items.size());
         List<TerrainWorkPlanner.WorkItem> result = new ArrayList<>(count);

         for (int i = 0; i < count; i++) {
            TerrainWorkPlanner.Request request = items.get(i);
            this.requests.remove(request.section());
            result.add(new TerrainWorkPlanner.WorkItem(request.section(), request.lod(), this.effectivePriority(request)));
         }

         return List.copyOf(result);
      } else {
         return List.of();
      }
   }

   public synchronized int pending() {
      return this.requests.size();
   }

   public synchronized void clear() {
      this.requests.clear();
   }

   private void enqueue(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
      Objects.requireNonNull(section, "section");
      Objects.requireNonNull(lod, "lod");
      TerrainWorkPlanner.Request old = this.requests.get(section);
      if (old == null || old.priority().ordinal() > priority.ordinal()) {
         if (old != null || this.requests.size() < this.maxPending) {
            this.requests.put(section, new TerrainWorkPlanner.Request(section, lod, priority));
         }
      }
   }

   private TerrainWorkPriority effectivePriority(TerrainWorkPlanner.Request request) {
      if (this.selectedSection != null && this.selectedSection.equals(request.section())) {
         return TerrainWorkPriority.SELECTED_NEAR;
      } else {
         int distance = this.distance(request.section());
         return distance <= 1 ? TerrainWorkPriority.CAMERA_NEAR : request.priority();
      }
   }

   private int distance(TerrainSectionKey section) {
      return Math.abs(section.x() - this.cameraSection.x()) + Math.abs(section.y() - this.cameraSection.y()) + Math.abs(section.z() - this.cameraSection.z());
   }

   private record Request(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
   }

   public record WorkItem(TerrainSectionKey section, TerrainLodLevel lod, TerrainWorkPriority priority) {
   }
}
