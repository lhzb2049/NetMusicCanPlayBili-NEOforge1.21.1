package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientMediaSoundRegistry {
   private static final Map<PlaybackSourceId, ClientMediaSoundHandle> ACTIVE_SOUNDS = new ConcurrentHashMap<>();

   private ClientMediaSoundRegistry() {
   }

   public static ClientMediaSoundHandle get(UUID sourceId) {
      return sourceId != null ? ACTIVE_SOUNDS.get(PlaybackSourceId.of(sourceId)) : null;
   }

   public static void register(UUID sourceId, String sessionId, ClientMediaSoundHandle sound) {
      tryRegister(sourceId, sessionId, sound);
   }

   public static void register(UUID sourceId, PlaybackSessionId sessionId, ClientMediaSoundHandle sound) {
      tryRegister(sourceId, sessionId, sound);
   }

   public static boolean tryRegister(UUID sourceId, String sessionId, ClientMediaSoundHandle sound) {
      PlaybackSessionId parsedSessionId = PlaybackSessionId.parse(sessionId).orElse(null);
      return tryRegister(sourceId, parsedSessionId, sound);
   }

   public static boolean tryRegister(UUID sourceId, PlaybackSessionId sessionId, ClientMediaSoundHandle sound) {
      if (sourceId != null && sessionId != null && matchesSession(sound, sessionId)) {
         PlaybackSourceId parsedSourceId = PlaybackSourceId.of(sourceId);
         AtomicBoolean installed = new AtomicBoolean();
         AtomicReference<ClientMediaSoundHandle> displaced = new AtomicReference<>();
         ACTIVE_SOUNDS.compute(parsedSourceId, (ignored, current) -> {
            if (!ClientMediaPlaybackRegistry.isCurrent(sourceId, sessionId)) {
               return (ClientMediaSoundHandle)current;
            } else {
               installed.set(true);
               if (current != null && current != sound) {
                  displaced.set(current);
               }

               return sound;
            }
         });
         if (installed.get() && ClientMediaPlaybackRegistry.isCurrent(sourceId, sessionId) && ACTIVE_SOUNDS.get(parsedSourceId) == sound) {
            discard(displaced.get());
            return true;
         } else {
            ACTIVE_SOUNDS.remove(parsedSourceId, sound);
            discard(sound);
            return false;
         }
      } else {
         discard(sound);
         return false;
      }
   }

   private static void discard(ClientMediaSoundHandle sound) {
      if (sound != null) {
         sound.discardWithoutFinishing();
      }
   }

   public static void onSessionAccepted(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         AtomicReference<ClientMediaSoundHandle> retired = new AtomicReference<>();
         ACTIVE_SOUNDS.computeIfPresent(PlaybackSourceId.of(sourceId), (ignored, sound) -> {
            if (matchesSession(sound, sessionId)) {
               return (ClientMediaSoundHandle)sound;
            } else {
               retired.set(sound);
               return null;
            }
         });
         ClientMediaSoundHandle retiredSound = retired.get();
         discard(retiredSound);
      }
   }

   public static void remove(UUID sourceId) {
      if (sourceId != null) {
         ACTIVE_SOUNDS.remove(PlaybackSourceId.of(sourceId));
      }
   }

   public static void removeAndDiscard(UUID sourceId) {
      if (sourceId != null) {
         discard(ACTIVE_SOUNDS.remove(PlaybackSourceId.of(sourceId)));
      }
   }

   public static void finish(UUID sourceId, String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finish(sourceId, parsedSessionId));
   }

   public static void finish(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         ACTIVE_SOUNDS.computeIfPresent(
            PlaybackSourceId.of(sourceId), (ignored, sound) -> (ClientMediaSoundHandle)(matchesSession(sound, sessionId) ? null : sound)
         );
      }
   }

   public static void finishAndDiscard(UUID sourceId, String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(parsedSessionId -> finishAndDiscard(sourceId, parsedSessionId));
   }

   public static void finishAndDiscard(UUID sourceId, PlaybackSessionId sessionId) {
      if (sourceId != null && sessionId != null) {
         AtomicReference<ClientMediaSoundHandle> retired = new AtomicReference<>();
         ACTIVE_SOUNDS.computeIfPresent(PlaybackSourceId.of(sourceId), (ignored, sound) -> {
            if (!matchesSession(sound, sessionId)) {
               return (ClientMediaSoundHandle)sound;
            } else {
               retired.set(sound);
               return null;
            }
         });
         discard(retired.get());
      }
   }

   static boolean matchesSession(ClientMediaSoundHandle sound, PlaybackSessionId sessionId) {
      return sound != null && sessionId != null && sound.playbackSession().filter(sessionId::equals).isPresent();
   }

   public static void clear() {
      ACTIVE_SOUNDS.clear();
   }

   public static void clearAndDiscard() {
      ACTIVE_SOUNDS.forEach((sourceId, sound) -> {
         if (ACTIVE_SOUNDS.remove(sourceId, sound)) {
            discard(sound);
         }
      });
   }
}
