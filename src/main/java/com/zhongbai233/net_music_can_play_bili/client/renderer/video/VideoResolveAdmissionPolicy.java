package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

public final class VideoResolveAdmissionPolicy {
   private VideoResolveAdmissionPolicy() {
   }

   public static VideoResolveAdmissionPolicy.Decision decide(boolean latestRequest, boolean sameSession, boolean sourcePlaying, boolean hasConsumer) {
      if (!latestRequest) {
         return VideoResolveAdmissionPolicy.Decision.DROP_STALE_REQUEST;
      } else if (!sameSession) {
         return VideoResolveAdmissionPolicy.Decision.DROP_SESSION_CHANGED;
      } else if (!sourcePlaying) {
         return VideoResolveAdmissionPolicy.Decision.DROP_SOURCE_STOPPED;
      } else {
         return hasConsumer ? VideoResolveAdmissionPolicy.Decision.START : VideoResolveAdmissionPolicy.Decision.DROP_NO_CONSUMER;
      }
   }

   public static enum Decision {
      START,
      DROP_STALE_REQUEST,
      DROP_SESSION_CHANGED,
      DROP_SOURCE_STOPPED,
      DROP_NO_CONSUMER;
   }
}
