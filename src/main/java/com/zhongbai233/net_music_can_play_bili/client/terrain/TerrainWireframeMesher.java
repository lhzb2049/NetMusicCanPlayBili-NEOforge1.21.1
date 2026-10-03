package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;

public final class TerrainWireframeMesher {
   private static final int FACE_PATCH_SIZE = 4;
   private static final int SECTION_SIZE = 16;

   private TerrainWireframeMesher() {
   }

   public static List<TerrainWireframeMesher.Segment> mesh(List<TerrainOverviewCell> cells, TerrainBounds bounds) {
      Objects.requireNonNull(cells, "cells");
      Objects.requireNonNull(bounds, "bounds");
      Map<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> boxes = new HashMap<>();

      for (TerrainOverviewCell cell : cells) {
         int x0 = Math.max(cell.worldX(), bounds.minX());
         int y0 = Math.max(cell.worldY(), bounds.minY());
         int z0 = Math.max(cell.worldZ(), bounds.minZ());
         int x1 = Math.min(cell.worldX() + cell.size(), bounds.maxX() + 1);
         int y1 = Math.min(cell.worldY() + cell.size(), bounds.maxY() + 1);
         int z1 = Math.min(cell.worldZ() + cell.size(), bounds.maxZ() + 1);
         if (x1 > x0 && y1 > y0 && z1 > z0) {
            boxes.computeIfAbsent(new TerrainWireframeMesher.BoxKey(x0, y0, z0, x1, y1, z1), ignored -> new TerrainWireframeMesher.BoxAccumulator())
               .add(cell.material(), cell.color());
         }
      }

      Map<TerrainWireframeMesher.LineKey, List<TerrainWireframeMesher.Interval>> intervals = new HashMap<>();
      addExposedFaceEdges(boxes, intervals);
      addExposedFaceEdges(sectionBoxes(boxes, bounds), intervals);
      List<TerrainWireframeMesher.LineKey> lines = new ArrayList<>(intervals.keySet());
      lines.sort(
         Comparator.<TerrainWireframeMesher.LineKey>comparingInt(linex -> linex.axis())
            .thenComparingInt(linex -> linex.fixedA())
            .thenComparingInt(linex -> linex.fixedB())
      );
      List<TerrainWireframeMesher.Segment> result = new ArrayList<>();

      for (TerrainWireframeMesher.LineKey line : lines) {
         List<TerrainWireframeMesher.Interval> values = intervals.get(line);
         values.sort(Comparator.<TerrainWireframeMesher.Interval>comparingInt(intervalx -> intervalx.start()).thenComparingInt(intervalx -> intervalx.end()));
         int start = values.getFirst().start();
         int end = values.getFirst().end();
         TerrainWireframeMesher.LineAccumulator accumulator = new TerrainWireframeMesher.LineAccumulator();
         accumulator.add(values.getFirst());

         for (int index = 1; index < values.size(); index++) {
            TerrainWireframeMesher.Interval interval = values.get(index);
            if (interval.start() <= end) {
               end = Math.max(end, interval.end());
               accumulator.add(interval);
            } else {
               result.add(line.segment(start, end, accumulator.material(), accumulator.color()));
               start = interval.start();
               end = interval.end();
               accumulator = new TerrainWireframeMesher.LineAccumulator();
               accumulator.add(interval);
            }
         }

         result.add(line.segment(start, end, accumulator.material(), accumulator.color()));
      }

      return List.copyOf(result);
   }

