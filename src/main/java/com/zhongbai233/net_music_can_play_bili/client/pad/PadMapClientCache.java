package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.Level;

public final class PadMapClientCache {
   private static final PadMapProperties.Cache PROPERTIES = PadMapProperties.cache();
   private static final int RESAMPLE_CHUNK_DISTANCE = PROPERTIES.resampleChunkDistance();
   private static final int CHUNKS_PER_TICK = PROPERTIES.chunksPerTick();
   private static final int FAST_CHUNKS_PER_TICK = PROPERTIES.fastChunksPerTick();
   private static final int CELLS_PER_CHUNK_BUDGET = PROPERTIES.cellsPerChunkBudget();
   private static final int INITIAL_VISIBLE_BURST_CELLS = PROPERTIES.initialVisibleBurstCells();
   private static final int MAX_JOB_LAG_CHUNKS = PROPERTIES.maxJobLagChunks();
   private static final int DIRTY_CHUNKS_PER_TICK = PROPERTIES.dirtyChunksPerTick();
   private static final int MAP_UPDATE_INTERVAL_TICKS = PROPERTIES.updateIntervalTicks();
   private static final int UNKNOWN_RETRY_INTERVAL_TICKS = PROPERTIES.unknownRetryTicks();
   private static final int RECENTER_BLOCKS = PROPERTIES.recenterBlocks();
   private static final int INDOOR_RECENTER_BLOCKS = PROPERTIES.indoorRecenterBlocks();
   private static final int INDOOR_CEILING_SCAN_BLOCKS = PROPERTIES.indoorCeilingScanBlocks();
   private static final int INDOOR_CEILING_MIN_HITS = PROPERTIES.indoorCeilingMinHits();
   private static final int INDOOR_ARTIFICIAL_MIN_HITS = PROPERTIES.indoorArtificialMinHits();
   private static final int INDOOR_ENTER_CONFIRM_TICKS = PROPERTIES.indoorEnterConfirmTicks();
   private static final int INDOOR_EXIT_CONFIRM_TICKS = PROPERTIES.indoorExitConfirmTicks();
   private static final int INDOOR_FLOOR_CHANGE_CONFIRM_TICKS = PROPERTIES.indoorFloorConfirmTicks();
   private static final int INDOOR_JUMP_TOLERANCE_BLOCKS = PROPERTIES.indoorJumpToleranceBlocks();
   private static final float OUTDOOR_ZOOM = PROPERTIES.outdoorZoom();
   private static final float INDOOR_ZOOM = PROPERTIES.indoorZoom();
   private static final float INDOOR_DISPLAY_SCALE = PROPERTIES.indoorDisplayScale();
   private static final int PREVIEW_CHUNKS = PROPERTIES.previewChunks();
   private static final int CELL_CACHE_LIMIT = PROPERTIES.cellCacheLimit();
   private static final int DIRTY_CHUNK_LIMIT = PROPERTIES.dirtyChunkLimit();
   private static final int DISK_FLUSH_INTERVAL_TICKS = PROPERTIES.diskFlushTicks();
   private static final boolean DISK_CACHE_ENABLED = PROPERTIES.diskCacheEnabled();
   private static final ExecutorService DISK_FLUSH_EXECUTOR = Executors.newSingleThreadExecutor(NetMusicThreadFactory.daemon("pad-map-disk-flush"));
   private static final AtomicBoolean DISK_FLUSH_IN_PROGRESS = new AtomicBoolean(false);
   private static final PadMapCellMemoryCache CELL_CACHE = new PadMapCellMemoryCache(CELL_CACHE_LIMIT);
   private static final PadMapDirtyChunkTracker DIRTY_CHUNKS = new PadMapDirtyChunkTracker(DIRTY_CHUNK_LIMIT);
   private static final PadMapViewSnapshotCache VIEW_SNAPSHOTS = new PadMapViewSnapshotCache();
   private static PadMapSnapshot completed;
   private static PadMapSnapshot placeholder;
   private static PadMapClientCache.Job activeJob;
   private static PadMapViewProfile completedProfile = PadMapViewProfile.OUTDOOR;
   private static boolean manualViewActive;
   private static int manualCenterX;
   private static int manualCenterZ;
   private static float manualZoom = OUTDOOR_ZOOM;
   private static String activeDiskScope;
   private static String syncedWorldScopeId;
   private static boolean diskCacheLoaded;
   private static boolean diskCacheDirty;
   private static boolean snapshotCacheDirty;
   private static int diskFlushTicker;
   private static final PadMapViewProfileStabilizer PROFILE_STABILIZER = new PadMapViewProfileStabilizer(
      INDOOR_ENTER_CONFIRM_TICKS, INDOOR_EXIT_CONFIRM_TICKS, INDOOR_FLOOR_CHANGE_CONFIRM_TICKS, INDOOR_JUMP_TOLERANCE_BLOCKS
   );
   private static final PadMapViewProfileDetector PROFILE_DETECTOR = new PadMapViewProfileDetector(
      INDOOR_CEILING_SCAN_BLOCKS, INDOOR_CEILING_MIN_HITS, INDOOR_ARTIFICIAL_MIN_HITS
   );
   private static final PadMapJobScheduler JOB_SCHEDULER = new PadMapJobScheduler(
      RECENTER_BLOCKS, INDOOR_RECENTER_BLOCKS, MAX_JOB_LAG_CHUNKS, RESAMPLE_CHUNK_DISTANCE, CHUNKS_PER_TICK, FAST_CHUNKS_PER_TICK
   );
   private static final PadMapSnapshotComposer SNAPSHOT_COMPOSER = new PadMapSnapshotComposer(INDOOR_DISPLAY_SCALE);
   private static int mapUpdateCountdown;
   private static long nextUnknownRetryTick;
   private static Level activeLevel;

