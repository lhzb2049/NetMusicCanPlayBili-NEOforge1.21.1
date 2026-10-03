package com.zhongbai233.net_music_can_play_bili.client.audio;

final class ModernTurntableCommandAdmissionPolicy {
   private ModernTurntableCommandAdmissionPolicy() {
   }

   static ModernTurntableCommandAdmissionPolicy.Decision decide(String incomingSessionId, String authoritativeSessionId, String trackedSessionId) {
      return decide(incomingSessionId, authoritativeSessionId, trackedSessionId, false, false);
   }

   static ModernTurntableCommandAdmissionPolicy.Decision decide(
      String incomingSessionId, String authoritativeSessionId, String trackedSessionId, boolean authoritativeSourcePresent
   ) {
      return decide(incomingSessionId, authoritativeSessionId, trackedSessionId, authoritativeSourcePresent, false);
   }

   static ModernTurntableCommandAdmissionPolicy.Decision decide(
      String incomingSessionId, String authoritativeSessionId, String trackedSessionId, boolean authoritativeSourcePresent, boolean explicitlyStopped
   ) {
      String incoming = normalize(incomingSessionId);
      String authoritative = normalize(authoritativeSessionId);
      String tracked = normalize(trackedSessionId);
      if (explicitlyStopped) {
         return ModernTurntableCommandAdmissionPolicy.Decision.DROP_EXPLICITLY_STOPPED;
      } else if (authoritativeSourcePresent && authoritative.isBlank()) {
         return ModernTurntableCommandAdmissionPolicy.Decision.DROP_AUTHORITATIVE_STOPPED;
      } else if (!authoritative.isBlank()) {
         return authoritative.equals(incoming)
            ? ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_AUTHORITATIVE
            : ModernTurntableCommandAdmissionPolicy.Decision.DROP_AUTHORITATIVE_SESSION_MISMATCH;
      } else if (tracked.isBlank()) {
         return ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_COMPATIBILITY_FALLBACK;
      } else {
         return tracked.equals(incoming)
            ? ModernTurntableCommandAdmissionPolicy.Decision.ACCEPT_TRACKED
            : ModernTurntableCommandAdmissionPolicy.Decision.DROP_TRACKED_SESSION_MISMATCH;
      }
   }

   private static String normalize(String value) {
      return value != null ? value : "";
   }

   static enum Decision {
      ACCEPT_AUTHORITATIVE(true),
      ACCEPT_TRACKED(true),
      ACCEPT_COMPATIBILITY_FALLBACK(true),
      DROP_AUTHORITATIVE_STOPPED(false),
      DROP_EXPLICITLY_STOPPED(false),
      DROP_AUTHORITATIVE_SESSION_MISMATCH(false),
      DROP_TRACKED_SESSION_MISMATCH(false);

      private final boolean accepted;

      private Decision(boolean accepted) {
         this.accepted = accepted;
      }

      boolean accepted() {
         return this.accepted;
      }
   }
}
