package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.util.NcpbSystemProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.1 移植版的运行期调优覆盖层。
 *
 * <p>为什么用 Mixin：这些默认值都位于「无法忠实重编译」的类里
 * （{@code ChunkPrefetchInputStream} 等：Vineflower 会把 {@code if (!x) return;} 守门语句
 * 重写成 {@code if (x) {...}}，分支极性不同，字节码不可能一致），因此不能用
 * 「重编译单个 class 替换进 jar」的方式安全修改。这里改为在属性读取入口处覆盖，
 * 属于纯新增代码，完全不动原有字节码。
 *
 * <p>三类覆盖：
 * <ol>
 *   <li>B站 CDN 取流预算（见下）—— 2026-10-01 实测：B站 给音频分配的 mcdn P2P 节点本身是坏的
 *       （TLS 握手中被掐断，4/4 复现，每次卡 5.2s），而 upos 常规镜像健康（首字节 172-532ms）。
 *       原 {@code startup_max_wait_ms=1500} 会在 1.5 秒内收不到字节时丢掉「慢但可用」的 upos 镜像
 *       并切换到那个死节点；{@code per_host_attempts=2} 又对死节点重试两次。</li>
 *   <li>Pad/MP4 诊断日志：默认关闭，打开后手持设备的动态 UI 会输出离屏渲染与视频层的
 *       成功/跳过原因，用于定位「UI 不显示」。</li>
 * </ol>
 *
 * <p>所有覆盖都尊重用户显式传入的 -D 参数；注入点均声明 {@code require = 0}，
 * 万一签名对不上只会不生效，不会导致启动崩溃。
 */
@Mixin(NcpbSystemProperties.class)
public class NcpbNetworkTuningMixin {
   private static final Logger LOGGER = LoggerFactory.getLogger("ncpb-tuning");

   /** 数值型覆盖（long / int 两条入口共用）：属性键 -> 新的默认值。 */
   private static final Map<String, Long> NUMERIC_TUNING = Map.of(
      "ncpb.media.prefetch.startup_max_wait_ms", 4000L,
      "ncpb.media.prefetch.per_host_attempts", 1L
   );

   /** 布尔型覆盖：属性键 -> 新的默认值。 */
   private static final Map<String, Boolean> BOOLEAN_TUNING = Map.of(
      "ncpb.pad.video.debug_log", Boolean.TRUE,
      "ncpb.pad.perf_log", Boolean.TRUE
   );

   @Inject(method = "longValue(Ljava/lang/String;Ljava/lang/String;J)J", at = @At("HEAD"), cancellable = true, require = 0)
   private static void ncpb$tuneLong(String key, String legacyKey, long fallback, CallbackInfoReturnable<Long> cir) {
      Long tuned = numeric(key, legacyKey, fallback);
      if (tuned != null) {
         cir.setReturnValue(tuned);
      }
   }

   @Inject(method = "intValue(Ljava/lang/String;Ljava/lang/String;I)I", at = @At("HEAD"), cancellable = true, require = 0)
   private static void ncpb$tuneInt(String key, String legacyKey, int fallback, CallbackInfoReturnable<Integer> cir) {
      Long tuned = numeric(key, legacyKey, fallback);
      if (tuned != null) {
         cir.setReturnValue(tuned.intValue());
      }
   }

   @Inject(method = "booleanValue(Ljava/lang/String;Ljava/lang/String;Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
   private static void ncpb$tuneBoolean(String key, String legacyKey, boolean fallback, CallbackInfoReturnable<Boolean> cir) {
      Boolean value = BOOLEAN_TUNING.get(key);
      if (value == null) {
         return;
      }

      if (explicitlySet(key) || explicitlySet(legacyKey)) {
         LOGGER.info("[NCPB 调优] {} 由 -D 显式指定，保留用户设置（跳过内置默认 {}）", key, value);
         return;
      }

      LOGGER.info("[NCPB 调优] {}: {} -> {}", key, fallback, value);
      cir.setReturnValue(value);
   }

   private static Long numeric(String key, String legacyKey, long fallback) {
      Long value = NUMERIC_TUNING.get(key);
      if (value == null) {
         return null;
      }

      if (explicitlySet(key) || explicitlySet(legacyKey)) {
         LOGGER.info("[NCPB 调优] {} 由 -D 显式指定，保留用户设置（跳过内置默认 {}）", key, value);
         return null;
      }

      LOGGER.info("[NCPB 调优] {}: {} -> {}", key, fallback, value);
      return value;
   }

   private static boolean explicitlySet(String key) {
      if (key == null || key.isBlank()) {
         return false;
      }

      String raw = System.getProperty(key);
      return raw != null && !raw.isBlank();
   }
}
