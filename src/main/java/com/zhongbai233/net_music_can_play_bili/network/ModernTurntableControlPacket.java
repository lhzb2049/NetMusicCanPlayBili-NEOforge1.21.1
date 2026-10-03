package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ModernTurntableControlPacket(BlockPos pos, ModernTurntableControlPacket.Action action, long targetMillis) implements CustomPacketPayload {
   public static final Type<ModernTurntableControlPacket> TYPE = new Type(NetworkPayloadIds.id("modern_turntable_control"));
   private static final StreamCodec<RegistryFriendlyByteBuf, ModernTurntableControlPacket.Action> ACTION_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ModernTurntableControlPacket.Action>() {
      public ModernTurntableControlPacket.Action decode(RegistryFriendlyByteBuf buffer) {
         return ModernTurntableControlPacket.Action.byId(buffer.readVarInt());
      }

      public void encode(RegistryFriendlyByteBuf buffer, ModernTurntableControlPacket.Action action) {
         buffer.writeVarInt(action.id());
      }
   };
   public static final StreamCodec<RegistryFriendlyByteBuf, ModernTurntableControlPacket> STREAM_CODEC = StreamCodec.composite(
      BlockPos.STREAM_CODEC, packet -> packet.pos(), ACTION_CODEC, packet -> packet.action(), new StreamCodec<RegistryFriendlyByteBuf, Long>() {
         public Long decode(RegistryFriendlyByteBuf buffer) {
            return buffer.readVarLong();
         }

         public void encode(RegistryFriendlyByteBuf buffer, Long value) {
            buffer.writeVarLong(value);
         }
      }, packet -> packet.targetMillis(), (pos, action, targetMillis) -> new ModernTurntableControlPacket(pos, action, targetMillis)
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ModernTurntableControlPacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
         if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)) {
            if (level.getBlockEntity(payload.pos()) instanceof ModernTurntableBlockEntity turntable) {
               switch (payload.action()) {
                  case REPLAY:
                     turntable.replayFromBeginning(player);
                     break;
                  case PAUSE:
                     turntable.pauseFromControl(level);
                     break;
                  case START:
                     turntable.resumePlayback(player, payload.targetMillis());
                     break;
                  case SEEK:
                     turntable.seekTo(level, payload.targetMillis());
                     break;
                  case TOGGLE_REPEAT_ONE:
                     turntable.toggleRepeatOne();
                     break;
                  case CYCLE_REDSTONE_MODE:
                     turntable.cycleRedstoneMode(level);
                     break;
                  case CYCLE_EXTRACTION_MODE:
                     turntable.cycleExtractionMode();
                     break;
                  case SET_VOLUME:
                     if (player.mayBuild()) {
                        turntable.setVolumePerMille((int)Math.max(0L, Math.min(1000L, payload.targetMillis())));
                     }
               }
            }
         }
      }
   }

   public static enum Action {
      REPLAY,
      PAUSE,
      START,
      SEEK,
      TOGGLE_REPEAT_ONE,
      CYCLE_REDSTONE_MODE,
      CYCLE_EXTRACTION_MODE,
      SET_VOLUME;

      public int id() {
         return this.ordinal();
      }

      public static ModernTurntableControlPacket.Action byId(int id) {
         ModernTurntableControlPacket.Action[] values = values();
         return id >= 0 && id < values.length ? values[id] : START;
      }
   }
}
