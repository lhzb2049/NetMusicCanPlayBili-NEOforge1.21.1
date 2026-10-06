package com.zhongbai233.net_music_can_play_bili.client.media;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Locale;

/**
 * 阶段 2（本地视频）的开关与上限，全部为系统属性，与模组其余开关一致。
 *
 * <p>刻意不依赖任何 Minecraft 类，便于离线跑矩阵验证。
 */
final class LocalVideoProperties {
   /** 本地视频通路总开关（关掉后本地视频与阶段 0 一样被拒绝）。 */
   static final String ENABLED = "ncpb.local.video.enabled";
   /** fMP4 分片目标时长（毫秒）。视频只在关键帧处切，所以实际分片会更长。 */
   static final String FRAGMENT_MILLIS = "ncpb.local.video.fragment_ms";
   /** 单个分片的字节上限（超过就提前切分；视频仍只在关键帧处切）。 */
   static final String MAX_FRAGMENT_BYTES = "ncpb.local.video.max_fragment_bytes";
   /** 允许重封装的源文件大小上限（字节）。 */
   static final String MAX_SOURCE_BYTES = "ncpb.local.video.max_source_bytes";
   /** 是否重封装音频轨（auto|off）：off 时只出视频轨，用于排查音频问题。 */
   static final String AUDIO = "ncpb.local.video.audio";
   /** 重封装产物缓存上限（字节）与回收策略。 */
   static final String CACHE_MAX_BYTES = "ncpb.local.video.cache_max_bytes";
   /** 回环地址 token 的有效期（秒）。 */
   static final String TOKEN_TTL_SECONDS = "ncpb.local.video.token_ttl_seconds";

   private static final long DEFAULT_MAX_SOURCE_BYTES = 1024L * 1024L * 1024L;
   private static final long DEFAULT_MAX_FRAGMENT_BYTES = 24L * 1024L * 1024L;
   private static final long DEFAULT_CACHE_MAX_BYTES = 1536L * 1024L * 1024L;
   private static final int DEFAULT_TOKEN_TTL_SECONDS = 900;
   private static final int DEFAULT_FRAGMENT_MILLIS = 1000;

   private LocalVideoProperties() {
   }

   static boolean enabled() {
      return NcpbSystemProperties.booleanValue(ENABLED, true);
   }

   static int fragmentMillis() {
      int value = NcpbSystemProperties.intValue(FRAGMENT_MILLIS, DEFAULT_FRAGMENT_MILLIS);
      return value > 0 ? value : DEFAULT_FRAGMENT_MILLIS;
   }

   static long maxFragmentBytes() {
      long value = NcpbSystemProperties.longValue(MAX_FRAGMENT_BYTES, DEFAULT_MAX_FRAGMENT_BYTES);
      return value > 0L ? value : DEFAULT_MAX_FRAGMENT_BYTES;
   }

   static long maxSourceBytes() {
      long value = NcpbSystemProperties.longValue(MAX_SOURCE_BYTES, DEFAULT_MAX_SOURCE_BYTES);
      return value > 0L ? value : DEFAULT_MAX_SOURCE_BYTES;
   }

   static boolean includeAudio() {
      String raw = NcpbSystemProperties.stringValue(AUDIO, "auto").trim().toLowerCase(Locale.ROOT);
      return !"off".equals(raw) && !"false".equals(raw) && !"never".equals(raw);
   }

   static long cacheMaxBytes() {
      long value = NcpbSystemProperties.longValue(CACHE_MAX_BYTES, DEFAULT_CACHE_MAX_BYTES);
      return value > 0L ? value : DEFAULT_CACHE_MAX_BYTES;
   }

   static long tokenTtlMillis() {
      int seconds = NcpbSystemProperties.intValue(TOKEN_TTL_SECONDS, DEFAULT_TOKEN_TTL_SECONDS);
      return Math.max(30, seconds) * 1000L;
   }
}
