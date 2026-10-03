package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ModernTurntablePlaybackTracker {
   private static final long STOP_GRACE_MILLIS = 5000L;
   private static final long DUPLICATE_SUPPRESS_MILLIS = 1500L;
   private static final long STOP_TOMBSTONE_MILLIS = 30000L;
   private static final ConcurrentHashMap<Object, ClientPlaybackSession> ACTIVE = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<SyncedMediaSound, Boolean> ACTIVE_SOUNDS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<SyncedMediaSound, BlockPos> ACTIVE_SOUND_POSITIONS = new ConcurrentHashMap<>();
   private static final ConcurrentHashMap<ModernTurntablePlaybackTracker.StoppedKey, Long> EXPLICITLY_STOPPED = new ConcurrentHashMap<>();

   private ModernTurntablePlaybackTracker() {
   }

   public static boolean tryStart(BlockPos pos, String sessionId, int remainingSeconds) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         long now = System.currentTimeMillis();
         cleanup(now);
         if (isExplicitlyStopped(pos, parsedSessionId, now)) {
            return false;
         } else {
            long expiresAt = now + Math.max(1, remainingSeconds) * 1000L + 5000L;
            Object key = keyFor(pos, parsedSessionId);
            ClientPlaybackSession previous = ACTIVE.get(key);
            if (previous != null && previous.playbackSessionId().equals(parsedSessionId)) {
               if (previous.expiresAtMillis() > now) {
                  return false;
               }

               if (previous.suppressUntilMillis() > now) {
                  return false;
               }
            }

            ClientPlaybackSession next = new ClientPlaybackSession(parsedSessionId, expiresAt, now + 1500L);
            AtomicReference<ClientPlaybackSession> replaced = new AtomicReference<>();
            ACTIVE.compute(
               key,
               (ignored, current) -> {
                  if (current == null
                     || !current.playbackSessionId().equals(parsedSessionId)
                     || current.expiresAtMillis() <= now && current.suppressUntilMillis() <= now) {
                     replaced.set(current);
                     return next;
                  } else {
                     replaced.set(next);
                     return (ClientPlaybackSession)current;
                  }
               }
            );
            ClientPlaybackSession old = replaced.get();
            if (old == next) {
               return false;
            } else {
               if (old != null) {
                  old.cancel();
               }

               return true;
            }
         }
      } else {
         return true;
      }
   }

   public static void markStreamStarted(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
         if (active != null && active.playbackSessionId().equals(parsedSessionId)) {
            active.transitionTo(ClientPlaybackSession.State.PLAYING);
         }
      }
   }

   public static void registerSound(SyncedMediaSound sound, BlockPos pos, String sessionId) {
      if (sound != null) {
         ACTIVE_SOUNDS.put(sound, Boolean.TRUE);
         if (pos != null) {
            ACTIVE_SOUND_POSITIONS.put(sound, AudioUtils.copyPos(pos));
         }

         PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
         ClientPlaybackSession active = pos != null && parsedSessionId != null ? ACTIVE.get(keyFor(pos, parsedSessionId)) : null;
         if (active == null || !active.playbackSessionId().equals(parsedSessionId)) {
            stopSound(sound);
            return;
         }

         active.transitionTo(ClientPlaybackSession.State.BUFFERING);
         active.onCancel(() -> stopSound(sound));
      }
   }

   public static void unregisterSound(SyncedMediaSound sound) {
      if (sound != null) {
         ACTIVE_SOUNDS.remove(sound);
         ACTIVE_SOUND_POSITIONS.remove(sound);
      }
   }

   public static void stopAllSounds() {
      Minecraft minecraft = Minecraft.getInstance();

      for (SyncedMediaSound sound : ACTIVE_SOUNDS.keySet()) {
         sound.stopFromTracker();
         if (minecraft != null) {
            minecraft.getSoundManager().stop(sound);
         }
      }

      ACTIVE_SOUNDS.clear();
      ACTIVE_SOUND_POSITIONS.clear();
      clear();
   }

   public static void retireForDemandIdle(BlockPos pos, String sessionId) {
      if (pos != null && !PlaybackSessionId.parse(sessionId).isEmpty()) {
         Minecraft minecraft = Minecraft.getInstance();

         for (SyncedMediaSound sound : ACTIVE_SOUNDS.keySet()) {
            if (sessionId.equals(sound.sessionId()) && pos.equals(ACTIVE_SOUND_POSITIONS.get(sound))) {
               ACTIVE_SOUNDS.remove(sound);
               ACTIVE_SOUND_POSITIONS.remove(sound);
               sound.stopForDemandIdle();
               if (minecraft != null) {
                  minecraft.getSoundManager().stop(sound);
               }
            }
         }
      }
   }

   public static void explicitStop(BlockPos pos, String sessionId) {
      suppressRestart(pos, sessionId);
      finish(pos, sessionId);
   }

   public static void suppressRestart(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         long now = System.currentTimeMillis();
         cleanup(now);
         EXPLICITLY_STOPPED.put(stoppedKey(pos, parsedSessionId), now + 30000L);
      }
   }

   public static void finish(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         Object key = keyFor(pos, parsedSessionId);
         ClientPlaybackSession active = ACTIVE.get(key);
         if (active != null
            && ModernTurntableStopPolicy.decide(parsedSessionId.value(), active.sessionId()) == ModernTurntableStopPolicy.Decision.STOP_EXACT
            && ACTIVE.remove(key, active)) {
            active.cancel();
         }
      }
   }

   public static void clear() {
      for (ClientPlaybackSession session : ACTIVE.values()) {
         session.cancel();
      }

      ACTIVE.clear();
      EXPLICITLY_STOPPED.clear();
      ClientMinecartAudioAnchors.clear();
   }

   public static boolean isCurrent(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         long now = System.currentTimeMillis();
         cleanup(now);
         if (isExplicitlyStopped(pos, parsedSessionId, now)) {
            return false;
         } else {
            ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
            return active == null || active.playbackSessionId().equals(parsedSessionId);
         }
      } else {
         return true;
      }
   }

   public static boolean wasExplicitlyStopped(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         long now = System.currentTimeMillis();
         cleanup(now);
         return isExplicitlyStopped(pos, parsedSessionId, now);
      } else {
         return false;
      }
   }

   public static boolean isActiveSession(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         long now = System.currentTimeMillis();
         cleanup(now);
         ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
         return active != null && active.playbackSessionId().equals(parsedSessionId) && !active.isCancelled() && !active.isTerminal();
      } else {
         return false;
      }
   }

   public static String currentSessionId(BlockPos pos) {
      return currentSessionId(pos, "");
   }

   public static String currentSessionId(BlockPos pos, String sessionHint) {
      if (pos == null) {
         return "";
      } else {
         long now = System.currentTimeMillis();
         cleanup(now);
         PlaybackSessionId parsedHint = PlaybackSessionId.parse(sessionHint).orElse(null);
         Object key = parsedHint != null ? keyFor(pos, parsedHint) : AudioUtils.copyPos(pos);
         ClientPlaybackSession active = ACTIVE.get(key);
         return active != null ? active.sessionId() : "";
      }
   }

   public static void markRecovering(BlockPos pos, String sessionId) {
      ClientPlaybackSession active = session(pos, sessionId);
      if (active != null) {
         active.transitionTo(ClientPlaybackSession.State.RECOVERING);
      }
   }

   public static boolean onCancel(BlockPos pos, String sessionId, Runnable action) {
      ClientPlaybackSession active = session(pos, sessionId);
      if (active != null && !active.isCancelled() && !active.isTerminal()) {
         active.onCancel(action);
         return true;
      } else {
         return false;
      }
   }

   public static boolean replaceResource(BlockPos pos, String sessionId, String slot, Runnable cancellationAction) {
      ClientPlaybackSession active = session(pos, sessionId);
      return active != null && active.replaceResource(slot, cancellationAction);
   }

   static ClientPlaybackSession session(BlockPos pos, String sessionId) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      if (pos != null && parsedSessionId != null) {
         ClientPlaybackSession active = ACTIVE.get(keyFor(pos, parsedSessionId));
         return active != null && active.playbackSessionId().equals(parsedSessionId) ? active : null;
      } else {
         return null;
      }
   }

   public static void fail(BlockPos pos, String sessionId) {
      ClientPlaybackSession active = session(pos, sessionId);
      if (active != null) {
         active.fail();
         if (ACTIVE.remove(keyFor(pos, active.playbackSessionId()), active)) {
            active.cancel();
         }
      }
   }

   private static Object keyFor(BlockPos pos, PlaybackSessionId sessionId) {
      UUID entityUuid = ClientMinecartAudioAnchors.entityUuid(sessionId.value());
      return entityUuid != null ? entityUuid : AudioUtils.copyPos(pos);
   }

   private static void cleanup(long now) {
      ACTIVE.entrySet().removeIf(entry -> {
         if (entry.getValue().expiresAtMillis() > now) {
            return false;
         } else {
            entry.getValue().cancel();
            return true;
         }
      });
      EXPLICITLY_STOPPED.entrySet().removeIf(entry -> entry.getValue() <= now);
   }

   private static boolean isExplicitlyStopped(BlockPos pos, PlaybackSessionId sessionId, long now) {
      Long expiresAt = EXPLICITLY_STOPPED.get(stoppedKey(pos, sessionId));
      return expiresAt != null && expiresAt > now;
   }

   private static ModernTurntablePlaybackTracker.StoppedKey stoppedKey(BlockPos pos, PlaybackSessionId sessionId) {
      return new ModernTurntablePlaybackTracker.StoppedKey(AudioUtils.copyPos(pos), sessionId);
   }

   private static void stopSound(SyncedMediaSound sound) {
      ACTIVE_SOUNDS.remove(sound);
      ACTIVE_SOUND_POSITIONS.remove(sound);
      Minecraft minecraft = Minecraft.getInstance();
      Runnable stop = () -> {
         sound.stopFromTracker();
         minecraft.getSoundManager().stop(sound);
      };
      if (minecraft.isSameThread()) {
         stop.run();
      } else {
         minecraft.execute(stop);
      }
   }

   private record StoppedKey(BlockPos pos, PlaybackSessionId sessionId) {
   }
}
