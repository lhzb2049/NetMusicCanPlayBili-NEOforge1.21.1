package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

public final class IrisShaderpackCompat {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static volatile boolean initialized;
   private static volatile boolean available;
   private static volatile boolean lastShaderPackInUse;

   private IrisShaderpackCompat() {
   }

   static boolean isForceYuvShaderEnabled() {
      return IrisShaderpackProperties.forceYuvShaderEnabled();
   }

   static String configuredYuvProgramName() {
      return IrisShaderpackProperties.yuvProgramName();
   }

   static String configuredYuvShaderKeyName() {
      return IrisShaderpackProperties.yuvShaderKeyName();
   }

   static boolean isTexturedProbeProgram() {
      return shouldApplyIrisYuvCompatibility() && "TEXTURED".equals(configuredYuvProgramName());
   }

   static boolean isThreePlaneIrisYuvAllowed() {
      return IrisShaderpackProperties.threePlaneYuvAllowed();
   }

   static boolean isYuvShaderpackBypassEnabled() {
      return IrisShaderpackProperties.yuvShaderpackBypassEnabled();
   }

   static boolean isSolidYuvRenderTypeExperimentEnabled() {
      return IrisVideoRenderTypePolicy.isSolidClassificationEnabled();
   }

   static boolean shouldForceSolidYuvRenderType() {
      return IrisVideoRenderTypePolicy.shouldForceSolidClassification(shouldApplyIrisYuvCompatibility());
   }

   static boolean shouldDrawYuvImmediate() {
      return IrisVideoRenderTypePolicy.shouldUseImmediateDraw(shouldApplyIrisYuvCompatibility());
   }

   static boolean shouldApplyIrisYuvCompatibility() {
      return isForceYuvShaderEnabled() && isShaderPackInUse();
   }

   static boolean shouldUseSingleSamplerProbe() {
      return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
   }

   static boolean shouldForceSafeProbeRenderType() {
      return shouldApplyIrisYuvCompatibility() && !isThreePlaneIrisYuvAllowed();
   }

   static boolean shouldDisableCustomYuvShader() {
      if (IrisShaderpackProperties.customYuvShaderDisabled()) {
         return true;
      } else {
         return isForceYuvShaderEnabled() ? false : isShaderPackInUse();
      }
   }

   public static boolean isShaderPackInUse() {
      ensureInitialized();
      if (!available) {
         return false;
      } else {
         try {
            boolean inUse;
            try {
               inUse = detectShaderPackInUse();
            } catch (ReflectiveOperationException var2) {
               LOGGER.debug("Iris API 查询 shaderpack 状态失败，按未启用 shaderpack 处理", var2);
               return false;
            }

            if (inUse != lastShaderPackInUse) {
               lastShaderPackInUse = inUse;
               LOGGER.info(
                  "Iris shaderpack 状态变化: shaderpackInUse={}, customYuvShaderDisabled={}",
                  inUse,
                  IrisShaderpackProperties.customYuvShaderDisabled() || !isForceYuvShaderEnabled() && inUse
               );
            }

            return inUse;
         } catch (RuntimeException var3) {
            LOGGER.debug("Iris API 查询 shaderpack 状态失败，按未启用 shaderpack 处理", var3);
            return false;
         }
      }
   }

   private static boolean detectShaderPackInUse() throws ReflectiveOperationException {
      Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      Object api = apiClass.getMethod("getInstance").invoke(null);

      try {
         Object active = apiClass.getMethod("isShaderPackActive").invoke(api);
         return Boolean.TRUE.equals(active);
      } catch (NoSuchMethodException var4) {
         Object inUse = apiClass.getMethod("isShaderPackInUse").invoke(api);
         return Boolean.TRUE.equals(inUse);
      }
   }

   private static void ensureInitialized() {
      if (!initialized) {
         synchronized (IrisShaderpackCompat.class) {
            if (!initialized) {
               initialized = true;

               try {
                  if (!ModList.get().isLoaded("iris")) {
                     available = false;
                     return;
                  }

                  try {
                     detectShaderPackInUse();
                  } catch (ReflectiveOperationException var3) {
                     available = false;
                     return;
                  }

                  available = true;
                  LOGGER.debug("检测到 Iris API，启用 shaderpack 兼容检测");
               } catch (LinkageError var4) {
                  available = false;
               } catch (RuntimeException var5) {
                  available = false;
                  LOGGER.debug("Iris API 初始化失败，按未安装 Iris 处理", var5);
               }
            }
         }
      }
   }
}
