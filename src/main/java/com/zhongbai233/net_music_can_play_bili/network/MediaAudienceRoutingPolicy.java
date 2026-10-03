package com.zhongbai233.net_music_can_play_bili.network;

final class MediaAudienceRoutingPolicy {
   private static final String PAD_SESSION_MARKER = "-pad-";

   private MediaAudienceRoutingPolicy() {
   }

   static boolean shouldSendPublicToOwner(boolean ownerReceivedHeadphoneRoute) {
      return !ownerReceivedHeadphoneRoute;
   }

   static boolean shouldBroadcastPublic(boolean anyHeadphoneRouteDelivered) {
      return !anyHeadphoneRouteDelivered;
   }

   static boolean shouldBroadcastPlayerSource(String sessionId, boolean anyHeadphoneRouteDelivered) {
      return !isPadSession(sessionId) && shouldBroadcastPublic(anyHeadphoneRouteDelivered);
   }

   static boolean isPadSession(String sessionId) {
      return sessionId != null && sessionId.contains("-pad-");
   }

   static MediaAudienceRoutingPolicy.PublicRoute publicRoute(
      boolean playerSource, String sessionId, boolean anyHeadphoneRouteDelivered, boolean ownerOnline, boolean ownerReceivedHeadphoneRoute
   ) {
      if (!playerSource) {
         return shouldBroadcastPublic(anyHeadphoneRouteDelivered) ? MediaAudienceRoutingPolicy.PublicRoute.NEARBY : MediaAudienceRoutingPolicy.PublicRoute.NONE;
      } else if (shouldBroadcastPlayerSource(sessionId, anyHeadphoneRouteDelivered)) {
         return MediaAudienceRoutingPolicy.PublicRoute.NEARBY;
      } else {
         return isPadSession(sessionId) && ownerOnline && shouldSendPublicToOwner(ownerReceivedHeadphoneRoute)
            ? MediaAudienceRoutingPolicy.PublicRoute.OWNER
            : MediaAudienceRoutingPolicy.PublicRoute.NONE;
      }
   }

   static boolean shouldBroadcastStopNearby(boolean playerSource, String sessionId) {
      return !playerSource || !isPadSession(sessionId);
   }

   static enum PublicRoute {
      NEARBY,
      OWNER,
      NONE;
   }
}
