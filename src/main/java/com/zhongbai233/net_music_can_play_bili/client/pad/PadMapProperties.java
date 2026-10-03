package com.zhongbai233.net_music_can_play_bili.client.pad;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class PadMapProperties {
   static final String VIEW_WIDTH = "ncpb.pad.map_view_width";
   static final String LEGACY_SIZE = "ncpb.pad.map_size";
   static final String VIEW_HEIGHT = "ncpb.pad.map_view_height";
   static final String OVERSCAN = "ncpb.pad.map_overscan";
   static final String WIDTH = "ncpb.pad.map_width";
   static final String HEIGHT = "ncpb.pad.map_height";
   static final String CELL_SAMPLES = "ncpb.pad.map_cell_samples";
   static final String RESAMPLE_CHUNKS = "ncpb.pad.map_resample_chunks";
   static final String CHUNKS_PER_TICK = "ncpb.pad.map_chunks_per_tick";
   static final String FAST_CHUNKS_PER_TICK = "ncpb.pad.map_fast_chunks_per_tick";
   static final String CELLS_PER_CHUNK_BUDGET = "ncpb.pad.map_cells_per_chunk_budget";
   static final String INITIAL_VISIBLE_BURST_CELLS = "ncpb.pad.map_initial_burst_cells";
   static final String MAX_JOB_LAG_CHUNKS = "ncpb.pad.map_max_job_lag_chunks";
   static final String DIRTY_CHUNKS_PER_TICK = "ncpb.pad.map_dirty_chunks_per_tick";
   static final String UPDATE_INTERVAL_TICKS = "ncpb.pad.map_update_interval_ticks";
   static final String UNKNOWN_RETRY_TICKS = "ncpb.pad.map_unknown_retry_ticks";
   static final String RECENTER_BLOCKS = "ncpb.pad.map_recenter_blocks";
   static final String INDOOR_RECENTER_BLOCKS = "ncpb.pad.map_indoor_recenter_blocks";
   static final String INDOOR_CEILING_SCAN_BLOCKS = "ncpb.pad.map_indoor_ceiling_scan_blocks";
   static final String INDOOR_CEILING_MIN_HITS = "ncpb.pad.map_indoor_ceiling_min_hits";
   static final String INDOOR_ARTIFICIAL_MIN_HITS = "ncpb.pad.map_indoor_artificial_min_hits";
   static final String INDOOR_ENTER_CONFIRM_TICKS = "ncpb.pad.map_indoor_enter_confirm_ticks";
   static final String INDOOR_EXIT_CONFIRM_TICKS = "ncpb.pad.map_indoor_exit_confirm_ticks";
   static final String INDOOR_FLOOR_CONFIRM_TICKS = "ncpb.pad.map_indoor_floor_confirm_ticks";
   static final String INDOOR_JUMP_TOLERANCE_BLOCKS = "ncpb.pad.map_indoor_jump_tolerance_blocks";
   static final String OUTDOOR_ZOOM = "ncpb.pad.map_outdoor_zoom";
   static final String INDOOR_ZOOM = "ncpb.pad.map_indoor_zoom";
   static final String INDOOR_DISPLAY_SCALE = "ncpb.pad.map_indoor_display_scale";
   static final String PREVIEW_CHUNKS = "ncpb.pad.map_preview_chunks";
   static final String CELL_CACHE_LIMIT = "ncpb.pad.map_cell_cache_limit";
   static final String DIRTY_CHUNK_LIMIT = "ncpb.pad.map_dirty_chunk_limit";
   static final String DISK_FLUSH_TICKS = "ncpb.pad.map_disk_flush_ticks";
   static final String DISK_CACHE = "ncpb.pad.map_disk_cache";

   private PadMapProperties() {
   }

   static PadMapProperties.Layout layout() {
      int viewWidth = NcpbSystemProperties.intValue("ncpb.pad.map_view_width", "ncpb.pad.map_size", 384);
      int viewHeight = NcpbSystemProperties.intValue("ncpb.pad.map_view_height", 192);
      int overscan = NcpbSystemProperties.intValue("ncpb.pad.map_overscan", 96);
      int width = NcpbSystemProperties.intValue("ncpb.pad.map_width", viewWidth + overscan * 2);
      int height = NcpbSystemProperties.intValue("ncpb.pad.map_height", viewHeight + overscan * 2);
      int cellSamples = NcpbSystemProperties.intValue("ncpb.pad.map_cell_samples", 5);
      return new PadMapProperties.Layout(viewWidth, viewHeight, overscan, width, height, cellSamples);
   }

   static PadMapProperties.Cache cache() {
      return new PadMapProperties.Cache(
         NcpbSystemProperties.intValue("ncpb.pad.map_resample_chunks", 2),
         NcpbSystemProperties.intValue("ncpb.pad.map_chunks_per_tick", 24),
         NcpbSystemProperties.intValue("ncpb.pad.map_fast_chunks_per_tick", 64),
         NcpbSystemProperties.intValue("ncpb.pad.map_cells_per_chunk_budget", 32),
         NcpbSystemProperties.intValue("ncpb.pad.map_initial_burst_cells", 8192),
         NcpbSystemProperties.intValue("ncpb.pad.map_max_job_lag_chunks", 3),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.pad.map_dirty_chunks_per_tick", 4)),
         Math.max(1, NcpbSystemProperties.intValue("ncpb.pad.map_update_interval_ticks", 1)),
         Math.max(20, NcpbSystemProperties.intValue("ncpb.pad.map_unknown_retry_ticks", 40)),
         NcpbSystemProperties.intValue("ncpb.pad.map_recenter_blocks", 16),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_recenter_blocks", 8),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_ceiling_scan_blocks", 96),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_ceiling_min_hits", 5),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_artificial_min_hits", 5),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_enter_confirm_ticks", 2),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_exit_confirm_ticks", 40),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_floor_confirm_ticks", 4),
         NcpbSystemProperties.intValue("ncpb.pad.map_indoor_jump_tolerance_blocks", 2),
         NcpbSystemProperties.floatValue("ncpb.pad.map_outdoor_zoom", 1.25F),
         NcpbSystemProperties.floatValue("ncpb.pad.map_indoor_zoom", 3.0F),
         NcpbSystemProperties.floatValue("ncpb.pad.map_indoor_display_scale", 2.0F),
         NcpbSystemProperties.intValue("ncpb.pad.map_preview_chunks", 1),
         NcpbSystemProperties.intValue("ncpb.pad.map_cell_cache_limit", 524288),
         NcpbSystemProperties.intValue("ncpb.pad.map_dirty_chunk_limit", 8192),
         NcpbSystemProperties.intValue("ncpb.pad.map_disk_flush_ticks", 200),
         NcpbSystemProperties.booleanValue("ncpb.pad.map_disk_cache", true)
      );
   }

   record Cache(
      int resampleChunkDistance,
      int chunksPerTick,
      int fastChunksPerTick,
      int cellsPerChunkBudget,
      int initialVisibleBurstCells,
      int maxJobLagChunks,
      int dirtyChunksPerTick,
      int updateIntervalTicks,
      int unknownRetryTicks,
      int recenterBlocks,
      int indoorRecenterBlocks,
      int indoorCeilingScanBlocks,
      int indoorCeilingMinHits,
      int indoorArtificialMinHits,
      int indoorEnterConfirmTicks,
      int indoorExitConfirmTicks,
      int indoorFloorConfirmTicks,
      int indoorJumpToleranceBlocks,
      float outdoorZoom,
      float indoorZoom,
      float indoorDisplayScale,
      int previewChunks,
      int cellCacheLimit,
      int dirtyChunkLimit,
      int diskFlushTicks,
      boolean diskCacheEnabled
   ) {
   }

   record Layout(int viewWidth, int viewHeight, int overscan, int width, int height, int cellSamples) {
   }
}
