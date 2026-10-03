package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.CompletableFuture;

final class HandheldReplacementGate {
   private HandheldReplacementGate.Signals signals = new HandheldReplacementGate.Signals(
      "", CompletableFuture.completedFuture(null), CompletableFuture.completedFuture(null)
   );

   synchronized HandheldReplacementGate.Signals snapshot() {
      return this.signals;
   }

   synchronized HandheldReplacementGate.Signals install(String sessionId, CompletableFuture<Void> decodeExit, CompletableFuture<Void> nativeTermination) {
      if (decodeExit != null && nativeTermination != null) {
         this.signals = new HandheldReplacementGate.Signals(sessionId != null ? sessionId : "", decodeExit, nativeTermination);
         return this.signals;
      } else {
         throw new IllegalArgumentException("handheld replacement signals must not be null");
      }
   }

   synchronized boolean matches(HandheldReplacementGate.Signals expected) {
      return this.signals == expected;
   }

   synchronized boolean completedNormally() {
      return HandheldDecoderAdmissionPolicy.decide(this.signals.decodeExit(), this.signals.nativeTermination()) == HandheldDecoderAdmissionPolicy.Decision.OPEN;
   }

   record Signals(String sessionId, CompletableFuture<Void> decodeExit, CompletableFuture<Void> nativeTermination) {
   }
}
