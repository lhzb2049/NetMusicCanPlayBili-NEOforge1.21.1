package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class ClientMinecartAudioAnchors {
   private static final ConcurrentHashMap<PlaybackSessionId, ClientMinecartAudioAnchors.Anchor> ANCHORS = new ConcurrentHashMap<>();

   private ClientMinecartAudioAnchors() {
   }

   public static void register(String sessionId, int entityId, UUID entityUuid) {
      PlaybackSessionId key = PlaybackSessionId.parse(sessionId).orElse(null);
      if (key != null && entityId >= 0 && entityUuid != null) {
         ANCHORS.put(key, new ClientMinecartAudioAnchors.Anchor(entityId, entityUuid, null));
      }
   }

   public static boolean isMoving(String sessionId) {
      return PlaybackSessionId.parse(sessionId).map(ANCHORS::containsKey).orElse(false);
   }

   public static UUID entityUuid(String sessionId) {
      ClientMinecartAudioAnchors.Anchor anchor = PlaybackSessionId.parse(sessionId).map(ANCHORS::get).orElse(null);
      return anchor != null ? anchor.entityUuid() : null;
   }

   public static Entity entity(String sessionId) {
      PlaybackSessionId key = PlaybackSessionId.parse(sessionId).orElse(null);
      ClientMinecartAudioAnchors.Anchor anchor = key != null ? ANCHORS.get(key) : null;
      Minecraft minecraft = Minecraft.getInstance();
      if (anchor != null && minecraft.level != null) {
         Entity entity = minecraft.level.getEntity(anchor.entityId());
         return entity != null && !entity.isRemoved() && anchor.entityUuid().equals(entity.getUUID()) ? entity : null;
      } else {
         return null;
      }
   }

   public static Vec3 currentPosition(String sessionId) {
      PlaybackSessionId key = PlaybackSessionId.parse(sessionId).orElse(null);
      Entity entity = key != null ? entity(sessionId) : null;
      if (entity == null) {
         return null;
      } else {
         Vec3 position = entity.position().add(0.0, 0.5, 0.0);
         ANCHORS.computeIfPresent(key, (ignored, anchor) -> new ClientMinecartAudioAnchors.Anchor(anchor.entityId(), anchor.entityUuid(), position));
         return position;
      }
   }

   public static Vec3 position(String sessionId) {
      Vec3 current = currentPosition(sessionId);
      if (current != null) {
         return current;
      } else {
         ClientMinecartAudioAnchors.Anchor anchor = PlaybackSessionId.parse(sessionId).map(ANCHORS::get).orElse(null);
         return anchor != null ? anchor.lastPosition() : null;
      }
   }

   public static void forget(String sessionId) {
      PlaybackSessionId.parse(sessionId).ifPresent(ANCHORS::remove);
   }

   public static void clear() {
      ANCHORS.clear();
   }

   private record Anchor(int entityId, UUID entityUuid, Vec3 lastPosition) {
   }
}
