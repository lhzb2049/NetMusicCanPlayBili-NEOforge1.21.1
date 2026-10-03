package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.media.VideoSurfaceBrightness;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record VideoProjectorConfigPacket(
   BlockPos pos, float yaw, float pitch, float scale, float height, float distanceX, float distanceZ, float brightness, int preferredQuality
) implements CustomPacketPayload {
   public static final Type<VideoProjectorConfigPacket> TYPE = new Type(NetworkPayloadIds.id("video_projector_config"));
   public static final StreamCodec<RegistryFriendlyByteBuf, VideoProjectorConfigPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, VideoProjectorConfigPacket>() {
      public VideoProjectorConfigPacket decode(RegistryFriendlyByteBuf buf) {
         return new VideoProjectorConfigPacket(
            (BlockPos)BlockPos.STREAM_CODEC.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Integer)ByteBufCodecs.INT.decode(buf)
         );
      }

      public void encode(RegistryFriendlyByteBuf buf, VideoProjectorConfigPacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         ByteBufCodecs.FLOAT.encode(buf, packet.yaw());
         ByteBufCodecs.FLOAT.encode(buf, packet.pitch());
         ByteBufCodecs.FLOAT.encode(buf, packet.scale());
         ByteBufCodecs.FLOAT.encode(buf, packet.height());
         ByteBufCodecs.FLOAT.encode(buf, packet.distanceX());
         ByteBufCodecs.FLOAT.encode(buf, packet.distanceZ());
         ByteBufCodecs.FLOAT.encode(buf, packet.brightness());
         ByteBufCodecs.INT.encode(buf, packet.preferredQuality());
      }
   };

   public VideoProjectorConfigPacket(
      BlockPos pos, float yaw, float pitch, float scale, float height, float distanceX, float distanceZ, float brightness, int preferredQuality
   ) {
      pos = pos.immutable();
      brightness = VideoSurfaceBrightness.normalize(brightness);
      this.pos = pos;
      this.yaw = yaw;
      this.pitch = pitch;
      this.scale = scale;
      this.height = height;
      this.distanceX = distanceX;
      this.distanceZ = distanceZ;
      this.brightness = brightness;
      this.preferredQuality = preferredQuality;
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(VideoProjectorConfigPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
         if (NetworkRateLimiter.allow(player.getUUID(), "video_projector_config", 8)) {
            if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)) {
               if (level.getBlockEntity(payload.pos()) instanceof VideoProjectorBlockEntity be) {
                  be.setProjectionYaw(payload.yaw());
                  be.setProjectionPitch(payload.pitch());
                  be.setProjectionScale(payload.scale());
                  be.setProjectionHeight(payload.height());
                  be.setProjectionDistanceX(payload.distanceX());
                  be.setProjectionDistanceZ(payload.distanceZ());
                  be.setProjectionBrightness(payload.brightness());
                  be.setPreferredQuality(payload.preferredQuality());
                  be.markDirtyAndSync();
               }
            }
         }
      }
   }
}
