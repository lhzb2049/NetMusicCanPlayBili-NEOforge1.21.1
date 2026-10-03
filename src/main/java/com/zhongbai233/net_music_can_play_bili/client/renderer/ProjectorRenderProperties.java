package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class ProjectorRenderProperties {
   static final String VIDEO_RENDER_MARGIN = "ncpb.video.projector.render_margin";
   static final String VIDEO_RENDER_MAX_ASPECT = "ncpb.video.projector.render_max_aspect";
   static final String LYRIC_SCROLL_MIN_DURATION_MILLIS = "ncpb.lyric.scroll.min_duration_ms";
   static final String LYRIC_SCROLL_FAST_GAP_MILLIS = "ncpb.lyric.scroll.fast_gap_ms";
   static final String LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS = "ncpb.lyric.scroll.interpolation_half_life_ms";
   static final String LEGACY_LYRIC_SCROLL_INTERPOLATION_HALF_LIFE_MILLIS = "bili.lyric.scroll.interpolation_half_life_ms";
   static final String LYRIC_AUDIO_DELAY_MILLIS = "ncpb.lyric.audio_delay_ms";
   static final String LYRIC_RENDER_MARGIN = "ncpb.lyric.projector.render_margin";
   static final String LYRIC_RENDER_MIN_INFLATE = "ncpb.lyric.projector.render_min_inflate";
   static final String LYRIC_RENDER_MAX_TEXT_WIDTH = "ncpb.lyric.projector.render_max_text_width";

   private ProjectorRenderProperties() {
   }

   static ProjectorRenderProperties.VideoBounds videoBounds() {
      return new ProjectorRenderProperties.VideoBounds(
         NcpbSystemProperties.doubleValue("ncpb.video.projector.render_margin", 0.5),
         NcpbSystemProperties.doubleValue("ncpb.video.projector.render_max_aspect", 8.0)
      );
   }

   static ProjectorRenderProperties.LyricScroll lyricScroll() {
      return new ProjectorRenderProperties.LyricScroll(
         NcpbSystemProperties.longValue("ncpb.lyric.scroll.min_duration_ms", 120L),
         NcpbSystemProperties.longValue("ncpb.lyric.scroll.fast_gap_ms", 850L),
         NcpbSystemProperties.longValue("ncpb.lyric.scroll.interpolation_half_life_ms", "bili.lyric.scroll.interpolation_half_life_ms", 35L),
         NcpbSystemProperties.longValue("ncpb.lyric.audio_delay_ms", 0L)
      );
   }

   static ProjectorRenderProperties.LyricBounds lyricBounds() {
      return new ProjectorRenderProperties.LyricBounds(
         NcpbSystemProperties.doubleValue("ncpb.lyric.projector.render_margin", 2.0),
         NcpbSystemProperties.doubleValue("ncpb.lyric.projector.render_min_inflate", 2.5),
         NcpbSystemProperties.doubleValue("ncpb.lyric.projector.render_max_text_width", 8.0)
      );
   }

   record LyricBounds(double margin, double minInflate, double maxTextWidth) {
      LyricBounds(double margin, double minInflate, double maxTextWidth) {
         margin = Math.max(0.0, margin);
         minInflate = Math.max(0.0, minInflate);
         maxTextWidth = Math.max(0.0, maxTextWidth);
         this.margin = margin;
         this.minInflate = minInflate;
         this.maxTextWidth = maxTextWidth;
      }
   }

   record LyricScroll(long minDurationMillis, long fastGapMillis, long interpolationHalfLifeMillis, long audioDelayMillis) {
      LyricScroll(long minDurationMillis, long fastGapMillis, long interpolationHalfLifeMillis, long audioDelayMillis) {
         minDurationMillis = Math.max(0L, minDurationMillis);
         fastGapMillis = Math.max(0L, fastGapMillis);
         interpolationHalfLifeMillis = Math.max(1L, interpolationHalfLifeMillis);
         audioDelayMillis = Math.max(0L, audioDelayMillis);
         this.minDurationMillis = minDurationMillis;
         this.fastGapMillis = fastGapMillis;
         this.interpolationHalfLifeMillis = interpolationHalfLifeMillis;
         this.audioDelayMillis = audioDelayMillis;
      }
   }

   record VideoBounds(double margin, double maxAspect) {
      VideoBounds(double margin, double maxAspect) {
         margin = Math.max(0.0, margin);
         maxAspect = Math.max(0.0, maxAspect);
         this.margin = margin;
         this.maxAspect = maxAspect;
      }
   }
}
