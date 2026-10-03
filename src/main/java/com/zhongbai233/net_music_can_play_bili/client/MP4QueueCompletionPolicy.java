package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

final class MP4QueueCompletionPolicy {
   static final MP4QueueCompletionPolicy INSTANCE = new MP4QueueCompletionPolicy();

   private MP4QueueCompletionPolicy() {
   }

   public void onCompleted(UUID deviceId, String sessionId) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player != null && ClientMediaPlayback.isCurrent(deviceId, sessionId)) {
         ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
         ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
         if (active != null && stack.getItem() instanceof MP4Item && active.sourceLocation().sourceType() == 0) {
            int queueSize = MP4FocusState.queueSize();
            if (queueSize <= 0) {
               MP4FocusState.setPlaying(false);
               MP4Client.updateFocusedLocalState();
               sendControl(MP4PlaybackControlPacket.Action.STOP, 0L);
            } else if (MP4FocusState.repeatMode() == 1) {
               MP4FocusState.setMediaProgress(0.0F);
               MP4Client.updateFocusedLocalState();
               sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
            } else {
               if (MP4FocusState.selectedQueueIndex() < queueSize - 1) {
                  MP4FocusState.nextTrack();
                  MP4Client.updateFocusedLocalState();
                  sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
               } else if (MP4FocusState.repeatMode() == 2) {
                  MP4FocusState.selectQueueIndexForPlayback(0);
                  MP4Client.updateFocusedLocalState();
                  sendControl(MP4PlaybackControlPacket.Action.RESTART, 0L);
               } else {
                  MP4FocusState.setPlaying(false);
                  MP4FocusState.setMediaProgress(0.0F);
                  MP4Client.updateFocusedLocalState();
                  sendControl(MP4PlaybackControlPacket.Action.STOP, 0L);
               }
            }
         } else {
            ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
         }
      } else {
         ClientMediaPlaybackRegistry.finishSession(deviceId, sessionId);
      }
   }

   private static void sendControl(MP4PlaybackControlPacket.Action action, long targetMillis) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null) {
         ItemStack stack = minecraft.player != null
            ? (MP4FocusState.active() ? minecraft.player.getItemInHand(MP4FocusState.hand()) : MP4Item.findAnyInInventory(minecraft.player))
            : ItemStack.EMPTY;
         if (!stack.isEmpty()) {
            UUID deviceId = MP4Item.readDeviceId(stack);
            if (deviceId == null) {
               PacketDistributor.sendToServer(
                  new MP4EnsureDeviceIdPacket(MP4FocusState.active() ? MP4FocusState.hand() : InteractionHand.MAIN_HAND), new CustomPacketPayload[0]
               );
            } else {
               PacketDistributor.sendToServer(
                  new MP4PlaybackControlPacket(
                     action, MP4FocusState.selectedQueueIndex(), Math.round(MP4FocusState.volume() * 1000.0F), Math.max(0L, targetMillis), deviceId
                  ),
                  new CustomPacketPayload[0]
               );
            }
         }
      }
   }
}
