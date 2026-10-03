package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaDeviceProfile;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaRenderState;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldMediaScreenSpec;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public final class MP4HandheldMediaProfile implements HandheldMediaDeviceProfile {
   public static final MP4HandheldMediaProfile INSTANCE = new MP4HandheldMediaProfile();
   public static final HandheldMediaScreenSpec SCREEN = new HandheldMediaScreenSpec(256, 448, VideoClientProperties.handheld().mp4OffscreenScale());

   private MP4HandheldMediaProfile() {
   }

   @Override
   public HandheldMediaScreenSpec screenSpec() {
      return SCREEN;
   }

   @Override
   public HandheldMediaPlayback playback(UUID deviceId) {
      return ClientMediaPlayback.videoPlayback(deviceId, stateForDevice(deviceId).subtitleAiEnabled());
   }

   @Override
   public HandheldMediaRenderState renderState(UUID deviceId) {
      MP4Item.State state = stateForDevice(deviceId);
      return new HandheldMediaRenderState(state.videoDecodeEnabled(), state.videoQualityCeiling(), state.subtitleAiEnabled());
   }

   @Override
   public boolean hasStartedSound(UUID deviceId, String sessionId) {
      return ClientMediaPlayback.hasAudioStarted(deviceId, sessionId);
   }

   @Override
   public boolean isDeviceAvailable(UUID deviceId) {
      return MP4HandheldVideoClient.isDeviceInHotbar(deviceId);
   }

   @Override
   public String subtitleMode(UUID deviceId) {
      return MP4FocusState.subtitlePrimaryMode() ? "primary" : "secondary";
   }

   private static MP4Item.State stateForDevice(UUID deviceId) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null && deviceId != null) {
         ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
         return MP4Client.stateForHeldRender(stack);
      } else {
         return MP4Item.State.DEFAULT;
      }
   }
}
