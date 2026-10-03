package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;

final class VideoClientProperties {
   static final String TURNTABLE_ENABLED = "ncpb.video.turntable.enabled";
   static final String TURNTABLE_RESOLVE_THREADS = "ncpb.video.turntable.resolve_threads";
   static final String LEGACY_TURNTABLE_RESOLVE_THREADS = "bili.video.turntable.resolve_threads";
   static final String LIVE_FPS = "ncpb.video.live.fps";
   static final String LIVE_QUALITY_CEILING = "ncpb.video.live.quality_ceiling";
   static final String LEGACY_LIVE_QUALITY_CEILING = "bili.video.turntable.quality";
   static final String HANDHELD_MAX_THREADS = "ncpb.mp4.video.max_threads";
   static final String HANDHELD_NATIVE_HWACCEL = "ncpb.mp4.video.native.hwaccel";
   static final String PAD_NATIVE_HWACCEL = "ncpb.pad.video.native.hwaccel";
   static final String MP4_OFFSCREEN_SCALE = "ncpb.mp4.offscreen_scale";

   private VideoClientProperties() {
   }

   static VideoClientProperties.Turntable turntable() {
      return new VideoClientProperties.Turntable(
         NcpbSystemProperties.booleanValue("ncpb.video.turntable.enabled", true),
         NcpbSystemProperties.intValue("ncpb.video.turntable.resolve_threads", "bili.video.turntable.resolve_threads", 2)
      );
   }

   static VideoClientProperties.Live live() {
      return new VideoClientProperties.Live(
         NcpbSystemProperties.intValue("ncpb.video.live.fps", 30),
         NcpbSystemProperties.intValue("ncpb.video.live.quality_ceiling", "bili.video.turntable.quality", 116)
      );
   }

   static VideoClientProperties.Handheld handheld() {
      return new VideoClientProperties.Handheld(
         NcpbSystemProperties.intValue("ncpb.mp4.video.max_threads", 4),
         NcpbSystemProperties.stringValue("ncpb.mp4.video.native.hwaccel", "auto"),
         NcpbSystemProperties.stringValue("ncpb.pad.video.native.hwaccel", "none"),
         NcpbSystemProperties.intValue("ncpb.mp4.offscreen_scale", 2)
      );
   }

   record Handheld(int maxThreads, String nativeHwaccel, String padNativeHwaccel, int mp4OffscreenScale) {
      Handheld(int maxThreads, String nativeHwaccel, String padNativeHwaccel, int mp4OffscreenScale) {
         maxThreads = Math.max(2, maxThreads);
         mp4OffscreenScale = Math.max(1, mp4OffscreenScale);
         this.maxThreads = maxThreads;
         this.nativeHwaccel = nativeHwaccel;
         this.padNativeHwaccel = padNativeHwaccel;
         this.mp4OffscreenScale = mp4OffscreenScale;
      }
   }

   record Live(int fps, int qualityCeiling) {
      Live(int fps, int qualityCeiling) {
         fps = Math.max(15, fps);
         qualityCeiling = Math.max(1, qualityCeiling);
         this.fps = fps;
         this.qualityCeiling = qualityCeiling;
      }
   }

   record Turntable(boolean enabled, int resolveThreads) {
      Turntable(boolean enabled, int resolveThreads) {
         resolveThreads = Math.max(1, resolveThreads);
         this.enabled = enabled;
         this.resolveThreads = resolveThreads;
      }
   }
}
