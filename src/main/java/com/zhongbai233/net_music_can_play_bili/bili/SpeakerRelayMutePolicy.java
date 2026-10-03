package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.Collection;

final class SpeakerRelayMutePolicy {
   private SpeakerRelayMutePolicy() {
   }

   static boolean shouldMuteMain(boolean enabled, int registeredRelayCount, boolean privateHeadphoneRoute) {
      return enabled && registeredRelayCount > 0 && !privateHeadphoneRoute;
   }

   static boolean shouldMuteMain(boolean enabled, Collection<SpeakerAudioRelay> relays, boolean privateHeadphoneRoute) {
      return enabled && !privateHeadphoneRoute && relays != null ? relays.stream().anyMatch(relay -> relay.takesOverMainOutput()) : false;
   }

   static boolean shouldMuteMain(boolean enabled, Collection<SpeakerAudioRelay> relays, boolean privateHeadphoneRoute, boolean consoleRouteSuppressed) {
      return enabled && !privateHeadphoneRoute ? consoleRouteSuppressed || shouldMuteMain(enabled, relays, privateHeadphoneRoute) : false;
   }
}