   private PadMapClientCache() {
   }

   public static PadMapSnapshot snapshot(int playerX, int playerZ) {
      return completed == null ? placeholder(playerX, playerZ) : completed;
   }

   public static void setManualView(int centerX, int centerZ, float zoom) {
      manualViewActive = true;
      manualCenterX = centerX;
      manualCenterZ = centerZ;
      manualZoom = zoom;
   }

   public static void clearManualView() {
      manualViewActive = false;
   }

   public static void tick() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && minecraft.level != null) {
         if (activeLevel != minecraft.level) {
            clearLevelRuntimeState();
            activeLevel = minecraft.level;
         }

         updateDiskScope(minecraft);
         loadDiskCacheIfNeeded();
         if (!isPadRelevant(minecraft)) {
            activeJob = null;
            mapUpdateCountdown = 0;
            maybeFlushDiskCache();
         } else if (mapUpdateCountdown > 0) {
            mapUpdateCountdown--;
            maybeFlushDiskCache();
         } else {
            mapUpdateCountdown = MAP_UPDATE_INTERVAL_TICKS - 1;
            int playerX = minecraft.player.blockPosition().getX();
            int playerY = minecraft.player.blockPosition().getY();
            int playerZ = minecraft.player.blockPosition().getZ();
            int targetX = manualViewActive ? manualCenterX : playerX;
            int targetZ = manualViewActive ? manualCenterZ : playerZ;
            float targetZoom = manualViewActive ? manualZoom : OUTDOOR_ZOOM;
            BlockPos profilePos = manualViewActive ? new BlockPos(targetX, playerY, targetZ) : minecraft.player.blockPosition();
            PadMapViewProfile rawProfile = PROFILE_DETECTOR.detect(minecraft.level, profilePos);
            int rawFloorY = rawProfile == PadMapViewProfile.INDOOR
               ? PROFILE_DETECTOR.normalizeIndoorFloorY(minecraft.level, profilePos)
               : PROFILE_DETECTOR.outdoorLayerY();
            PadMapViewProfileStabilizer.Result stableView = PROFILE_STABILIZER.update(rawProfile, rawFloorY);
            PadMapViewProfile profile = stableView.profile();
            int targetY = stableView.floorY();
            if (!manualViewActive) {
               targetZoom = profile == PadMapViewProfile.INDOOR ? INDOOR_ZOOM : OUTDOOR_ZOOM;
            }

            cancelStaleJob(targetX, targetY, targetZ, profile, targetZoom);
            activateViewSnapshot(targetX, targetY, targetZ, profile, targetZoom);
            PadMapSnapshot dirtySeed = invalidateDirtyChunks(minecraft.level, targetY, profile, targetZoom);
            if (dirtySeed != null) {
               activeJob = new PadMapClientCache.Job(minecraft.level, targetX, targetZ, targetY, profile, targetZoom, dirtySeed);
            }

            maybeStartJob(minecraft.level, targetX, targetY, targetZ, profile, targetZoom);
            maybeStartUnknownRetryJob(minecraft.level, targetX, targetY, targetZ, profile, targetZoom);
            publishTransitionIfMissing(targetX, targetY, targetZ, profile, targetZoom);
            if (activeJob != null) {
               long started = System.nanoTime();
               boolean done = activeJob.step(chunksPerTickFor(playerX, playerZ));
               PadPerfLogger.recordSampleStep(System.nanoTime() - started);
               if (done) {
                  completed = activeJob.finish();
                  completedProfile = activeJob.profile;
                  VIEW_SNAPSHOTS.put(completedProfile, completed);
                  if (completedProfile == PadMapViewProfile.OUTDOOR && !completed.hasUnknownTiles()) {
                     snapshotCacheDirty = true;
                  }

                  int steps = activeJob.steps();
                  activeJob = null;
                  PadPerfLogger.recordSampleJobComplete(steps);
               } else if (activeJob.shouldPublishPreview()) {
                  completed = activeJob.preview();
               }
            }

            maybeFlushDiskCache();
         }
      } else {
         if (activeLevel != null) {
            flushDiskCache();
            clearLevelRuntimeState();
            activeLevel = null;
         }
      }
   }

   private static void activateViewSnapshot(int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
      if (completed == null || completedProfile != profile || completed.centerY() != playerY || completed.cellSizeBlocks() != cellSize) {
         PadMapSnapshot cached = VIEW_SNAPSHOTS.get(profile, playerY, cellSize);
         completed = cached;
         completedProfile = profile;
      }
   }

   private static void publishTransitionIfMissing(int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      if (completed == null) {
         completed = transitionSnapshot(playerX, playerY, playerZ, profile, zoom);
         completedProfile = profile;
      }
   }

   private static void cancelStaleJob(int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      if (JOB_SCHEDULER.shouldCancel(schedulerView(activeJob), playerX, playerY, playerZ, profile, zoom)) {
         activeJob = null;
      }
   }

   private static int chunksPerTickFor(int playerX, int playerZ) {
      return JOB_SCHEDULER.chunksPerTick(schedulerView(activeJob), playerX, playerZ);
   }

   private static PadMapJobScheduler.JobView schedulerView(PadMapClientCache.Job job) {
      return job == null ? null : job.schedulerView();
   }

   private static void maybeStartJob(Level level, int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      if (activeJob == null) {
         if (JOB_SCHEDULER.shouldStart(completed, completedProfile, playerX, playerY, playerZ, profile)) {
            activeJob = new PadMapClientCache.Job(
               level, playerX, playerZ, playerY, profile, zoom, JOB_SCHEDULER.canSeedPrevious(completed, completedProfile, profile, zoom) ? completed : null
            );
         }
      }
   }

   private static void maybeStartUnknownRetryJob(Level level, int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      if (activeJob == null && completed != null && completedProfile == profile && completed.hasUnknownTiles()) {
         long gameTime = MonotonicMediaClock.nowTick();
         if (gameTime >= nextUnknownRetryTick) {
            nextUnknownRetryTick = gameTime + UNKNOWN_RETRY_INTERVAL_TICKS;
            activeJob = new PadMapClientCache.Job(
               level, playerX, playerZ, playerY, profile, zoom, JOB_SCHEDULER.canSeedPrevious(completed, completedProfile, profile, zoom) ? completed : null
            );
         }
      }
   }

   public static void markChunkDirty(Level level, int chunkX, int chunkZ) {
      if (level != null) {
         DIRTY_CHUNKS.mark(level.dimension().location().toString(), chunkX, chunkZ);
      }
   }

   public static void markBlockDirty(Level level, BlockPos pos) {
      if (pos != null) {
         markChunkDirty(level, Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
      }
   }

   private static PadMapSnapshot invalidateDirtyChunks(Level level, int centerY, PadMapViewProfile profile, float zoom) {
      if (activeJob == null && completed != null) {
         int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
         String dimension = level.dimension().location().toString();
         List<PadMapDirtyChunkTracker.Key> dirty = DIRTY_CHUNKS.drainForDimension(dimension, DIRTY_CHUNKS_PER_TICK);
         if (dirty.isEmpty()) {
            return null;
         } else {
            VIEW_SNAPSHOTS.invalidate(dirty);
            PadMapSnapshot invalidatedSnapshot = PadMapDirtyInvalidation.invalidateSnapshot(completed, cellSize, dirty);
            boolean invalidated = invalidatedSnapshot != null;
            if (profile == PadMapViewProfile.OUTDOOR) {
               List<PadMapDirtyInvalidation.CellRange> dirtyCellRanges = PadMapDirtyInvalidation.cellRanges(dirty, cellSize);
               int before = CELL_CACHE.size();

               for (PadMapDirtyInvalidation.CellRange dirtyCellRange : dirtyCellRanges) {
                  for (int cellZ = dirtyCellRange.minCellZ(); cellZ <= dirtyCellRange.maxCellZ(); cellZ++) {
                     for (int cellX = dirtyCellRange.minCellX(); cellX <= dirtyCellRange.maxCellX(); cellX++) {
                        CELL_CACHE.remove(new PadMapCellMemoryCache.Key(dimension, cellSize, cellX, cellZ));
                     }
                  }
               }

               invalidated |= CELL_CACHE.size() != before;
            }

            if (!invalidated) {
               return null;
            } else {
               diskCacheDirty = true;
               return invalidatedSnapshot;
            }
         }
      } else {
         return null;
      }
   }

   private static void resetProfileStability() {
      PROFILE_STABILIZER.reset();
   }

   private static void clearLevelRuntimeState() {
      activeJob = null;
      completed = null;
      placeholder = null;
      VIEW_SNAPSHOTS.clear();
      DIRTY_CHUNKS.clear();
      mapUpdateCountdown = 0;
      nextUnknownRetryTick = 0L;
      manualViewActive = false;
      resetProfileStability();
   }

   private static boolean isPadRelevant(Minecraft minecraft) {
      return PadFocusState.active()
         ? true
         : minecraft.player.getMainHandItem().getItem() instanceof PadItem || minecraft.player.getOffhandItem().getItem() instanceof PadItem;
   }

   private static PadMapSnapshot placeholder(int playerX, int playerZ) {
      if (placeholder != null) {
         return placeholder;
      } else {
         int size = PadMapSampler.DEFAULT_SIZE;
         int height = PadMapSampler.DEFAULT_HEIGHT;
         PadMapTileKind[] tiles = new PadMapTileKind[size * height];
         Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
         placeholder = new PadMapSnapshot(
            Math.floorDiv(playerX, 16) * 16, PROFILE_DETECTOR.outdoorLayerY(), Math.floorDiv(playerZ, 16) * 16, 1, size, height, tiles
         );
         return placeholder;
      }
   }

   private static PadMapSnapshot transitionSnapshot(int playerX, int playerY, int playerZ, PadMapViewProfile profile, float zoom) {
      int width = PadMapSampler.DEFAULT_WIDTH;
      int height = PadMapSampler.DEFAULT_HEIGHT;
      int cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
      PadMapTileKind[] tiles = new PadMapTileKind[width * height];
      Arrays.fill(tiles, PadMapTileKind.UNKNOWN);
      return SNAPSHOT_COMPOSER.compose(playerX, playerY, playerZ, cellSize, width, height, tiles, profile);
   }

   private static PadMapTileKind cachedClassify(Level level, MutableBlockPos mutable, String dimension, int worldX, int worldZ, int cellSize) {
      int cellX = Math.floorDiv(worldX, cellSize);
      int cellZ = Math.floorDiv(worldZ, cellSize);
      PadMapCellMemoryCache.Key key = new PadMapCellMemoryCache.Key(dimension, cellSize, cellX, cellZ);
      PadMapTileKind cached = CELL_CACHE.get(key);
      if (cached != null) {
         PadPerfLogger.recordCellCacheHit();
         return cached;
      } else {
         PadPerfLogger.recordCellCacheMiss();
         PadMapTileKind kind = PadMapSampler.classifyCell(level, mutable, cellX * cellSize, cellZ * cellSize, cellSize);
         if (kind == PadMapTileKind.UNKNOWN) {
            return kind;
         } else {
            CELL_CACHE.put(key, kind);
            diskCacheDirty = true;
            return kind;
         }
      }
   }

   public static int memoryCacheSize() {
      return CELL_CACHE.size();
   }

   public static String describeStatus() {
      PadMapClientCache.Job job = activeJob;
      int dirtySize = DIRTY_CHUNKS.size();
      String completedInfo = completed == null
         ? "completed=none"
         : "completed="
            + completed.width()
            + "x"
            + completed.height()
            + " cell="
            + completed.cellSizeBlocks()
            + " center=("
            + completed.centerX()
            + ","
            + completed.centerZ()
            + ") profile="
            + completedProfile;
      String jobInfo = job == null ? "job=none" : job.describe();
      return completedInfo
         + ", "
         + jobInfo
         + ", memoryCells="
         + memoryCacheSize()
         + ", dirtyChunks="
         + dirtySize
         + ", cellBudget="
         + CHUNKS_PER_TICK * CELLS_PER_CHUNK_BUDGET
         + "/"
         + FAST_CHUNKS_PER_TICK * CELLS_PER_CHUNK_BUDGET
         + ", initialBurst="
         + INITIAL_VISIBLE_BURST_CELLS
         + ", recenter="
         + RECENTER_BLOCKS
         + ", disk="
         + diskCachePath();
   }

   public static void setServerWorldScope(String worldScopeId, String worldName) {
      String normalizedScopeId = isBlank(worldScopeId) ? null : worldScopeId.trim();
      if (!Objects.equals(syncedWorldScopeId, normalizedScopeId)) {
         flushDiskCache();
         syncedWorldScopeId = normalizedScopeId;
         clearAllCaches(false);
         activeDiskScope = null;
         diskCacheLoaded = false;
      }
   }

   public static Path diskCachePath() {
      return PadMapDiskCachePaths.cells(Minecraft.getInstance(), syncedWorldScopeId);
   }

   public static Path snapshotCachePath() {
      return PadMapDiskCachePaths.snapshot(Minecraft.getInstance(), syncedWorldScopeId);
   }

   public static void flushDiskCache() {
      if (DISK_CACHE_ENABLED && diskCacheLoaded && hasSyncedDiskScope()) {
         if (DISK_FLUSH_IN_PROGRESS.compareAndSet(false, true)) {
            PadMapCellDiskCodec.Snapshot cells = captureCellDiskSnapshot();
            PadMapSnapshotDiskCodec.Snapshot snapshot = captureSnapshotDiskSnapshot();
            if (cells == null && snapshot == null) {
               DISK_FLUSH_IN_PROGRESS.set(false);
            } else {
               try {
                  DISK_FLUSH_EXECUTOR.execute(() -> {
                     try {
                        PadMapCellDiskCodec.write(cells);
                        PadMapSnapshotDiskCodec.write(snapshot);
                     } finally {
                        DISK_FLUSH_IN_PROGRESS.set(false);
                     }
                  });
               } catch (RuntimeException var3) {
                  DISK_FLUSH_IN_PROGRESS.set(false);
                  diskCacheDirty |= cells != null;
                  snapshotCacheDirty |= snapshot != null;
                  throw var3;
               }
            }
         }
      }
   }

   private static PadMapCellDiskCodec.Snapshot captureCellDiskSnapshot() {
      if (!diskCacheDirty) {
         return null;
      } else {
         Path path = diskCachePath();
         List<PadMapCellDiskCodec.Entry> entries = new ArrayList<>();

         for (PadMapCellMemoryCache.Entry entry : CELL_CACHE.entries()) {
            if (entry.kind() != PadMapTileKind.UNKNOWN) {
               PadMapCellMemoryCache.Key key = entry.key();
               entries.add(new PadMapCellDiskCodec.Entry(key.dimension(), key.cellSize(), key.cellX(), key.cellZ(), entry.kind()));
            }
         }

         diskCacheDirty = false;
         return new PadMapCellDiskCodec.Snapshot(path, entries);
      }
   }

   private static PadMapSnapshotDiskCodec.Snapshot captureSnapshotDiskSnapshot() {
      if (snapshotCacheDirty && completed != null && completed.tiles() != null && !completed.hasUnknownTiles()) {
         Path path = snapshotCachePath();
         PadMapSnapshot snapshot = completed;
         PadMapTileKind[] tiles = Arrays.copyOf(snapshot.tiles(), snapshot.tiles().length);
         snapshotCacheDirty = false;
         return new PadMapSnapshotDiskCodec.Snapshot(
            path, snapshot.centerX(), snapshot.centerY(), snapshot.centerZ(), snapshot.cellSizeBlocks(), snapshot.width(), snapshot.height(), tiles
         );
      } else {
         return null;
      }
   }

   public static int clearAllCaches(boolean deleteDisk) {
      activeJob = null;
      completed = null;
      placeholder = null;
      VIEW_SNAPSHOTS.clear();
      DIRTY_CHUNKS.clear();
      resetProfileStability();
      nextUnknownRetryTick = 0L;
      int previousSize = CELL_CACHE.size();
      CELL_CACHE.clear();
      diskCacheLoaded = true;
      diskCacheDirty = false;
      snapshotCacheDirty = false;
      if (deleteDisk) {
         try {
            Files.deleteIfExists(diskCachePath());
            Files.deleteIfExists(snapshotCachePath());
         } catch (IOException var3) {
         }
      }

      return previousSize;
   }

   static void clearMemorySnapshots() {
      activeJob = null;
      completed = null;
      placeholder = null;
      VIEW_SNAPSHOTS.clear();
      DIRTY_CHUNKS.clear();
      resetProfileStability();
      nextUnknownRetryTick = 0L;
   }

   private static void maybeFlushDiskCache() {
      if (++diskFlushTicker >= DISK_FLUSH_INTERVAL_TICKS) {
         diskFlushTicker = 0;
         flushDiskCache();
      }
   }

   private static void loadDiskCacheIfNeeded() {
      if (DISK_CACHE_ENABLED && !diskCacheLoaded && hasSyncedDiskScope()) {
         diskCacheLoaded = true;
         loadSnapshotDiskCache();
         Path path = diskCachePath();
         List<PadMapCellDiskCodec.Entry> entries = PadMapCellDiskCodec.read(path, CELL_CACHE_LIMIT);
         if (entries != null) {
            CELL_CACHE.clear();

            for (PadMapCellDiskCodec.Entry entry : entries) {
               CELL_CACHE.put(new PadMapCellMemoryCache.Key(entry.dimension(), entry.cellSize(), entry.cellX(), entry.cellZ()), entry.kind());
            }
         }
      }
   }

   private static void loadSnapshotDiskCache() {
      Path path = snapshotCachePath();
      PadMapSnapshot snapshot = PadMapSnapshotDiskCodec.read(
         path, PadMapSampler.DEFAULT_WIDTH, PadMapSampler.DEFAULT_HEIGHT, PadMapSamplingPolicy.cellSizeForZoom(OUTDOOR_ZOOM)
      );
      if (snapshot != null) {
         completed = snapshot;
         completedProfile = PadMapViewProfile.OUTDOOR;
         VIEW_SNAPSHOTS.put(PadMapViewProfile.OUTDOOR, snapshot);
      }
   }

   private static void updateDiskScope(Minecraft minecraft) {
      String scope = currentDiskScope(minecraft);
      if (!scope.equals(activeDiskScope)) {
         flushDiskCache();
         activeDiskScope = scope;
         activeJob = null;
         completed = null;
         placeholder = null;
         VIEW_SNAPSHOTS.clear();
         DIRTY_CHUNKS.clear();
         resetProfileStability();
         nextUnknownRetryTick = 0L;
         CELL_CACHE.clear();
         diskCacheLoaded = false;
         diskCacheDirty = false;
         snapshotCacheDirty = false;
      }
   }

   private static boolean hasSyncedDiskScope() {
      return !isBlank(syncedWorldScopeId);
   }

   private static String currentDiskScope(Minecraft minecraft) {
      return PadMapDiskCachePaths.worldScope(syncedWorldScopeId);
   }

   private static boolean isBlank(String value) {
      return value == null || value.isBlank();
   }

   static final class Job {
      private final Level level;
      private final int centerX;
      private final int centerZ;
      private final int floorY;
      private final PadMapViewProfile profile;
      private final int size;
      private final int height;
      private final int cellSize;
      private final String dimension;
      private final float zoom;
      private final MutableBlockPos mutable = new MutableBlockPos();
      private final PadMapTileKind[] tiles;
      private final List<PadMapSamplePlan.Cell> pendingCells;
      private final int previewReadyCells;
      private final PadMapJobProgress progress;

      Job(Level level, int centerX, int centerZ, int playerY, PadMapViewProfile profile, float zoom, PadMapSnapshot previous) {
         this.level = level;
         this.zoom = zoom;
         this.cellSize = PadMapSamplingPolicy.cellSizeForZoom(zoom);
         this.dimension = level == null ? "" : level.dimension().location().toString();
         this.centerX = centerX;
         this.centerZ = centerZ;
         this.floorY = playerY;
         this.profile = profile;
         this.size = PadMapSampler.DEFAULT_SIZE;
         this.height = PadMapSampler.DEFAULT_HEIGHT;
         this.tiles = new PadMapTileKind[this.size * this.height];
         Arrays.fill(this.tiles, PadMapTileKind.UNKNOWN);
         PadMapSnapshotSeeder.seed(previous, this.tiles, centerX, this.floorY, centerZ, this.cellSize, this.size, this.height);
         this.pendingCells = PadMapSamplePlan.collectPendingCells(
            centerX, centerZ, this.cellSize, this.size, this.height, PadMapSampler.DEFAULT_VIEW_WIDTH, PadMapSampler.DEFAULT_VIEW_HEIGHT, this.tiles
         );
         if (level != null) {
            this.pendingCells.removeIf(cell -> !PadMapSampler.isCellReady(level, cell.worldX(), cell.worldZ(), this.cellSize));
         }

         this.previewReadyCells = previewReadyCells(this.pendingCells);
         this.progress = new PadMapJobProgress(
            this.pendingCells.size(), PadMapClientCache.CELLS_PER_CHUNK_BUDGET, PadMapClientCache.INITIAL_VISIBLE_BURST_CELLS, PadMapClientCache.PREVIEW_CHUNKS
         );
      }

      private boolean step(int chunkBudget) {
         PadMapJobProgress.Step step = this.progress.beginStep(chunkBudget);

         for (int i = step.startInclusive(); i < step.endExclusive(); i++) {
            PadMapSamplePlan.Cell cell = this.pendingCells.get(i);
            this.tiles[cell.index()] = this.sampleCell(cell.worldX(), cell.worldZ());
         }

         return this.progress.done();
      }

      private boolean shouldPublishPreview() {
         return this.progress.doneCells() >= this.previewReadyCells && this.progress.shouldPublishPreview();
      }

      private static int previewReadyCells(List<PadMapSamplePlan.Cell> cells) {
         int count = 0;

         for (PadMapSamplePlan.Cell cell : cells) {
            if (cell.priority() <= 0) {
               count++;
            }
         }

         return Math.max(1, count);
      }

      private PadMapSnapshot preview() {
         this.progress.markPreviewPublished();
         return PadMapClientCache.SNAPSHOT_COMPOSER
            .compose(this.centerX, this.floorY, this.centerZ, this.cellSize, this.size, this.height, this.tiles, this.profile);
      }

      private PadMapTileKind sampleCell(int worldX, int worldZ) {
         return this.profile == PadMapViewProfile.INDOOR
            ? PadMapSampler.classifyInteriorCell(this.level, this.mutable, worldX, worldZ, this.floorY, this.cellSize)
            : PadMapClientCache.cachedClassify(this.level, this.mutable, this.dimension, worldX, worldZ, this.cellSize);
      }

      private PadMapSnapshot finish() {
         return PadMapClientCache.SNAPSHOT_COMPOSER
            .compose(this.centerX, this.floorY, this.centerZ, this.cellSize, this.size, this.height, this.tiles, this.profile);
      }

      private int steps() {
         return this.progress.steps();
      }

      int centerX() {
         return this.centerX;
      }

      int centerZ() {
         return this.centerZ;
      }

      int floorY() {
         return this.floorY;
      }

      PadMapViewProfile profile() {
         return this.profile;
      }

      float zoom() {
         return this.zoom;
      }

      PadMapJobScheduler.JobView schedulerView() {
         return new PadMapJobScheduler.JobView(this.centerX, this.centerZ, this.floorY, this.profile, this.zoom);
      }

      private String describe() {
         return "job="
            + this.profile
            + " "
            + this.progress.doneCells()
            + "/"
            + this.progress.totalCells()
            + " cells "
            + this.progress.percent()
            + "% center=("
            + this.centerX
            + ","
            + this.centerZ
            + ") cell="
            + this.cellSize
            + " steps="
            + this.progress.steps();
      }
   }
}