   private static Map<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> sectionBoxes(
      Map<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> macroBoxes, TerrainBounds bounds
   ) {
      Map<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> sections = new HashMap<>();

      for (Entry<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> entry : macroBoxes.entrySet()) {
         TerrainWireframeMesher.BoxKey box = entry.getKey();
         TerrainWireframeMesher.BoxStyle style = entry.getValue().style();
         int firstX = Math.floorDiv(box.x0(), 16) * 16;
         int firstY = Math.floorDiv(box.y0(), 16) * 16;
         int firstZ = Math.floorDiv(box.z0(), 16) * 16;
         int lastX = Math.floorDiv(box.x1() - 1, 16) * 16;
         int lastY = Math.floorDiv(box.y1() - 1, 16) * 16;
         int lastZ = Math.floorDiv(box.z1() - 1, 16) * 16;

         for (int sectionY = firstY; sectionY <= lastY; sectionY += 16) {
            for (int sectionZ = firstZ; sectionZ <= lastZ; sectionZ += 16) {
               for (int sectionX = firstX; sectionX <= lastX; sectionX += 16) {
                  int x0 = Math.max(sectionX, bounds.minX());
                  int y0 = Math.max(sectionY, bounds.minY());
                  int z0 = Math.max(sectionZ, bounds.minZ());
                  int x1 = Math.min(sectionX + 16, bounds.maxX() + 1);
                  int y1 = Math.min(sectionY + 16, bounds.maxY() + 1);
                  int z1 = Math.min(sectionZ + 16, bounds.maxZ() + 1);
                  if (x1 > x0 && y1 > y0 && z1 > z0) {
                     sections.computeIfAbsent(new TerrainWireframeMesher.BoxKey(x0, y0, z0, x1, y1, z1), ignored -> new TerrainWireframeMesher.BoxAccumulator())
                        .add(style.material(), style.color());
                  }
               }
            }
         }
      }

      return sections;
   }

   private static void addExposedFaceEdges(
      Map<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> boxes,
      Map<TerrainWireframeMesher.LineKey, List<TerrainWireframeMesher.Interval>> intervals
   ) {
      List<TerrainWireframeMesher.Face> faces = new ArrayList<>(boxes.size() * 6);
      Map<TerrainWireframeMesher.FaceKey, TerrainWireframeMesher.FaceOccupancy> patches = new HashMap<>();

      for (Entry<TerrainWireframeMesher.BoxKey, TerrainWireframeMesher.BoxAccumulator> entry : boxes.entrySet()) {
         TerrainWireframeMesher.BoxKey box = entry.getKey();
         TerrainWireframeMesher.BoxStyle style = entry.getValue().style();
         addFace(faces, patches, new TerrainWireframeMesher.Face(0, box.x0(), box.y0(), box.y1(), box.z0(), box.z1(), 1, style));
         addFace(faces, patches, new TerrainWireframeMesher.Face(0, box.x1(), box.y0(), box.y1(), box.z0(), box.z1(), -1, style));
         addFace(faces, patches, new TerrainWireframeMesher.Face(1, box.y0(), box.x0(), box.x1(), box.z0(), box.z1(), 1, style));
         addFace(faces, patches, new TerrainWireframeMesher.Face(1, box.y1(), box.x0(), box.x1(), box.z0(), box.z1(), -1, style));
         addFace(faces, patches, new TerrainWireframeMesher.Face(2, box.z0(), box.x0(), box.x1(), box.y0(), box.y1(), 1, style));
         addFace(faces, patches, new TerrainWireframeMesher.Face(2, box.z1(), box.x0(), box.x1(), box.y0(), box.y1(), -1, style));
      }

      for (TerrainWireframeMesher.Face face : faces) {
         Set<TerrainWireframeMesher.EdgeKey> boundary = new HashSet<>();

         for (TerrainWireframeMesher.FaceKey patch : split(face)) {
            TerrainWireframeMesher.FaceOccupancy occupancy = patches.get(patch);
            if (occupancy != null && !occupancy.hasOpposite(face.side())) {
               toggleFaceEdges(boundary, patch);
            }
         }

         for (TerrainWireframeMesher.EdgeKey edge : boundary) {
            add(intervals, edge.line(), edge.start(), edge.end(), face.style());
         }
      }
   }

   private static void addFace(
      List<TerrainWireframeMesher.Face> faces,
      Map<TerrainWireframeMesher.FaceKey, TerrainWireframeMesher.FaceOccupancy> patches,
      TerrainWireframeMesher.Face face
   ) {
      faces.add(face);

      for (TerrainWireframeMesher.FaceKey patch : split(face)) {
         patches.computeIfAbsent(patch, ignored -> new TerrainWireframeMesher.FaceOccupancy()).add(face.side());
      }
   }

