package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ClientPlaybackSession {
   private final PlaybackSessionId sessionId;
   private final long expiresAtMillis;
   private final long suppressUntilMillis;
   private final List<Runnable> cancellationActions = new ArrayList<>();
   private final Map<String, Runnable> replaceableResources = new LinkedHashMap<>();
   private ClientPlaybackSession.State state = ClientPlaybackSession.State.PREPARING;
   private boolean cancelled;

   ClientPlaybackSession(String sessionId, long expiresAtMillis, long suppressUntilMillis) {
      this(PlaybackSessionId.of(sessionId), expiresAtMillis, suppressUntilMillis);
   }

   ClientPlaybackSession(PlaybackSessionId sessionId, long expiresAtMillis, long suppressUntilMillis) {
      this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
      this.expiresAtMillis = expiresAtMillis;
      this.suppressUntilMillis = suppressUntilMillis;
   }

   public String sessionId() {
      return this.sessionId.value();
   }

   public PlaybackSessionId playbackSessionId() {
      return this.sessionId;
   }

   public long expiresAtMillis() {
      return this.expiresAtMillis;
   }

   public long suppressUntilMillis() {
      return this.suppressUntilMillis;
   }

   public synchronized ClientPlaybackSession.State state() {
      return this.state;
   }

   public synchronized boolean isCancelled() {
      return this.cancelled;
   }

   public synchronized boolean isTerminal() {
      return this.state == ClientPlaybackSession.State.STOPPED || this.state == ClientPlaybackSession.State.FAILED;
   }

   public synchronized boolean transitionTo(ClientPlaybackSession.State next) {
      if (next != null && this.state != next && !this.isTerminal() && (!this.cancelled || next == ClientPlaybackSession.State.STOPPED)) {
         if (!canTransition(this.state, next)) {
            return false;
         } else {
            this.state = next;
            return true;
         }
      } else {
         return false;
      }
   }

   public void onCancel(Runnable action) {
      if (action != null) {
         boolean runNow;
         synchronized (this) {
            runNow = this.cancelled;
            if (!runNow) {
               this.cancellationActions.add(action);
            }
         }

         if (runNow) {
            runSafely(action);
         }
      }
   }

   public boolean replaceResource(String slot, Runnable cancellationAction) {
      if (slot != null && !slot.isBlank() && cancellationAction != null) {
         Runnable replaced = null;
         boolean bound;
         synchronized (this) {
            bound = !this.cancelled && !this.isTerminal();
            if (bound) {
               replaced = this.replaceableResources.put(slot, cancellationAction);
            }
         }

         if (replaced != null) {
            runSafely(replaced);
         }

         if (!bound) {
            runSafely(cancellationAction);
         }

         return bound;
      } else {
         return false;
      }
   }

   public boolean cancel() {
      List<Runnable> actions;
      synchronized (this) {
         if (this.cancelled) {
            return false;
         }

         this.cancelled = true;
         if (!this.isTerminal()) {
            this.state = ClientPlaybackSession.State.STOPPING;
         }

         actions = new ArrayList<>(this.cancellationActions.size() + this.replaceableResources.size());
         actions.addAll(this.cancellationActions);
         actions.addAll(this.replaceableResources.values());
         this.cancellationActions.clear();
         this.replaceableResources.clear();
      }

      actions.forEach(ClientPlaybackSession::runSafely);
      synchronized (this) {
         if (this.state == ClientPlaybackSession.State.STOPPING) {
            this.state = ClientPlaybackSession.State.STOPPED;
         }

         return true;
      }
   }

   public synchronized boolean fail() {
      if (!this.cancelled && !this.isTerminal()) {
         this.state = ClientPlaybackSession.State.FAILED;
         return true;
      } else {
         return false;
      }
   }

   private static boolean canTransition(ClientPlaybackSession.State current, ClientPlaybackSession.State next) {
      return switch (current) {
         case PREPARING -> next == ClientPlaybackSession.State.BUFFERING
            || next == ClientPlaybackSession.State.PLAYING
            || next == ClientPlaybackSession.State.FAILED;
         case BUFFERING -> next == ClientPlaybackSession.State.PLAYING
            || next == ClientPlaybackSession.State.RECOVERING
            || next == ClientPlaybackSession.State.FAILED;
         case PLAYING -> next == ClientPlaybackSession.State.RECOVERING || next == ClientPlaybackSession.State.FAILED;
         case RECOVERING -> next == ClientPlaybackSession.State.BUFFERING
            || next == ClientPlaybackSession.State.PLAYING
            || next == ClientPlaybackSession.State.FAILED;
         case STOPPING -> next == ClientPlaybackSession.State.STOPPED;
         case STOPPED, FAILED -> false;
      };
   }

   private static void runSafely(Runnable action) {
      try {
         action.run();
      } catch (RuntimeException var2) {
      }
   }

   public static enum State {
      PREPARING,
      BUFFERING,
      PLAYING,
      RECOVERING,
      STOPPING,
      STOPPED,
      FAILED;
   }
}
