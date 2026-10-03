package com.zhongbai233.net_music_can_play_bili.client.audio;

final class AudioRelayRoutingPolicy {
   private AudioRelayRoutingPolicy() {
   }

   static boolean muteWorldRelays(boolean headphoneHandlesSource, boolean headphoneSuppressesSource, boolean privateOwnerRoute) {
      return headphoneHandlesSource || headphoneSuppressesSource || privateOwnerRoute;
   }
}
