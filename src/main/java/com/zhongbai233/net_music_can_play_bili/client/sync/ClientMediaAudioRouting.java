package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class ClientMediaAudioRouting {
   private static final Set<PlaybackSourceId> LOCAL_PRIVATE_SOURCES = ConcurrentHashMap.newKeySet();

   private ClientMediaAudioRouting() {
   }

   public static boolean canHear(UUID deviceId, boolean headphoneRouted) {
      if (deviceId != null && LOCAL_PRIVATE_SOURCES.contains(PlaybackSourceId.of(deviceId))) {
         return true;
      } else {
         return !HeadphoneClientState.equipped() ? !headphoneRouted : headphoneRouted && HeadphoneClientState.handlesMediaDevice(deviceId);
      }
   }

   public static void registerLocalPrivateSource(UUID sourceId) {
      if (sourceId != null) {
         LOCAL_PRIVATE_SOURCES.add(PlaybackSourceId.of(sourceId));
      }
   }

   public static void unregisterLocalPrivateSource(UUID sourceId) {
      if (sourceId != null) {
         LOCAL_PRIVATE_SOURCES.remove(PlaybackSourceId.of(sourceId));
      }
   }

   public static void clearLocalPrivateSources() {
      LOCAL_PRIVATE_SOURCES.clear();
   }

   public static Vec3 localHeadPosition() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player == null ? Vec3.ZERO : minecraft.player.position().add(0.0, minecraft.player.getEyeHeight(), 0.0);
   }

   public static Vec3 audiblePosition(UUID deviceId, boolean headphoneRouted) {
      Vec3 pos = headphoneRouted ? localHeadPosition() : ClientMediaPlayback.sourcePosition(deviceId);
      if (pos != null) {
         return pos;
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         return minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
      }
   }
}
