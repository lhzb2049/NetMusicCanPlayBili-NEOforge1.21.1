package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Locale;

final class IrisShaderpackProperties {
   static final String FORCE_YUV_SHADER = "ncpb.video.iris.force_yuv_shader";
   static final String DISABLE_CUSTOM_YUV_SHADER = "ncpb.video.iris.disable_yuv_shader";
   static final String LEGACY_DISABLE_CUSTOM_YUV_SHADER = "bili.video.iris.disable_yuv_shader";
   static final String ALLOW_THREE_PLANE_YUV = "ncpb.video.iris.allow_three_plane";
   static final String ENABLE_YUV_SHADERPACK_BYPASS = "ncpb.video.iris.yuv_bypass";
   static final String YUV_PROGRAM = "ncpb.video.iris.program";
   static final String YUV_SHADER_KEY = "ncpb.video.iris.shader_key";
   static final String DEFAULT_YUV_PROGRAM = "ENTITIES_TRANSLUCENT";
   static final String YUV_MODE = "ncpb.video.iris.yuv_mode";
   static final String YUV_MODE_AUTO = "auto";
   static final String YUV_MODE_ALWAYS = "always";
   static final String YUV_MODE_NEVER = "never";

   private IrisShaderpackProperties() {
   }

   /**
    * 自定义 YUV（NV12 / YUV420P）着色器的三态策略。
    *
    * <p>auto（默认）：检测到 shaderpack 生效时让位给 CPU NV12→RGBA 回退，避免自定义 sampler 集合与自定义 RenderType
    * 叠在光影管线上；always：始终保留自定义 YUV 着色器（光影下的实验通路，也用于复现旧行为）；never：始终禁用，一律走 CPU 回退。
    */
   static String yuvMode() {
      String raw = NcpbSystemProperties.stringValue(YUV_MODE, YUV_MODE_AUTO).toLowerCase(Locale.ROOT);
      if (YUV_MODE_ALWAYS.equals(raw) || YUV_MODE_NEVER.equals(raw)) {
         return raw;
      } else {
         return YUV_MODE_AUTO;
      }
   }

   /**
    * 显式指定的“禁用自定义 YUV 着色器”结论；{@code null} 表示未显式指定，按 {@link #yuvMode()} 的 auto 处理。
    *
    * <p>兼容旧开关：{@code disable_yuv_shader=true}（含 {@code bili.video.iris.*} 旧键）表示无条件禁用；
    * {@code force_yuv_shader=true} 表示强制保留 YUV 通路；{@code force_yuv_shader=false} 等价于 auto。
    */
   static Boolean explicitCustomYuvShaderDisabled() {
      String mode = yuvMode();
      if (YUV_MODE_ALWAYS.equals(mode)) {
         return Boolean.FALSE;
      } else if (YUV_MODE_NEVER.equals(mode)) {
         return Boolean.TRUE;
      } else if (Boolean.TRUE.equals(explicitBoolean(DISABLE_CUSTOM_YUV_SHADER, LEGACY_DISABLE_CUSTOM_YUV_SHADER))) {
         return Boolean.TRUE;
      } else {
         Boolean forced = explicitBoolean(FORCE_YUV_SHADER, null);
         return Boolean.TRUE.equals(forced) ? Boolean.FALSE : null;
      }
   }

   /** 把已算出的 shaderpack 状态代入策略，供日志使用（在 isShaderPackInUse() 内部回查会递归）。 */
   static boolean customYuvShaderDisabledWhen(boolean shaderPackInUse) {
      Boolean explicit = explicitCustomYuvShaderDisabled();
      return explicit != null ? explicit : shaderPackInUse;
   }

   private static Boolean explicitBoolean(String key, String legacyKey) {
      String raw = NcpbSystemProperties.stringValue(key, legacyKey, null);
      if ("true".equalsIgnoreCase(raw)) {
         return Boolean.TRUE;
      } else if ("false".equalsIgnoreCase(raw)) {
         return Boolean.FALSE;
      } else {
         return null;
      }
   }

   static boolean threePlaneYuvAllowed() {
      return NcpbSystemProperties.booleanValue("ncpb.video.iris.allow_three_plane", true);
   }

   static boolean yuvShaderpackBypassEnabled() {
      return NcpbSystemProperties.booleanValue("ncpb.video.iris.yuv_bypass", true);
   }

   static String yuvProgramName() {
      return NcpbSystemProperties.stringValue("ncpb.video.iris.program", "ENTITIES_TRANSLUCENT").toUpperCase(Locale.ROOT);
   }

   static String yuvShaderKeyName() {
      return NcpbSystemProperties.stringValue("ncpb.video.iris.shader_key", "").toUpperCase(Locale.ROOT);
   }
}
