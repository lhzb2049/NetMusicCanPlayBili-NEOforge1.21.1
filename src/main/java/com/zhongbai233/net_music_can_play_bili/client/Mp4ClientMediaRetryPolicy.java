package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaRetryPolicy;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackRetryPacket;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

final class Mp4ClientMediaRetryPolicy implements ClientMediaRetryPolicy {
   static final Mp4ClientMediaRetryPolicy INSTANCE = new Mp4ClientMediaRetryPolicy();
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long STREAM_RETRY_DELAY_MILLIS = 750L;

   private Mp4ClientMediaRetryPolicy() {
   }

   @Override
   public long retryDelayMillis() {
      return 750L;
   }

   @Override
   public void onRetryScheduled(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
      long targetMillis = Math.max(0L, active.elapsedMillis());
      LOGGER.warn(
         "MP4 音频流初始化失败，将自动刷新直链并重试一次: owner={} session={} song='{}' at={}ms reason={}",
         new Object[]{
            deviceId, sessionId, active.songName(), targetMillis, error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage()
         }
      );
   }

   @Override
   public void scheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
      this.tryScheduleRetry(deviceId, sessionId, active, error);
   }

   @Override
   public boolean tryScheduleRetry(UUID deviceId, String sessionId, ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) {
      Minecraft client = Minecraft.getInstance();
      if (client.player != null && client.getConnection() != null) {
         ItemStack stack = MP4Item.findByDeviceId(client.player, deviceId);
         if (!(stack.getItem() instanceof MP4Item)) {
            return false;
         } else {
            PacketDistributor.sendToServer(new MP4PlaybackRetryPacket(deviceId, sessionId, Math.max(0L, active.elapsedMillis())), new CustomPacketPayload[0]);
            return true;
         }
      } else {
         return false;
      }
   }
}
