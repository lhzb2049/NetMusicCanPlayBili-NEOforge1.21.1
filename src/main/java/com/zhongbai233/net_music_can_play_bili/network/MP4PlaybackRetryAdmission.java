package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

final class MP4PlaybackRetryAdmission<S> {
   private final MP4PlaybackSourceSessionRegistry<S> sessions;
   private final MP4ResolveIntentRegistry resolveIntents;
   private final Function<S, PlaybackSessionId> sessionId;
   private final ToIntFunction<S> queueIndex;
   private final Function<S, String> sourceUrl;

   MP4PlaybackRetryAdmission(
      MP4PlaybackSourceSessionRegistry<S> sessions,
      MP4ResolveIntentRegistry resolveIntents,
      Function<S, PlaybackSessionId> sessionId,
      ToIntFunction<S> queueIndex,
      Function<S, String> sourceUrl
   ) {
      this.sessions = Objects.requireNonNull(sessions, "sessions");
      this.resolveIntents = Objects.requireNonNull(resolveIntents, "resolveIntents");
      this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
      this.queueIndex = Objects.requireNonNull(queueIndex, "queueIndex");
      this.sourceUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
   }

   MP4PlaybackRetryAdmission.Attempt begin(UUID deviceId, PlaybackSessionId expectedSessionId) {
      S current = this.sessions.get(deviceId);
      if (current != null && expectedSessionId != null && expectedSessionId.equals(this.sessionId.apply(current))) {
         int expectedQueueIndex = this.queueIndex.applyAsInt(current);
         String expectedSourceUrl = this.safeSourceUrl(current);
         MP4ResolveIntentRegistry.Intent resolveIntent = this.resolveIntents.beginIfIdle(deviceId, expectedQueueIndex, expectedSourceUrl);
         if (resolveIntent == null) {
            return null;
         } else {
            MP4PlaybackRetryAdmission.Attempt attempt = new MP4PlaybackRetryAdmission.Attempt(
               expectedSessionId, expectedQueueIndex, expectedSourceUrl, resolveIntent
            );
            if (!this.matchesCurrent(deviceId, attempt)) {
               this.resolveIntents.complete(deviceId, resolveIntent);
               return null;
            } else {
               return attempt;
            }
         }
      } else {
         return null;
      }
   }

   boolean isCurrent(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
      return attempt != null && this.resolveIntents.isCurrent(deviceId, attempt.resolveIntent()) && this.matchesCurrent(deviceId, attempt);
   }

   S replaceIfCurrent(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt, UnaryOperator<S> replacementFactory) {
      if (attempt != null && replacementFactory != null && this.isCurrent(deviceId, attempt)) {
         S current = this.sessions.get(deviceId);
         if (!this.matches(current, attempt)) {
            this.complete(deviceId, attempt);
            return null;
         } else {
            S replacement = Objects.requireNonNull(replacementFactory.apply(current), "replacement");
            if (this.resolveIntents.isCurrent(deviceId, attempt.resolveIntent()) && this.sessions.replace(deviceId, current, replacement)) {
               this.resolveIntents.complete(deviceId, attempt.resolveIntent());
               return replacement;
            } else {
               this.complete(deviceId, attempt);
               return null;
            }
         }
      } else {
         this.complete(deviceId, attempt);
         return null;
      }
   }

   void complete(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
      if (attempt != null) {
         this.resolveIntents.complete(deviceId, attempt.resolveIntent());
      }
   }

   private boolean matchesCurrent(UUID deviceId, MP4PlaybackRetryAdmission.Attempt attempt) {
      return this.matches(this.sessions.get(deviceId), attempt);
   }

   private boolean matches(S current, MP4PlaybackRetryAdmission.Attempt attempt) {
      return current != null
         && attempt.expectedSessionId().equals(this.sessionId.apply(current))
         && attempt.queueIndex() == this.queueIndex.applyAsInt(current)
         && attempt.sourceUrl().equals(this.safeSourceUrl(current));
   }

   private String safeSourceUrl(S session) {
      String value = this.sourceUrl.apply(session);
      return value != null ? value : "";
   }

   record Attempt(PlaybackSessionId expectedSessionId, int queueIndex, String sourceUrl, MP4ResolveIntentRegistry.Intent resolveIntent) {
      Attempt(PlaybackSessionId expectedSessionId, int queueIndex, String sourceUrl, MP4ResolveIntentRegistry.Intent resolveIntent) {
         Objects.requireNonNull(expectedSessionId, "expectedSessionId");
         sourceUrl = sourceUrl != null ? sourceUrl : "";
         Objects.requireNonNull(resolveIntent, "resolveIntent");
         this.expectedSessionId = expectedSessionId;
         this.queueIndex = queueIndex;
         this.sourceUrl = sourceUrl;
         this.resolveIntent = resolveIntent;
      }
   }
}
