package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

public final class PadMapClientHooks {
   private PadMapClientHooks() {
   }

   public static void setServerWorldScope(String worldScopeId, String worldName) {
      if (FMLEnvironment.dist == Dist.CLIENT) {
         PadMapClientHooks.ClientOnly.setServerWorldScope(worldScopeId, worldName);
      }
   }

   private static final class ClientOnly {
      private static void setServerWorldScope(String worldScopeId, String worldName) {
         PadMapClientCache.setServerWorldScope(worldScopeId, worldName);
      }
   }
}
