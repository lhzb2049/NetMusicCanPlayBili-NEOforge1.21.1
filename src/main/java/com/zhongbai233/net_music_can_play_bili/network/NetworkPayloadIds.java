package com.zhongbai233.net_music_can_play_bili.network;

import net.minecraft.resources.ResourceLocation;

final class NetworkPayloadIds {
   static final String NAMESPACE = "ncpb";

   private NetworkPayloadIds() {
   }

   static ResourceLocation id(String path) {
      return ResourceLocation.fromNamespaceAndPath("ncpb", path);
   }
}
