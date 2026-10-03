package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
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

public record LyricProjectorConfigPacket(
   BlockPos pos, float yaw, float pitch, float scale, float height, float distanceX, float distanceZ, int mode, boolean allowAi
) implements CustomPacketPayload {
   public static final Type<LyricProjectorConfigPacket> TYPE = new Type(NetworkPayloadIds.id("lyric_projector_config"));
   public static final StreamCodec<RegistryFriendlyByteBuf, LyricProjectorConfigPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, LyricProjectorConfigPacket>() {
      public LyricProjectorConfigPacket decode(RegistryFriendlyByteBuf buf) {
         return new LyricProjectorConfigPacket(
            (BlockPos)BlockPos.STREAM_CODEC.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Float)ByteBufCodecs.FLOAT.decode(buf),
            (Integer)ByteBufCodecs.INT.decode(buf),
            (Boolean)ByteBufCodecs.BOOL.decode(buf)
         );
      }

      public void encode(RegistryFriendlyByteBuf buf, LyricProjectorConfigPacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         ByteBufCodecs.FLOAT.encode(buf, packet.yaw());
         ByteBufCodecs.FLOAT.encode(buf, packet.pitch());
         ByteBufCodecs.FLOAT.encode(buf, packet.scale());
         ByteBufCodecs.FLOAT.encode(buf, packet.height());
         ByteBufCodecs.FLOAT.encode(buf, packet.distanceX());
         ByteBufCodecs.FLOAT.encode(buf, packet.distanceZ());
         ByteBufCodecs.INT.encode(buf, packet.mode());
         ByteBufCodecs.BOOL.encode(buf, packet.allowAi());
      }
   };

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(LyricProjectorConfigPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
         if (NetworkRateLimiter.allow(player.getUUID(), "lyric_projector_config", 8)) {
            if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)) {
               if (level.getBlockEntity(payload.pos()) instanceof LyricProjectorBlockEntity be) {
                  be.setProjectionYaw(payload.yaw());
                  be.setProjectionPitch(payload.pitch());
                  be.setProjectionScale(payload.scale());
                  be.setProjectionHeight(payload.height());
                  be.setProjectionDistanceX(payload.distanceX());
                  be.setProjectionDistanceZ(payload.distanceZ());
                  be.setProjectionMode(payload.mode());
                  be.setAllowAi(payload.allowAi());
                  be.markDirtyAndSync();
               }
            }
         }
      }
   }
}
