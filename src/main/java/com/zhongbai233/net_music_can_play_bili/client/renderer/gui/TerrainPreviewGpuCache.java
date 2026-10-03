package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.ByteBufferBuilder.Result;
import com.mojang.blaze3d.vertex.MeshData.SortState;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.vertex.VertexFormat.IndexType;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainBlockSectionSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainCompilationAdmission;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewSectionCompiler;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainResidentSectionPolicy;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainTranslucentSortPolicy;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockModelShaper;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TerrainPreviewGpuCache implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPreviewGpuCache.class);
   private static final RenderType[] RENDER_LAYERS = new RenderType[]{
      RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()
   };
   private static final long COVERAGE_LOG_INTERVAL_NANOS = 5000000000L;
   private final Map<TerrainSectionKey, TerrainPreviewGpuCache.GpuSection> sections = new HashMap<>();
   private final Map<TerrainSectionKey, TerrainBlockSectionSnapshot> failedSources = new HashMap<>();
   private final ConcurrentLinkedQueue<TerrainPreviewGpuCache.CompilationOutcome> completedCompilations = new ConcurrentLinkedQueue<>();
   private final ExecutorService compilationExecutor = Executors.newSingleThreadExecutor(terrainCompilerThreadFactory());
   private TerrainPreviewGpuCache.CompilationRequest activeCompilation;
   private long compilationEpoch;
   private long generation;
   private BlockModelShaper modelShaper;
   private TerrainBounds synchronizedBounds;
   private long lastCoverageLogNanos;
   private long lastCoverageGeneration = Long.MIN_VALUE;
   private TerrainPreviewFrame exhaustedCompilationFrame;
   private Matrix4f exhaustedCompilationViewProjection;
   private long residentRevision;
   private TerrainPreviewGpuCache.RenderPlan cachedRenderPlan;
   private long renderPlanHits;
   private long renderPlanBuilds;
   private long sessionReleases;
   private volatile boolean closed;
   private boolean disabledForSession;
   private boolean failureLogged;

   void updateAndRender(TerrainPreviewFrame frame, Matrix4fc modelView, HolographicPreviewPipRenderState state) {
      if (!this.disabledForSession) {
         try {
            this.updateAndRenderInternal(frame, modelView, state);
         } catch (Throwable var6) {
            if (var6 instanceof VirtualMachineError fatal) {
               throw fatal;
            }

            if (var6 instanceof Error fatal) {
               throw fatal;
            }

            this.disableForSession("terrain preview GPU/render failure", var6);
         }
      }
   }

   private void updateAndRenderInternal(TerrainPreviewFrame frame, Matrix4fc modelView, HolographicPreviewPipRenderState state) {
      BlockModelShaper currentModels = Minecraft.getInstance().getModelManager().getBlockModelShaper();
      if (frame.generation() != this.generation || currentModels != this.modelShaper) {
         this.clear();
         this.generation = frame.generation();
         this.modelShaper = currentModels;
      }

      this.removeTombstonedSections(frame);
      this.removeNonResidentSections(frame);
      this.synchronizeCoverage(frame);
      Matrix4f viewProjection = state.cameraFrame() != null ? new Matrix4f(state.cameraFrame().matrices().viewProjection()) : new Matrix4f();
      this.updateCompilation(frame, modelView, viewProjection);
      this.renderLayers(frame, modelView, viewProjection);
   }

   private void synchronizeCoverage(TerrainPreviewFrame frame) {
      if (!frame.bounds().equals(this.synchronizedBounds)) {
         this.synchronizedBounds = frame.bounds();
         boolean removed = this.sections.entrySet().removeIf(entry -> {
            if (frame.bounds().intersects(entry.getKey())) {
               return false;
            } else {
               entry.getValue().close();
               this.failedSources.remove(entry.getKey());
               return true;
            }
         });
         if (removed) {
            this.residentRevision++;
            this.cachedRenderPlan = null;
            this.exhaustedCompilationFrame = null;
            this.exhaustedCompilationViewProjection = null;
         }
      }
   }

   private void removeTombstonedSections(TerrainPreviewFrame frame) {
      boolean removed = false;

      for (TerrainSectionKey key : frame.removedSections()) {
         TerrainPreviewGpuCache.GpuSection section = this.sections.remove(key);
         if (section != null) {
            section.close();
            removed = true;
         }

         this.failedSources.remove(key);
      }

      if (removed) {
         this.residentRevision++;
         this.cachedRenderPlan = null;
         this.exhaustedCompilationFrame = null;
         this.exhaustedCompilationViewProjection = null;
      }
   }

   private void removeNonResidentSections(TerrainPreviewFrame frame) {
      boolean removed = false;

      for (TerrainSectionKey key : TerrainResidentSectionPolicy.staleSections(this.sections.keySet(), frame)) {
         TerrainPreviewGpuCache.GpuSection section = this.sections.remove(key);
         if (section != null) {
            section.close();
            removed = true;
         }

         this.failedSources.remove(key);
      }

      if (removed) {
         this.residentRevision++;
         this.cachedRenderPlan = null;
         this.exhaustedCompilationFrame = null;
         this.exhaustedCompilationViewProjection = null;
      }
   }

   private void updateCompilation(TerrainPreviewFrame frame, Matrix4fc modelView, Matrix4fc viewProjection) {
      this.consumeCompletedCompilation(frame);
      if (this.activeCompilation == null && !this.closed) {
         if (frame != this.exhaustedCompilationFrame
            || this.exhaustedCompilationViewProjection == null
            || !this.exhaustedCompilationViewProjection.equals(viewProjection)) {
            for (TerrainBlockSectionSnapshot snapshot : frame.fullDetailSections()) {
               TerrainPreviewGpuCache.GpuSection current = this.sections.get(snapshot.section());
               if (current != null && current.source.get() != snapshot && this.failedSources.get(snapshot.section()) != snapshot) {
                  this.exhaustedCompilationFrame = null;
                  this.exhaustedCompilationViewProjection = null;
                  this.submitCompilation(frame, snapshot);
                  return;
               }
            }

            TerrainBlockSectionSnapshot nearestMissing = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            TerrainBlockSectionSnapshot nearestMissingOutsideFrustum = null;
            double nearestOutsideDistance = Double.POSITIVE_INFINITY;

            for (TerrainBlockSectionSnapshot snapshotx : frame.fullDetailSections()) {
               if (!this.sections.containsKey(snapshotx.section()) && this.failedSources.get(snapshotx.section()) != snapshotx) {
                  double distance = viewDistanceSquared(modelView, frame, snapshotx.section());
                  if (intersectsFrustum(frame, viewProjection, snapshotx.section())) {
                     if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestMissing = snapshotx;
                     }
                  } else if (distance < nearestOutsideDistance) {
                     nearestOutsideDistance = distance;
                     nearestMissingOutsideFrustum = snapshotx;
                  }
               }
            }

            if (nearestMissing == null) {
               nearestMissing = nearestMissingOutsideFrustum;
            }

            if (nearestMissing != null) {
               this.exhaustedCompilationFrame = null;
               this.exhaustedCompilationViewProjection = null;
               this.submitCompilation(frame, nearestMissing);
            } else {
               this.exhaustedCompilationFrame = frame;
               this.exhaustedCompilationViewProjection = new Matrix4f(viewProjection);
            }
         }
      }
   }

   private static boolean intersectsFrustum(TerrainPreviewFrame frame, Matrix4fc viewProjection, TerrainSectionKey key) {
      float x = key.minBlockX() - frame.originX();
      float y = key.minBlockY() - frame.originY();
      float z = key.minBlockZ() - frame.originZ();
      return intersectsClip(viewProjection, x, y, z, x + 16.0F, y + 16.0F, z + 16.0F);
   }

   private static boolean intersectsClip(Matrix4fc matrix, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
      int outsidePlanes = 63;

      for (int corner = 0; corner < 8 && outsidePlanes != 0; corner++) {
         float x = (corner & 4) == 0 ? minX : maxX;
         float y = (corner & 2) == 0 ? minY : maxY;
         float z = (corner & 1) == 0 ? minZ : maxZ;
         float clipX = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
         float clipY = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
         float clipZ = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
         float clipW = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
         int cornerOutside = 0;
         if (clipX < -clipW) {
            cornerOutside |= 1;
         }

         if (clipX > clipW) {
            cornerOutside |= 2;
         }

         if (clipY < -clipW) {
            cornerOutside |= 4;
         }

         if (clipY > clipW) {
            cornerOutside |= 8;
         }

         if (clipZ < -clipW) {
            cornerOutside |= 16;
         }

         if (clipZ > clipW) {
            cornerOutside |= 32;
         }

         outsidePlanes &= cornerOutside;
      }

      return outsidePlanes == 0;
   }

   private void submitCompilation(TerrainPreviewFrame frame, TerrainBlockSectionSnapshot snapshot) {
      TerrainPreviewGpuCache.CompilationRequest request = new TerrainPreviewGpuCache.CompilationRequest(
         this.compilationEpoch, frame, snapshot, this.modelShaper, Minecraft.getInstance().getBlockColors()
      );
      this.activeCompilation = request;
      this.compilationExecutor.execute(() -> {
         TerrainPreviewSectionCompiler.CompiledCpuSection compiled = null;
         Throwable failure = null;

         try {
            compiled = TerrainPreviewSectionCompiler.compile(request.frame, request.source, request.modelShaper, request.blockColors);
         } catch (Throwable var5) {
            failure = var5;
         }

         if (this.closed) {
            if (compiled != null) {
               compiled.close();
            }
         } else {
            TerrainPreviewGpuCache.CompilationOutcome outcome = new TerrainPreviewGpuCache.CompilationOutcome(request, compiled, failure);
            this.completedCompilations.add(outcome);
            if (this.closed && this.completedCompilations.remove(outcome)) {
               outcome.closeCompiled();
            }
         }
      });
   }

   private void consumeCompletedCompilation(TerrainPreviewFrame frame) {
      TerrainPreviewGpuCache.CompilationOutcome outcome = this.completedCompilations.poll();
      if (outcome != null) {
         if (this.activeCompilation == outcome.request) {
            this.activeCompilation = null;
         }

         boolean current = TerrainCompilationAdmission.isCurrent(frame, outcome.request.source, outcome.request.epoch, this.compilationEpoch);
         if (!current) {
            outcome.closeCompiled();
         } else if (outcome.failure != null) {
            if (outcome.failure instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               TerrainPreviewRenderDiagnostics.recordFailure();
               this.failedSources.put(outcome.request.source.section(), outcome.request.source);
               LOGGER.warn("无法编译地形预览 section {}，保留旧网格", outcome.request.source.section(), outcome.failure);
               TerrainPreviewManager.markCompiled(outcome.request.frame.generation(), outcome.request.source);
            }
         } else {
            try (TerrainPreviewSectionCompiler.CompiledCpuSection compiled = outcome.compiled) {
               try {
                  TerrainPreviewGpuCache.GpuSection replacement = TerrainPreviewGpuCache.GpuSection.upload(compiled);
                  boolean materialLod = compiled.source().blocks().stream().anyMatch(block -> block.cellSize() > 1);
                  boolean translucent = compiled.layers().containsKey(RenderType.translucent());
                  TerrainPreviewRenderDiagnostics.recordSectionUpload(materialLod, translucent);
                  TerrainPreviewGpuCache.GpuSection old = this.sections.put(outcome.request.source.section(), replacement);
                  if (old != null) {
                     old.close();
                  }

                  this.residentRevision++;
                  this.exhaustedCompilationFrame = null;
                  this.exhaustedCompilationViewProjection = null;
                  this.cachedRenderPlan = null;
                  this.failedSources.remove(outcome.request.source.section());
                  TerrainPreviewManager.markCompiled(outcome.request.frame.generation(), outcome.request.source);
               } catch (Throwable var10) {
                  if (var10 instanceof VirtualMachineError fatal) {
                     throw fatal;
                  }

                  if (var10 instanceof Error fatal) {
                     throw fatal;
                  }

                  TerrainPreviewRenderDiagnostics.recordFailure();
                  this.failedSources.put(outcome.request.source.section(), outcome.request.source);
                  LOGGER.warn("无法上传地形预览 section {}，跳过该 section", outcome.request.source.section(), var10);
                  TerrainPreviewManager.markCompiled(outcome.request.frame.generation(), outcome.request.source);
               }
            }
         }
      }
   }

   private void renderLayers(TerrainPreviewFrame frame, Matrix4fc modelView, Matrix4fc viewProjection) {
      Minecraft minecraft = Minecraft.getInstance();
      if (this.resortTranslucentQuads(frame, modelView, viewProjection)) {
         this.residentRevision++;
         this.cachedRenderPlan = null;
      }

      TerrainPreviewGpuCache.RenderPlan plan = this.renderPlan(frame, modelView, viewProjection);
      this.logCoverage(frame, plan.visible.size());

      try {
         this.drawLayers(minecraft, plan);
      } catch (Throwable var8) {
         if (var8 instanceof VirtualMachineError fatal) {
            throw fatal;
         } else if (var8 instanceof Error fatal) {
            throw fatal;
         } else {
            throw new TerrainPreviewGpuCache.TerrainPreviewRenderFailure(var8);
         }
      }
   }

   private boolean resortTranslucentQuads(TerrainPreviewFrame frame, Matrix4fc modelView, Matrix4fc viewProjection) {
      boolean changed = false;

      for (TerrainPreviewGpuCache.GpuSection section : this.sections.values()) {
         TerrainPreviewGpuCache.GpuLayer layer = section.layers.get(RenderType.translucent());
         if (layer != null && layer.sortState != null && !layer.sortedFor(modelView) && intersectsFrustum(frame, viewProjection, section.key)) {
            float sectionX = section.key.minBlockX() - frame.originX();
            float sectionY = section.key.minBlockY() - frame.originY();
            float sectionZ = section.key.minBlockZ() - frame.originZ();
            VertexSorting sorting = VertexSorting.byDistance(
               point -> TerrainTranslucentSortPolicy.viewDistanceSquared(modelView, sectionX, sectionY, sectionZ, point.x(), point.y(), point.z())
            );
            layer.resort(sorting, modelView);
            changed = true;
         }
      }

      return changed;
   }

   private void disableForSession(String message, Throwable failure) {
      this.disabledForSession = true;
      if (!this.failureLogged) {
         this.failureLogged = true;
         LOGGER.warn("{}; disabling terrain preview until the session is reset", message, failure);
      }

      this.clear();
   }

   private TerrainPreviewGpuCache.RenderPlan renderPlan(TerrainPreviewFrame frame, Matrix4fc modelView, Matrix4fc viewProjection) {
      TerrainPreviewGpuCache.RenderPlan cached = this.cachedRenderPlan;
      if (cached != null
         && cached.residentRevision == this.residentRevision
         && cached.frame == frame
         && cached.modelView.equals(modelView)
         && cached.viewProjection.equals(viewProjection)) {
         this.renderPlanHits++;
         return cached;
      } else {
         this.renderPlanBuilds++;
         List<TerrainPreviewGpuCache.GpuSection> visible = new ArrayList<>();

         for (TerrainPreviewGpuCache.GpuSection section : this.sections.values()) {
            if (intersectsFrustum(frame, viewProjection, section.key)) {
               visible.add(section);
            }
         }

         visible.sort(Comparator.comparingDouble(sectionx -> viewDepth(modelView, frame, sectionx)));
         HashMap<RenderType, List<TerrainPreviewGpuCache.GpuSection>> draws = new HashMap<>();

         for (RenderType layer : RENDER_LAYERS) {
            List<TerrainPreviewGpuCache.GpuSection> layerDraws = new ArrayList<>();

            for (TerrainPreviewGpuCache.GpuSection sectionx : visible) {
               if (sectionx.layers.containsKey(layer)) {
                  layerDraws.add(sectionx);
               }
            }

            draws.put(layer, List.copyOf(layerDraws));
         }

         cached = new TerrainPreviewGpuCache.RenderPlan(
            frame, this.residentRevision, new Matrix4f(modelView), new Matrix4f(viewProjection), List.copyOf(visible), draws
         );
         this.cachedRenderPlan = cached;
         return cached;
      }
   }

   private void logCoverage(TerrainPreviewFrame frame, int visibleSections) {
      if (LOGGER.isTraceEnabled()) {
         long now = System.nanoTime();
         if (frame.generation() != this.lastCoverageGeneration || now - this.lastCoverageLogNanos >= 5000000000L) {
            this.lastCoverageGeneration = frame.generation();
            this.lastCoverageLogNanos = now;
            long unknown = frame.overviewCells().stream().filter(cell -> cell.material() == TerrainCellSample.RenderCategory.UNKNOWN).count();
            long overview = frame.overviewCells().size() - unknown;
            LOGGER.trace(
               "地形预览 hardRange 覆盖: generation={}, bounds={}, nearCpu={}, overviewCells={}, wireSegments={}, unknownSections={}, capturePending={}, sampledSections={}, resident={}, visible={}, compilerActive={}",
               new Object[]{
                  frame.generation(),
                  frame.bounds(),
                  frame.fullDetailSections().size(),
                  overview,
                  frame.wireframeSegments().size(),
                  unknown,
                  frame.pendingSections(),
                  frame.sampledSections(),
                  this.sections.size(),
                  visibleSections,
                  this.activeCompilation != null
               }
            );
         }
      }
   }

   private static double viewDistanceSquared(Matrix4fc modelView, TerrainPreviewFrame frame, TerrainSectionKey key) {
      float x = key.minBlockX() - frame.originX() + 8.0F;
      float y = key.minBlockY() - frame.originY() + 8.0F;
      float z = key.minBlockZ() - frame.originZ() + 8.0F;
      double viewX = modelView.m00() * x + modelView.m10() * y + modelView.m20() * z + modelView.m30();
      double viewY = modelView.m01() * x + modelView.m11() * y + modelView.m21() * z + modelView.m31();
      double viewZ = modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32();
      return viewX * viewX + viewY * viewY + viewZ * viewZ;
   }

   private static double viewDepth(Matrix4fc modelView, TerrainPreviewFrame frame, TerrainPreviewGpuCache.GpuSection section) {
      float x = section.key.minBlockX() - frame.originX() + 8.0F;
      float y = section.key.minBlockY() - frame.originY() + 8.0F;
      float z = section.key.minBlockZ() - frame.originZ() + 8.0F;
      return modelView.m02() * x + modelView.m12() * y + modelView.m22() * z + modelView.m32();
   }

   private void drawLayers(Minecraft minecraft, TerrainPreviewGpuCache.RenderPlan plan) {
      Matrix4f projection = RenderSystem.getProjectionMatrix();

      for (RenderType layer : RENDER_LAYERS) {
         List<TerrainPreviewGpuCache.GpuSection> draws = plan.draws.get(layer);
         if (!draws.isEmpty()) {
            layer.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader == null) {
               layer.clearRenderState();
            } else {
               shader.setDefaultUniforms(Mode.QUADS, plan.modelView, projection, minecraft.getWindow());
               shader.apply();
               Uniform chunkOffset = shader.CHUNK_OFFSET;

               try {
                  for (TerrainPreviewGpuCache.GpuSection section : draws) {
                     TerrainPreviewGpuCache.GpuLayer mesh = section.layers.get(layer);
                     if (mesh != null) {
                        if (chunkOffset != null) {
                           chunkOffset.set(
                              section.key.minBlockX() - plan.frame.originX(),
                              section.key.minBlockY() - plan.frame.originY(),
                              section.key.minBlockZ() - plan.frame.originZ()
                           );
                           chunkOffset.upload();
                        }

                        mesh.vertexBuffer.bind();
                        mesh.vertexBuffer.draw();
                     }
                  }
               } finally {
                  if (chunkOffset != null) {
                     chunkOffset.set(0.0F, 0.0F, 0.0F);
                  }

                  shader.clear();
                  VertexBuffer.unbind();
                  layer.clearRenderState();
               }
            }
         }
      }
   }

   void clear() {
      this.compilationEpoch++;
      this.activeCompilation = null;
      this.sections.values().forEach(section -> section.close());
      this.sections.clear();
      this.failedSources.clear();
      this.synchronizedBounds = null;
      this.exhaustedCompilationFrame = null;
      this.exhaustedCompilationViewProjection = null;
      this.residentRevision++;
      this.cachedRenderPlan = null;
      this.lastCoverageLogNanos = 0L;
      this.lastCoverageGeneration = Long.MIN_VALUE;
      if (!this.disabledForSession) {
         this.failureLogged = false;
      }

      TerrainPreviewGpuCache.CompilationOutcome outcome;
      while ((outcome = this.completedCompilations.poll()) != null) {
         outcome.closeCompiled();
      }
   }

   void releaseSession() {
      if (!this.sections.isEmpty() || this.activeCompilation != null || !this.completedCompilations.isEmpty()) {
         this.clear();
         this.disabledForSession = false;
         this.failureLogged = false;
         this.sessionReleases++;
      }
   }

   @Override
   public void close() {
      this.closed = true;
      this.releaseSession();
      LOGGER.debug("地形预览稳态缓存: planBuilds={}, planHits={}, sessionReleases={}", new Object[]{this.renderPlanBuilds, this.renderPlanHits, this.sessionReleases});
      this.compilationExecutor.shutdownNow();
   }

   private static ThreadFactory terrainCompilerThreadFactory() {
      return task -> NetMusicThreadFactory.daemonThread("NCPB terrain section compiler", task, 4);
   }

   private record CompilationOutcome(
      TerrainPreviewGpuCache.CompilationRequest request, TerrainPreviewSectionCompiler.CompiledCpuSection compiled, Throwable failure
   ) {
      private CompilationOutcome(
         TerrainPreviewGpuCache.CompilationRequest request, TerrainPreviewSectionCompiler.CompiledCpuSection compiled, Throwable failure
      ) {
         if (compiled == null == (failure == null)) {
            throw new IllegalArgumentException("compilation outcome must contain exactly one result");
         } else {
            this.request = request;
            this.compiled = compiled;
            this.failure = failure;
         }
      }

      private void closeCompiled() {
         if (this.compiled != null) {
            this.compiled.close();
         }
      }
   }

   private record CompilationRequest(
      long epoch, TerrainPreviewFrame frame, TerrainBlockSectionSnapshot source, BlockModelShaper modelShaper, BlockColors blockColors
   ) {
   }

   private static final class GpuLayer implements AutoCloseable {
      private final VertexBuffer vertexBuffer;
      private final int indexCount;
      private final IndexType indexType;
      private final boolean customIndices;
      @Nullable
      private final SortState sortState;
      private Matrix4f lastSortModelView;

      private GpuLayer(VertexBuffer vertexBuffer, int indexCount, IndexType indexType, boolean customIndices, @Nullable SortState sortState) {
         this.vertexBuffer = vertexBuffer;
         this.indexCount = indexCount;
         this.indexType = indexType;
         this.customIndices = customIndices;
         this.sortState = sortState;
      }

      private static TerrainPreviewGpuCache.GpuLayer create(MeshData mesh, @Nullable SortState sortState) {
         int indexCount = mesh.drawState().indexCount();
         IndexType indexType = mesh.drawState().indexType();
         boolean customIndices = mesh.indexBuffer() != null;
         VertexBuffer vertexBuffer = new VertexBuffer(Usage.STATIC);
         vertexBuffer.upload(mesh);
         return new TerrainPreviewGpuCache.GpuLayer(vertexBuffer, indexCount, indexType, customIndices, sortState);
      }

      private boolean sortedFor(Matrix4fc modelView) {
         return !TerrainTranslucentSortPolicy.needsResort(this.lastSortModelView, modelView);
      }

      private void resort(VertexSorting sorting, Matrix4fc modelView) {
         if (this.sortState != null) {
            int bytes = Math.max(256, this.indexCount * this.indexType.bytes);
            ByteBufferBuilder storage = new ByteBufferBuilder(bytes);

            label29: {
               try {
                  Result result = this.sortState.buildSortedIndexBuffer(storage, sorting);
                  if (result == null) {
                     break label29;
                  }

                  this.vertexBuffer.uploadIndexBuffer(result);
               } catch (Throwable var8) {
                  try {
                     storage.close();
                  } catch (Throwable var7) {
                     var8.addSuppressed(var7);
                  }

                  throw var8;
               }

               storage.close();
               this.lastSortModelView = new Matrix4f(modelView);
               TerrainPreviewRenderDiagnostics.recordTranslucentResort();
               return;
            }

            storage.close();
         }
      }

      @Override
      public void close() {
         this.vertexBuffer.close();
      }
   }

   private static final class GpuSection implements AutoCloseable {
      private final TerrainSectionKey key;
      private final WeakReference<TerrainBlockSectionSnapshot> source;
      private final Map<RenderType, TerrainPreviewGpuCache.GpuLayer> layers;

      private GpuSection(TerrainSectionKey key, TerrainBlockSectionSnapshot source, Map<RenderType, TerrainPreviewGpuCache.GpuLayer> layers) {
         this.key = key;
         this.source = new WeakReference<>(source);
         this.layers = layers;
      }

      private static TerrainPreviewGpuCache.GpuSection upload(TerrainPreviewSectionCompiler.CompiledCpuSection compiled) {
         Map<RenderType, TerrainPreviewGpuCache.GpuLayer> layers = new HashMap<>();

         try {
            for (Entry<RenderType, MeshData> entry : compiled.layers().entrySet()) {
               TerrainPreviewGpuCache.GpuLayer layer = TerrainPreviewGpuCache.GpuLayer.create(entry.getValue(), compiled.sortStates().get(entry.getKey()));
               layers.put(entry.getKey(), layer);
            }

            return new TerrainPreviewGpuCache.GpuSection(compiled.source().section(), compiled.source(), layers);
         } catch (Throwable var5) {
            layers.values().forEach(layerx -> layerx.close());
            throw var5;
         }
      }

      @Override
      public void close() {
         this.layers.values().forEach(layer -> layer.close());
         this.layers.clear();
      }
   }

   private record RenderPlan(
      TerrainPreviewFrame frame,
      long residentRevision,
      Matrix4f modelView,
      Matrix4f viewProjection,
      List<TerrainPreviewGpuCache.GpuSection> visible,
      HashMap<RenderType, List<TerrainPreviewGpuCache.GpuSection>> draws
   ) {
   }

   private static final class TerrainPreviewRenderFailure extends RuntimeException {
      private TerrainPreviewRenderFailure(Throwable cause) {
         super(cause);
      }
   }
}