   private static List<TerrainWireframeMesher.FaceKey> split(TerrainWireframeMesher.Face face) {
      List<Integer> aCuts = cuts(face.a0(), face.a1());
      List<Integer> bCuts = cuts(face.b0(), face.b1());
      List<TerrainWireframeMesher.FaceKey> patches = new ArrayList<>((aCuts.size() - 1) * (bCuts.size() - 1));

      for (int a = 0; a + 1 < aCuts.size(); a++) {
         for (int b = 0; b + 1 < bCuts.size(); b++) {
            patches.add(new TerrainWireframeMesher.FaceKey(face.axis(), face.plane(), aCuts.get(a), aCuts.get(a + 1), bCuts.get(b), bCuts.get(b + 1)));
         }
      }

      return patches;
   }

   private static List<Integer> cuts(int start, int end) {
      List<Integer> cuts = new ArrayList<>();
      cuts.add(start);
      int cut = Math.floorDiv(start, 4) * 4;
      if (cut <= start) {
         cut += 4;
      }

      while (cut < end) {
         cuts.add(cut);
         cut += 4;
      }

      cuts.add(end);
      return cuts;
   }

   private static void toggleFaceEdges(Set<TerrainWireframeMesher.EdgeKey> edges, TerrainWireframeMesher.FaceKey face) {
      switch (face.axis()) {
         case 0:
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(1, face.plane(), face.b0()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(1, face.plane(), face.b1()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(2, face.plane(), face.a0()), face.b0(), face.b1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(2, face.plane(), face.a1()), face.b0(), face.b1()));
            break;
         case 1:
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(0, face.plane(), face.b0()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(0, face.plane(), face.b1()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(2, face.a0(), face.plane()), face.b0(), face.b1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(2, face.a1(), face.plane()), face.b0(), face.b1()));
            break;
         default:
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(0, face.b0(), face.plane()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(0, face.b1(), face.plane()), face.a0(), face.a1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(1, face.a0(), face.plane()), face.b0(), face.b1()));
            toggle(edges, new TerrainWireframeMesher.EdgeKey(new TerrainWireframeMesher.LineKey(1, face.a1(), face.plane()), face.b0(), face.b1()));
      }
   }

   private static void toggle(Set<TerrainWireframeMesher.EdgeKey> edges, TerrainWireframeMesher.EdgeKey edge) {
      if (!edges.add(edge)) {
         edges.remove(edge);
      }
   }

   private static void add(
      Map<TerrainWireframeMesher.LineKey, List<TerrainWireframeMesher.Interval>> intervals,
      TerrainWireframeMesher.LineKey line,
      int start,
      int end,
      TerrainWireframeMesher.BoxStyle style
   ) {
      intervals.computeIfAbsent(line, ignored -> new ArrayList<>()).add(new TerrainWireframeMesher.Interval(start, end, style.material(), style.color()));
   }

   private static final class BoxAccumulator {
      private long red;
      private long green;
      private long blue;
      private int colorCount;
      private TerrainCellSample.RenderCategory material = TerrainCellSample.RenderCategory.UNKNOWN;

      private void add(TerrainCellSample.RenderCategory candidate, int color) {
         if (candidate != TerrainCellSample.RenderCategory.UNKNOWN) {
            this.material = candidate;
         }

         if (color != 0) {
            this.red += color >>> 16 & 0xFF;
            this.green += color >>> 8 & 0xFF;
            this.blue += color & 0xFF;
            this.colorCount++;
         }
      }

      private TerrainWireframeMesher.BoxStyle style() {
         if (this.colorCount == 0) {
            return new TerrainWireframeMesher.BoxStyle(this.material, 0);
         } else {
            int half = this.colorCount / 2;
            int r = (int)((this.red + half) / this.colorCount);
            int g = (int)((this.green + half) / this.colorCount);
            int b = (int)((this.blue + half) / this.colorCount);
            return new TerrainWireframeMesher.BoxStyle(this.material, 0xFF000000 | r << 16 | g << 8 | b);
         }
      }
   }

   private record BoxKey(int x0, int y0, int z0, int x1, int y1, int z1) {
   }

   private record BoxStyle(TerrainCellSample.RenderCategory material, int color) {
   }

   private record EdgeKey(TerrainWireframeMesher.LineKey line, int start, int end) {
   }

   private record Face(int axis, int plane, int a0, int a1, int b0, int b1, int side, TerrainWireframeMesher.BoxStyle style) {
   }

   private record FaceKey(int axis, int plane, int a0, int a1, int b0, int b1) {
   }

   private static final class FaceOccupancy {
      private boolean negative;
      private boolean positive;

      private void add(int side) {
         if (side < 0) {
            this.negative = true;
         } else {
            this.positive = true;
         }
      }

      private boolean hasOpposite(int side) {
         return side < 0 ? this.positive : this.negative;
      }
   }

   private record Interval(int start, int end, TerrainCellSample.RenderCategory material, int color) {
   }

   private static final class LineAccumulator {
      private long red;
      private long green;
      private long blue;
      private long weight;
      private TerrainCellSample.RenderCategory material = TerrainCellSample.RenderCategory.UNKNOWN;

      private void add(TerrainWireframeMesher.Interval interval) {
         if (interval.material() != TerrainCellSample.RenderCategory.UNKNOWN) {
            this.material = interval.material();
         }

         if (interval.color() != 0) {
            int intervalWeight = Math.max(1, interval.end() - interval.start());
            this.red = this.red + (long)(interval.color() >>> 16 & 0xFF) * intervalWeight;
            this.green = this.green + (long)(interval.color() >>> 8 & 0xFF) * intervalWeight;
            this.blue = this.blue + (long)(interval.color() & 0xFF) * intervalWeight;
            this.weight += intervalWeight;
         }
      }

      private TerrainCellSample.RenderCategory material() {
         return this.material;
      }

      private int color() {
         if (this.weight == 0L) {
            return 0;
         } else {
            int half = (int)(this.weight / 2L);
            int r = (int)((this.red + half) / this.weight);
            int g = (int)((this.green + half) / this.weight);
            int b = (int)((this.blue + half) / this.weight);
            return 0xFF000000 | r << 16 | g << 8 | b;
         }
      }
   }

   private record LineKey(int axis, int fixedA, int fixedB) {
      private TerrainWireframeMesher.Segment segment(int start, int end, TerrainCellSample.RenderCategory material, int color) {
         return switch (this.axis) {
            case 0 -> new TerrainWireframeMesher.Segment(start, this.fixedA, this.fixedB, end, this.fixedA, this.fixedB, material, color);
            case 1 -> new TerrainWireframeMesher.Segment(this.fixedA, start, this.fixedB, this.fixedA, end, this.fixedB, material, color);
            default -> new TerrainWireframeMesher.Segment(this.fixedA, this.fixedB, start, this.fixedA, this.fixedB, end, material, color);
         };
      }
   }

   public record Segment(int x1, int y1, int z1, int x2, int y2, int z2, TerrainCellSample.RenderCategory material, int color) {
      public Segment(int x1, int y1, int z1, int x2, int y2, int z2, TerrainCellSample.RenderCategory material) {
         this(x1, y1, z1, x2, y2, z2, material, 0);
      }

      public Segment(int x1, int y1, int z1, int x2, int y2, int z2, TerrainCellSample.RenderCategory material, int color) {
         Objects.requireNonNull(material, "material");
         if (color != 0 && (color & 0xFF000000) != -16777216) {
            throw new IllegalArgumentException("wire color must be zero or opaque ARGB");
         } else {
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.x2 = x2;
            this.y2 = y2;
            this.z2 = z2;
            this.material = material;
            this.color = color;
         }
      }
   }
}
