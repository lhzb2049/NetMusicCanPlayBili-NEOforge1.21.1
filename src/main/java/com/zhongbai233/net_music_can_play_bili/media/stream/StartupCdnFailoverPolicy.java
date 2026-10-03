package com.zhongbai233.net_music_can_play_bili.media.stream;

final class StartupCdnFailoverPolicy {
   private StartupCdnFailoverPolicy() {
   }

   static long firstRequestBytes(long normalChunkBytes, long startupTargetBytes, boolean firstRequestHasNoCachedData) {
      long normal = Math.max(1L, normalChunkBytes);
      return firstRequestHasNoCachedData && startupTargetBytes > 0L ? Math.max(1L, Math.min(normal, startupTargetBytes)) : normal;
   }

   static boolean shouldSwitch(boolean closed, long cachedBytes, int candidateCount, boolean requestInFlight, boolean switchAlreadyRequested) {
      return !closed && cachedBytes <= 0L && candidateCount > 1 && requestInFlight && !switchAlreadyRequested;
   }
}
