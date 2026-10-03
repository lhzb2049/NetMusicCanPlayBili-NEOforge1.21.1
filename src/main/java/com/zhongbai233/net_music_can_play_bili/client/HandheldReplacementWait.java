package com.zhongbai233.net_music_can_play_bili.client;

import java.util.concurrent.atomic.AtomicBoolean;

final class HandheldReplacementWait {
   private final AtomicBoolean decided = new AtomicBoolean();

   HandheldReplacementWait.Outcome complete(Throwable error, HandheldDecoderAdmissionPolicy.Decision physicalDecision) {
      if (!this.decided.compareAndSet(false, true)) {
         return HandheldReplacementWait.Outcome.ALREADY_DECIDED;
      } else {
         return error == null && physicalDecision == HandheldDecoderAdmissionPolicy.Decision.OPEN
            ? HandheldReplacementWait.Outcome.OPEN
            : HandheldReplacementWait.Outcome.FAIL_CLOSED;
      }
   }

   static enum Outcome {
      OPEN,
      FAIL_CLOSED,
      ALREADY_DECIDED;
   }
}
