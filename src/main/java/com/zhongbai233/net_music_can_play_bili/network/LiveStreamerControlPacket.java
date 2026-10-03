package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
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

public record LiveStreamerControlPacket(BlockPos pos, LiveStreamerControlPacket.Action action, String roomInput, int value) implements CustomPacketPayload {
   public static final Type<LiveStreamerControlPacket> TYPE = new Type(NetworkPayloadIds.id("live_streamer_control"));
   private static final int MAX_ROOM_INPUT_LENGTH = 256;
   private static final StreamCodec<RegistryFriendlyByteBuf, LiveStreamerControlPacket.Action> ACTION_CODEC = new StreamCodec<RegistryFriendlyByteBuf, LiveStreamerControlPacket.Action>() {
      public LiveStreamerControlPacket.Action decode(RegistryFriendlyByteBuf buffer) {
         return LiveStreamerControlPacket.Action.byId(buffer.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buffer, LiveStreamerControlPacket.Action action) {
         buffer.writeVarInt(action.id());
      }
   };
   public static final StreamCodec<RegistryFriendlyByteBuf, LiveStreamerControlPacket> STREAM_CODEC = StreamCodec.composite(
      BlockPos.STREAM_CODEC,
      packet -> packet.pos(),
      ACTION_CODEC,
      packet -> packet.action(),
      ByteBufCodecs.stringUtf8(256),
      packet -> packet.roomInput(),
      ByteBufCodecs.VAR_INT,
      packet -> packet.value(),
      (pos, action, roomInput, value) -> new LiveStreamerControlPacket(pos, action, roomInput, value)
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(LiveStreamerControlPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
         if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)) {
            if (level.getBlockEntity(payload.pos()) instanceof LiveStreamerBlockEntity streamer) {
               switch (payload.action()) {
                  case START:
                     if (streamer.setRoomId(level, payload.roomInput(), player)) {
                        streamer.startLive(level, player);
                     }
                     break;
                  case STOP:
                     streamer.stopLive();
                     break;
                  case SET_ROOM:
                     streamer.setRoomId(level, payload.roomInput(), player);
                     break;
                  case SET_VOLUME:
                     if (player.mayBuild()) {
                        streamer.setVolumePerMille(Math.max(0, Math.min(1000, payload.value())));
                     }
               }
            }
         }
      }
   }

   public static enum Action {
      START,
      STOP,
      SET_ROOM,
      SET_VOLUME;

      public int id() {
         return this.ordinal();
      }

      public static LiveStreamerControlPacket.Action byId(int id) {
         LiveStreamerControlPacket.Action[] values = values();
         return id >= 0 && id < values.length ? values[id] : STOP;
      }
   }
}
