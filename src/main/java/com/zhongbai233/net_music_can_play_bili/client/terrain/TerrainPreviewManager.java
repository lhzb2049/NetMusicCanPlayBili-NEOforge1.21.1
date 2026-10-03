package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCellSample;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainCoverageCursor;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainDirtyTracker;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFixedCorePolicy;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainMapColorAggregator;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainNeighborhoodIndex;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainOverviewAggregator;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainSectionKey;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainTintColors;
import com.zhongbai233.net_music_can_play_bili.terrain.core.WeightedLruCache;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class TerrainPreviewManager {
   private static volatile TerrainPreviewManager.Session active;
   private static volatile TerrainPreviewManager.Session parked;
   private static volatile TerrainPreviewFrame published = TerrainPreviewFrame.empty();

   private TerrainPreviewManager() {
   }

   public static void update(ClientLevel level, BlockPos origin, TerrainBounds bounds, Vector3dc coreCenterLocal) {
      if (level != null && origin != null && bounds != null && coreCenterLocal != null) {
         TerrainPreviewManager.Session session = active;
         if (session == null && parked != null && parked.matches(level, origin, bounds, coreCenterLocal)) {
            session = parked;
            parked = null;
            session.resume();
            active = session;
            published = session.frame();
         } else {
            if (session == null
               || session.level != level
               || !session.origin.equals(origin)
               || !session.bounds.equals(bounds)
               || !session.hasCoreCenter(coreCenterLocal)) {
               if (session != null) {
                  session.close();
               }

               if (parked != null) {
                  parked.close();
                  parked = null;
               }

               session = new TerrainPreviewManager.Session(level, origin.immutable(), bounds, coreCenterLocal, session == null ? 1L : session.generation + 1L);
               active = session;
               published = session.frame();
            }
         }
      }
   }

   public static void tick() {
      TerrainPreviewManager.Session session = active;
      if (session != null) {
         if (Minecraft.getInstance().level != session.level) {
            clear();
         } else {
            session.tick();
            published = session.frame();
         }
      }
   }

   public static TerrainPreviewFrame frame() {
      return published;
   }

   public static void markCompiled(long generation, TerrainBlockSectionSnapshot source) {
      TerrainPreviewManager.Session session = active;
      if (session != null && session.generation == generation && source != null) {
         session.markCompiled(source);
      }
   }

   public static void markBlockDirty(ClientLevel level, BlockPos pos) {
      markBlockDirty(active, level, pos);
      markBlockDirty(parked, level, pos);
   }

   private static void markBlockDirty(TerrainPreviewManager.Session session, ClientLevel level, BlockPos pos) {
      if (session != null && session.level == level && session.bounds.contains(pos.getX(), pos.getY(), pos.getZ())) {
         session.dirty.markBlockAndBoundaryNeighbors(pos.getX(), pos.getY(), pos.getZ());
      }
   }

   public static void markChunkUnloaded(ClientLevel level, int chunkX, int chunkZ) {
      if (active != null && active.level == level) {
         active.markChunkUnloaded(chunkX, chunkZ);
         published = active.frame();
      }

      if (parked != null && parked.level == level) {
         parked.markChunkUnloaded(chunkX, chunkZ);
      }
   }

   public static void markChunkLoaded(ClientLevel level, int chunkX, int chunkZ) {
      if (active != null && active.level == level) {
         active.markChunkLoaded(chunkX, chunkZ);
         published = active.frame();
      }

      if (parked != null && parked.level == level) {
         parked.markChunkLoaded(chunkX, chunkZ);
      }
   }

   public static void close(BlockPos origin) {
      TerrainPreviewManager.Session session = active;
      if (session != null && session.origin.equals(origin)) {
         active = null;
         if (parked != null && parked != session) {
            parked.close();
         }

         session.suspend();
         parked = session;
         published = TerrainPreviewFrame.empty();
      }
   }

   public static void clear() {
      TerrainPreviewManager.Session session = active;
      active = null;
      if (session != null) {
         session.close();
      }

      TerrainPreviewManager.Session cached = parked;
      parked = null;
      if (cached != null && cached != session) {
         cached.close();
      }

      published = TerrainPreviewFrame.empty();
   }

   static List<TerrainOverviewCell> aggregateOverview(TerrainSectionKey key, List<TerrainBlockSectionSnapshot.VisibleBlock> visible, int size) {
      List<TerrainOverviewAggregator.Cell> input = new ArrayList<>(visible.size());

      for (TerrainBlockSectionSnapshot.VisibleBlock block : visible) {
         input.add(new TerrainOverviewAggregator.Cell(block.localX(), block.localY(), block.localZ(), TerrainCellSample.RenderCategory.MODEL));
      }

      List<TerrainOverviewAggregator.Cell> cells = TerrainOverviewAggregator.aggregate(input, size);
      List<TerrainOverviewCell> result = new ArrayList<>(cells.size());

      for (TerrainOverviewAggregator.Cell cell : cells) {
         result.add(
            new TerrainOverviewCell(key.minBlockX() + cell.localX(), key.minBlockY() + cell.localY(), key.minBlockZ() + cell.localZ(), size, cell.material())
         );
      }

      return List.copyOf(result);
   }

   static <T> List<TerrainOverviewCell> mapColorOverview(TerrainSectionKey key, List<TerrainMapColorAggregator.Sample<T>> visible, int size) {
      List<TerrainOverviewCell> result = new ArrayList<>();

      for (TerrainMapColorAggregator.Cell<T> cell : TerrainMapColorAggregator.aggregate(visible, size)) {
         result.add(
            new TerrainOverviewCell(
               key.minBlockX() + cell.localX(),
               key.minBlockY() + cell.localY(),
               key.minBlockZ() + cell.localZ(),
               cell.size(),
               TerrainCellSample.RenderCategory.MODEL,
               cell.color()
            )
         );
      }

      return List.copyOf(result);
   }

   static Set<TerrainSectionKey> sectionKeysForChunk(int chunkX, int chunkZ, TerrainBounds bounds, int levelMinY, int levelMaxY) {
      TerrainSectionKey horizontal = new TerrainSectionKey(chunkX, 0, chunkZ);
      if (horizontal.minBlockX() <= bounds.maxX()
         && horizontal.minBlockX() + 16 - 1 >= bounds.minX()
         && horizontal.minBlockZ() <= bounds.maxZ()
         && horizontal.minBlockZ() + 16 - 1 >= bounds.minZ()) {
         int minY = Math.floorDiv(Math.max(bounds.minY(), levelMinY), 16);
         int maxY = Math.floorDiv(Math.min(bounds.maxY(), levelMaxY - 1), 16);
         Set<TerrainSectionKey> keys = new HashSet<>();

         for (int y = minY; y <= maxY; y++) {
            keys.add(new TerrainSectionKey(chunkX, y, chunkZ));
         }

         return Set.copyOf(keys);
      } else {
         return Set.of();
      }
   }

   private static final class Session {
      private static final long FULL_BLOCK_CACHE_BYTES = 268435456L;
      private static final long ESTIMATED_SECTION_BASE_BYTES = 256L;
      private static final long ESTIMATED_VISIBLE_BLOCK_BYTES = 48L;
      private static final long ESTIMATED_NEIGHBORHOOD_CELL_BYTES = 9L;
      private static final long CAPTURE_BUDGET_NANOS = 4000000L;
      private static final int MAX_CAPTURES_PER_TICK = 8;
      private static final int MAX_BUFFERED_SNAPSHOTS = 32;
      private static final int COVERAGE_CANDIDATES_PER_TICK = 64;
      private static final int MAX_PENDING_SECTIONS = 512;
      private static final int MAX_UNKNOWN_SECTIONS = 256;
      private static final int MAX_BLOCK_ENTITY_PREVIEWS = 128;
      private final ClientLevel level;
      private final BlockPos origin;
      private final TerrainBounds bounds;
      private final long generation;
      private final TerrainDirtyTracker dirty = new TerrainDirtyTracker(1024);
      private final WeightedLruCache<TerrainSectionKey, TerrainBlockSectionSnapshot> sections = new WeightedLruCache<>(
         268435456L, snapshot -> snapshot.estimatedBytes()
      );
      private final ArrayDeque<TerrainSectionKey> pending = new ArrayDeque<>();
      private final Set<TerrainSectionKey> queued = new HashSet<>();
      private final Set<TerrainSectionKey> removedSections = new HashSet<>();
      private final Set<TerrainSectionKey> fullDetailSectionKeys = new HashSet<>();
      private final LinkedHashMap<TerrainSectionKey, TerrainOverviewCell> unknownSections = new LinkedHashMap<>();
      private final LinkedHashMap<TerrainSectionKey, List<TerrainOverviewCell>> overviewBySection = new LinkedHashMap<>();
      private final Set<BlockPos> blockEntityPositions = new HashSet<>();
      private List<TerrainBlockEntityPreview> blockEntityPreviews = List.of();
      private final TerrainCoverageCursor coverageCursor;
      private final MutableBlockPos cursor = new MutableBlockPos();
      private TerrainPreviewFrame cachedFrame;
      private boolean frameDirty = true;
      private boolean closed;
      private boolean suspended;
      private final Vector3d coreCenterLocal;
      private final double coreCenterX;
      private final double coreCenterY;
      private final double coreCenterZ;
      private final long coreSeed;

      private Session(ClientLevel level, BlockPos origin, TerrainBounds bounds, Vector3dc coreCenterLocal, long generation) {
         this.level = level;
         this.origin = origin;
         this.bounds = bounds;
         this.generation = generation;
         this.coreCenterLocal = new Vector3d(coreCenterLocal);
         this.coreCenterX = origin.getX() + coreCenterLocal.x();
         this.coreCenterY = origin.getY() + coreCenterLocal.y();
         this.coreCenterZ = origin.getZ() + coreCenterLocal.z();
         this.coreSeed = seed(origin);
         this.coverageCursor = new TerrainCoverageCursor(bounds, coverageFocus(origin, coreCenterLocal));
      }

      private boolean hasCoreCenter(Vector3dc center) {
         return this.coreCenterLocal.distanceSquared(center) < 1.0E-8;
      }

      private boolean matches(ClientLevel level, BlockPos origin, TerrainBounds bounds, Vector3dc center) {
         return !this.closed && this.level == level && this.origin.equals(origin) && this.bounds.equals(bounds) && this.hasCoreCenter(center);
      }

      private void suspend() {
         this.suspended = true;
      }

      private void resume() {
         this.suspended = false;
         this.frameDirty = true;
      }

      private static long seed(BlockPos origin) {
         long value = origin.getX() * 341873128712L ^ origin.getY() * 132897987541L ^ origin.getZ() * 42317861L;
         return value ^ value >>> 29;
      }

      private static TerrainSectionKey coverageFocus(BlockPos origin, Vector3dc cameraLocal) {
         return TerrainSectionKey.fromBlock(
            floorBlock(origin.getX(), cameraLocal.x()), floorBlock(origin.getY(), cameraLocal.y()), floorBlock(origin.getZ(), cameraLocal.z())
         );
      }

      private static int floorBlock(int origin, double local) {
         double world = origin + local;
         if (world <= -2.1474836E9F) {
            return Integer.MIN_VALUE;
         } else {
            return world >= 2.147483647E9 ? Integer.MAX_VALUE : (int)Math.floor(world);
         }
      }

      private void scheduleCoverageStep() {
         if (this.queued.size() < 512) {
            for (TerrainSectionKey key : this.coverageCursor.next(64)) {
               if (this.level.hasChunk(key.x(), key.z())) {
                  if (this.unknownSections.remove(key) != null) {
                     this.frameDirty = true;
                  }

                  this.enqueue(key);
               } else {
                  this.rememberUnknown(key);
               }
            }
         }
      }

      private void tick() {
         if (!this.closed && !this.suspended) {
            this.scheduleCoverageStep();
            List<TerrainSectionKey> dirtySections = this.dirty.drain(8);

            for (TerrainSectionKey key : dirtySections) {
               this.sections.remove(key);
               this.fullDetailSectionKeys.remove(key);
               this.overviewBySection.remove(key);
               this.enqueueFirst(key);
            }

            long deadline = System.nanoTime() + 4000000L;
            int captured = 0;

            while (captured < 8) {
               TerrainSectionKey key = this.pending.pollFirst();
               if (key == null) {
                  break;
               }

               this.frameDirty = true;
               this.queued.remove(key);
               boolean detailCandidate = TerrainFixedCorePolicy.sectionMayContainDetail(
                  this.coreCenterX, this.coreCenterY, this.coreCenterZ, key.minBlockX(), key.minBlockY(), key.minBlockZ()
               );
               if (detailCandidate && dirtySections.isEmpty() && this.sections.size() >= 32) {
                  this.enqueue(key);
                  break;
               }

               this.capture(key);
               captured++;
               if (System.nanoTime() >= deadline) {
                  break;
               }
            }

            this.refreshBlockEntityPreviews();
         }
      }

      private void capture(TerrainSectionKey key) {
         int chunkX = key.x();
         int chunkZ = key.z();
         if (!this.removedSections.contains(key) && this.level.hasChunk(chunkX, chunkZ)) {
            if (!TerrainFixedCorePolicy.sectionMayContainDetail(
               this.coreCenterX, this.coreCenterY, this.coreCenterZ, key.minBlockX(), key.minBlockY(), key.minBlockZ()
            )) {
               this.captureMaterialLod(key);
            } else {
               TerrainPreviewManager.Session.CapturedNeighborhood neighborhood = this.captureNeighborhood(key);
               this.blockEntityPositions.removeIf(pos -> TerrainSectionKey.fromBlock(pos.getX(), pos.getY(), pos.getZ()).equals(key));
               List<TerrainBlockSectionSnapshot.VisibleBlock> detail = new ArrayList<>();
               List<TerrainMapColorAggregator.Sample<BlockState>> wire = new ArrayList<>();

               for (int localY = 0; localY < 16; localY++) {
                  int worldY = key.minBlockY() + localY;
                  if (worldY >= this.bounds.minY()
                     && worldY <= this.bounds.maxY()
                     && worldY >= this.level.getMinBuildHeight()
                     && worldY < this.level.getMaxBuildHeight()) {
                     for (int localZ = 0; localZ < 16; localZ++) {
                        int worldZ = key.minBlockZ() + localZ;
                        if (worldZ >= this.bounds.minZ() && worldZ <= this.bounds.maxZ()) {
                           for (int localX = 0; localX < 16; localX++) {
                              int worldX = key.minBlockX() + localX;
                              if (worldX >= this.bounds.minX() && worldX <= this.bounds.maxX()) {
                                 BlockState state = neighborhood.states().get(TerrainNeighborhoodIndex.index(localX, localY, localZ));
                                 if (isRenderableState(state)) {
                                    this.cursor.set(worldX, worldY, worldZ);
                                    TerrainBlockSectionSnapshot.VisibleBlock block = new TerrainBlockSectionSnapshot.VisibleBlock(
                                       localX,
                                       localY,
                                       localZ,
                                       1,
                                       state,
                                       new TerrainTintColors(
                                          this.safeBlockTint(BiomeColors.GRASS_COLOR_RESOLVER),
                                          this.safeBlockTint(BiomeColors.FOLIAGE_COLOR_RESOLVER),
                                          this.safeBlockTint(BiomeColors.FOLIAGE_COLOR_RESOLVER),
                                          this.safeBlockTint(BiomeColors.WATER_COLOR_RESOLVER)
                                       ),
                                       this.safeTintLayers(state),
                                       this.safePackedLightAtCursor()
                                    );
                                    if (this.rendersDetail(worldX, worldY, worldZ)) {
                                       detail.add(block);
                                       if (state.hasBlockEntity() && this.blockEntityPositions.size() < 128) {
                                          this.blockEntityPositions.add(this.cursor.immutable());
                                       }
                                    } else {
                                       int mapColor = this.safeMapColor(state);
                                       if (mapColor != 0) {
                                          wire.add(new TerrainMapColorAggregator.Sample<>(localX, localY, localZ, state, mapColor));
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }

               long estimatedBytes = 256L + detail.size() * 48L + neighborhood.states().size() * 9L;
               if (detail.isEmpty()) {
                  this.sections.remove(key);
                  this.fullDetailSectionKeys.remove(key);
               } else {
                  this.sections
                     .put(
                        key,
                        new TerrainBlockSectionSnapshot(key, detail, this.maskedNeighborhood(key, neighborhood.states()), neighborhood.light(), estimatedBytes)
                     );
                  this.fullDetailSectionKeys.add(key);
               }

               if (wire.isEmpty()) {
                  this.overviewBySection.remove(key);
               } else {
                  this.overviewBySection.put(key, TerrainPreviewManager.mapColorOverview(key, wire, this.overviewCellSize(key)));
               }

               this.frameDirty = true;
            }
         }
      }

      private void captureMaterialLod(TerrainSectionKey key) {
         List<TerrainMapColorAggregator.Sample<BlockState>> visible = new ArrayList<>();

         for (int localY = 0; localY < 16; localY++) {
            int worldY = key.minBlockY() + localY;
            if (worldY >= this.bounds.minY()
               && worldY <= this.bounds.maxY()
               && worldY >= this.level.getMinBuildHeight()
               && worldY < this.level.getMaxBuildHeight()) {
               for (int localZ = 0; localZ < 16; localZ++) {
                  int worldZ = key.minBlockZ() + localZ;
                  if (worldZ >= this.bounds.minZ() && worldZ <= this.bounds.maxZ()) {
                     for (int localX = 0; localX < 16; localX++) {
                        int worldX = key.minBlockX() + localX;
                        if (worldX >= this.bounds.minX() && worldX <= this.bounds.maxX()) {
                           BlockState state = this.safeBlockState(worldX, worldY, worldZ);
                           if (state != null && isRenderableState(state)) {
                              int mapColor = this.safeMapColor(state);
                              if (mapColor != 0) {
                                 visible.add(new TerrainMapColorAggregator.Sample<>(localX, localY, localZ, state, mapColor));
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (visible.isEmpty()) {
            this.sections.remove(key);
            this.fullDetailSectionKeys.remove(key);
            this.overviewBySection.remove(key);
         } else {
            int cellSize = this.overviewCellSize(key);
            this.sections.remove(key);
            this.fullDetailSectionKeys.remove(key);
            this.overviewBySection.put(key, TerrainPreviewManager.mapColorOverview(key, visible, cellSize));
         }

         this.frameDirty = true;
      }

      private boolean rendersDetail(int worldX, int worldY, int worldZ) {
         return TerrainFixedCorePolicy.rendersBlock(this.coreSeed, this.coreCenterX, this.coreCenterY, this.coreCenterZ, worldX, worldY, worldZ);
      }

      private int overviewCellSize(TerrainSectionKey key) {
         double centerX = key.minBlockX() + 8.0;
         double centerY = key.minBlockY() + 8.0;
         double centerZ = key.minBlockZ() + 8.0;
         double dx = centerX - this.coreCenterX;
         double dy = centerY - this.coreCenterY;
         double dz = centerZ - this.coreCenterZ;
         return TerrainFixedCorePolicy.overviewCellSize(Math.sqrt(dx * dx + dy * dy + dz * dz));
      }

      private static boolean isRenderableState(BlockState state) {
         try {
            return state.getRenderShape() == RenderShape.MODEL || !state.getFluidState().isEmpty();
         } catch (Throwable var3) {
            if (var3 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else if (var3 instanceof Error fatal) {
               throw fatal;
            } else {
               return false;
            }
         }
      }

      private List<BlockState> maskedNeighborhood(TerrainSectionKey key, List<BlockState> source) {
         List<BlockState> masked = new ArrayList<>(source);
         int index = 0;

         for (int localY = -2; localY <= 17; localY++) {
            for (int localZ = -2; localZ <= 17; localZ++) {
               for (int localX = -2; localX <= 17; localX++) {
                  int worldX = key.minBlockX() + localX;
                  int worldY = key.minBlockY() + localY;
                  int worldZ = key.minBlockZ() + localZ;
                  if (!this.rendersDetail(worldX, worldY, worldZ)) {
                     masked.set(index, Blocks.AIR.defaultBlockState());
                  }

                  index++;
               }
            }
         }

         return List.copyOf(masked);
      }

      private void markCompiled(TerrainBlockSectionSnapshot source) {
      }

      private TerrainPreviewManager.Session.CapturedNeighborhood captureNeighborhood(TerrainSectionKey key) {
         List<BlockState> states = new ArrayList<>(8000);
         byte[] light = new byte[8000];
         boolean[] loadedChunks = this.captureLoadedChunks(key);
         boolean[] readableColumns = this.captureReadableColumns(key, loadedChunks);
         int index = 0;

         for (int localY = -2; localY <= 17; localY++) {
            int worldY = key.minBlockY() + localY;
            boolean validY = worldY >= this.level.getMinBuildHeight()
               && worldY < this.level.getMaxBuildHeight()
               && worldY >= this.bounds.minY()
               && worldY <= this.bounds.maxY();

            for (int localZ = -2; localZ <= 17; localZ++) {
               int worldZ = key.minBlockZ() + localZ;
               int columnBase = (localZ - -2) * 20;

               for (int localX = -2; localX <= 17; localX++) {
                  int worldX = key.minBlockX() + localX;
                  boolean readable = validY && readableColumns[columnBase + localX - -2];
                  BlockState state = readable ? this.safeBlockState(worldX, worldY, worldZ) : null;
                  states.add(state != null ? state : Blocks.AIR.defaultBlockState());
                  light[index++] = state != null ? this.safePackedLightAtCursor() : 0;
               }
            }
         }

         return new TerrainPreviewManager.Session.CapturedNeighborhood(states, light);
      }

      private boolean[] captureReadableColumns(TerrainSectionKey key, boolean[] loadedChunks) {
         boolean[] readable = new boolean[400];
         int index = 0;

         for (int localZ = -2; localZ <= 17; localZ++) {
            int worldZ = key.minBlockZ() + localZ;
            boolean validZ = worldZ >= this.bounds.minZ() && worldZ <= this.bounds.maxZ();

            for (int localX = -2; localX <= 17; localX++) {
               int worldX = key.minBlockX() + localX;
               readable[index++] = validZ
                  && worldX >= this.bounds.minX()
                  && worldX <= this.bounds.maxX()
                  && loadedChunks[TerrainNeighborhoodIndex.neighborChunkIndex(localX, localZ)];
            }
         }

         return readable;
      }

      private boolean[] captureLoadedChunks(TerrainSectionKey key) {
         boolean[] loaded = new boolean[9];

         for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
               loaded[(offsetZ + 1) * 3 + offsetX + 1] = this.level.hasChunk(key.x() + offsetX, key.z() + offsetZ);
            }
         }

         return loaded;
      }

      private byte safePackedLightAtCursor() {
         try {
            return TerrainPackedLight.pack(this.level.getBrightness(LightLayer.BLOCK, this.cursor), this.level.getBrightness(LightLayer.SKY, this.cursor));
         } catch (Throwable var3) {
            if (var3 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               return 0;
            }
         }
      }

      private BlockState safeBlockState(int x, int y, int z) {
         try {
            this.cursor.set(x, y, z);
            return this.level.getBlockState(this.cursor);
         } catch (Throwable var6) {
            if (var6 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               return null;
            }
         }
      }

      private int safeMapColor(BlockState state) {
         try {
            MapColor mapColor = state.getMapColor(this.level, this.cursor);
            return mapColor == MapColor.NONE ? 0 : mapColor.col;
         } catch (Throwable var6) {
            if (var6 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               try {
                  MapColor fallback = state.getBlock().defaultMapColor();
                  return fallback == MapColor.NONE ? 0 : fallback.col;
               } catch (Throwable var5) {
                  if (var5 instanceof VirtualMachineError fatal) {
                     throw fatal;
                  } else {
                     return 0;
                  }
               }
            }
         }
      }

      private int safeBlockTint(ColorResolver resolver) {
         try {
            return this.level.getBlockTint(this.cursor, resolver);
         } catch (Throwable var4) {
            if (var4 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               return -1;
            }
         }
      }

      private List<Integer> safeTintLayers(BlockState state) {
         try {
            BlockColors blockColors = Minecraft.getInstance().getBlockColors();
            List<Integer> colors = new ArrayList<>(4);

            for (int index = 0; index < 32; index++) {
               try {
                  int color = blockColors.getColor(state, this.level, this.cursor, index);
                  if (color == -1) {
                     break;
                  }

                  colors.add(color);
               } catch (Throwable var7) {
                  if (var7 instanceof VirtualMachineError fatal) {
                     throw fatal;
                  }

                  colors.add(-1);
               }
            }

            return List.copyOf(colors);
         } catch (Throwable var8) {
            if (var8 instanceof VirtualMachineError fatal) {
               throw fatal;
            } else {
               return List.of();
            }
         }
      }

      private void refreshBlockEntityPreviews() {
         if (this.blockEntityPositions.isEmpty()) {
            if (!this.blockEntityPreviews.isEmpty()) {
               this.blockEntityPreviews = List.of();
               this.frameDirty = true;
            }
         } else {
            List<TerrainBlockEntityPreview> refreshed = new ArrayList<>(this.blockEntityPositions.size());

            for (BlockPos pos : this.blockEntityPositions) {
               if (refreshed.size() < 128 && this.level.hasChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16))) {
                  try {
                     BlockEntity blockEntity = this.level.getBlockEntity(pos);
                     if (blockEntity != null) {
                        TerrainBlockEntityPreview preview = extractBlockEntityPreview(blockEntity);
                        if (preview != null) {
                           refreshed.add(preview);
                        }
                     }
                  } catch (Throwable var6) {
                     if (var6 instanceof VirtualMachineError fatal) {
                        throw fatal;
                     }
                  }
               }
            }

            this.blockEntityPreviews = List.copyOf(refreshed);
            this.frameDirty = true;
         }
      }

      private static TerrainBlockEntityPreview extractBlockEntityPreview(BlockEntity blockEntity) {
         BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
         return dispatcher.getRenderer(blockEntity) == null ? null : new TerrainBlockEntityPreview(blockEntity.getBlockPos(), blockEntity);
      }

      private void enqueue(TerrainSectionKey key) {
         if (!this.removedSections.contains(key)
            && this.bounds.intersects(key)
            && this.sections.get(key).isEmpty()
            && this.queued.size() < 512
            && this.queued.add(key)) {
            this.pending.addLast(key);
         }
      }

      private void enqueueFirst(TerrainSectionKey key) {
         if (!this.removedSections.contains(key) && this.bounds.intersects(key) && this.queued.add(key)) {
            this.pending.addFirst(key);
         }
      }

      private TerrainPreviewFrame frame() {
         if (!this.frameDirty && this.cachedFrame != null) {
            return this.cachedFrame;
         } else {
            List<TerrainBlockSectionSnapshot> values = new ArrayList<>(this.sections.snapshot().values());
            values.sort(
               Comparator.<TerrainBlockSectionSnapshot>comparingInt(snapshot -> snapshot.section().y())
                  .thenComparingInt(snapshot -> snapshot.section().z())
                  .thenComparingInt(snapshot -> snapshot.section().x())
            );
            List<TerrainOverviewCell> overview = new ArrayList<>(this.unknownSections.values());
            this.overviewBySection.values().forEach(overview::addAll);
            List<TerrainWireframeMesher.Segment> wireframe = TerrainWireframeMesher.mesh(overview, this.bounds);
            this.cachedFrame = new TerrainPreviewFrame(
               this.generation,
               this.origin.getX(),
               this.origin.getY(),
               this.origin.getZ(),
               this.coreCenterX,
               this.coreCenterY,
               this.coreCenterZ,
               this.bounds,
               overview,
               wireframe,
               List.of(),
               values,
               this.fullDetailSectionKeys,
               this.removedSections,
               this.blockEntityPreviews,
               this.pending.size(),
               this.sections.size() + this.overviewBySection.size()
            );
            this.frameDirty = false;
            return this.cachedFrame;
         }
      }

      private void close() {
         this.closed = true;
         this.dirty.clear();
         this.sections.clear();
         this.pending.clear();
         this.queued.clear();
         this.removedSections.clear();
         this.fullDetailSectionKeys.clear();
         this.unknownSections.clear();
         this.overviewBySection.clear();
         this.blockEntityPositions.clear();
         this.blockEntityPreviews = List.of();
      }

      private void markChunkUnloaded(int chunkX, int chunkZ) {
         for (TerrainSectionKey key : TerrainPreviewManager.sectionKeysForChunk(
            chunkX, chunkZ, this.bounds, this.level.getMinBuildHeight(), this.level.getMaxBuildHeight()
         )) {
            this.sections.remove(key);
            this.fullDetailSectionKeys.remove(key);
            this.overviewBySection.remove(key);
            this.blockEntityPositions.removeIf(pos -> TerrainSectionKey.fromBlock(pos.getX(), pos.getY(), pos.getZ()).equals(key));
            this.pending.removeIf(key::equals);
            this.queued.remove(key);
            this.removedSections.add(key);
            this.rememberUnknown(key);
         }

         this.frameDirty = true;
      }

      private void markChunkLoaded(int chunkX, int chunkZ) {
         for (TerrainSectionKey key : TerrainPreviewManager.sectionKeysForChunk(
            chunkX, chunkZ, this.bounds, this.level.getMinBuildHeight(), this.level.getMaxBuildHeight()
         )) {
            this.removedSections.remove(key);
            this.unknownSections.remove(key);
            this.overviewBySection.remove(key);
            this.enqueue(key);
         }

         this.frameDirty = true;
      }

      private void rememberUnknown(TerrainSectionKey key) {
         if (this.bounds.intersects(key) && !this.unknownSections.containsKey(key)) {
            while (this.unknownSections.size() >= 256) {
               Iterator<Entry<TerrainSectionKey, TerrainOverviewCell>> iterator = this.unknownSections.entrySet().iterator();
               iterator.next();
               iterator.remove();
            }

            this.unknownSections
               .put(key, new TerrainOverviewCell(key.minBlockX(), key.minBlockY(), key.minBlockZ(), 16, TerrainCellSample.RenderCategory.UNKNOWN));
            this.frameDirty = true;
         }
      }

      private record CapturedNeighborhood(List<BlockState> states, byte[] light) {
      }
   }
}
