package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ControlConsoleEditLeasePacket(BlockPos pos, ControlConsoleEditLeasePacket.Action action, UUID leaseId) implements CustomPacketPayload {
   public static final Type<ControlConsoleEditLeasePacket> TYPE = new Type(NetworkPayloadIds.id("control_console_edit_lease"));
   public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleEditLeasePacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ControlConsoleEditLeasePacket>() {
      public ControlConsoleEditLeasePacket decode(RegistryFriendlyByteBuf buf) {
         BlockPos pos = (BlockPos)BlockPos.STREAM_CODEC.decode(buf);
         ControlConsoleEditLeasePacket.Action action = ControlConsoleEditLeasePacket.Action.fromId(buf.readVarInt());
         UUID leaseId = buf.readBoolean() ? buf.readUUID() : null;
         return new ControlConsoleEditLeasePacket(pos, action, leaseId);
      }

      public void encode(RegistryFriendlyByteBuf buf, ControlConsoleEditLeasePacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         buf.writeVarInt(packet.action().id);
         buf.writeBoolean(packet.leaseId() != null);
         if (packet.leaseId() != null) {
            buf.writeUUID(packet.leaseId());
         }
      }
   };

   public ControlConsoleEditLeasePacket(BlockPos pos, ControlConsoleEditLeasePacket.Action action, UUID leaseId) {
      pos = Objects.requireNonNull(pos, "pos").immutable();
      action = Objects.requireNonNull(action, "action");
      if (action != ControlConsoleEditLeasePacket.Action.OPEN && leaseId == null) {
         throw new IllegalArgumentException("leaseId is required for " + action);
      } else {
         this.pos = pos;
         this.action = action;
         this.leaseId = leaseId;
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ControlConsoleEditLeasePacket payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
         ControlConsoleEditLeaseRegistry.Key var10 = key(level, payload.pos());
         if (payload.action() == ControlConsoleEditLeasePacket.Action.RELEASE) {
            ControlConsoleEditLeaseRegistry.release(var10, player.getUUID(), payload.leaseId());
         } else if (NetworkRateLimiter.allow(player.getUUID(), "control_console_edit_lease", 4)
            && !(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)
            && level.hasChunk(Math.floorDiv(payload.pos().getX(), 16), Math.floorDiv(payload.pos().getZ(), 16))
            && level.getBlockEntity(payload.pos()) instanceof ControlConsoleBlockEntity console) {
            console.claimIfUnowned(player.getUUID());
            if (!console.canEdit(player)) {
               send(player, payload.pos(), ControlConsoleEditLeaseResultPacket.Status.REJECTED, null);
            } else {
               long now = System.currentTimeMillis();
               if (payload.action() == ControlConsoleEditLeasePacket.Action.OPEN) {
                  ControlConsoleEditLeaseRegistry.AcquireResult result = ControlConsoleEditLeaseRegistry.acquire(var10, player.getUUID(), now);
                  send(
                     player,
                     payload.pos(),
                     result.status() == ControlConsoleEditLeaseRegistry.Status.GRANTED
                        ? ControlConsoleEditLeaseResultPacket.Status.GRANTED
                        : ControlConsoleEditLeaseResultPacket.Status.BUSY,
                     result.leaseId()
                  );
               } else {
                  boolean renewed = ControlConsoleEditLeaseRegistry.renew(var10, player.getUUID(), payload.leaseId(), now);
                  send(
                     player,
                     payload.pos(),
                     renewed ? ControlConsoleEditLeaseResultPacket.Status.GRANTED : ControlConsoleEditLeaseResultPacket.Status.EXPIRED,
                     renewed ? payload.leaseId() : null
                  );
               }
            }
         } else {
            send(player, payload.pos(), ControlConsoleEditLeaseResultPacket.Status.REJECTED, null);
         }
      }
   }

   public static ControlConsoleEditLeaseRegistry.Key key(ServerLevel level, BlockPos pos) {
      return new ControlConsoleEditLeaseRegistry.Key(level.dimension().location().toString(), pos.asLong());
   }

   private static void send(ServerPlayer player, BlockPos pos, ControlConsoleEditLeaseResultPacket.Status status, UUID leaseId) {
      PacketDistributor.sendToPlayer(player, new ControlConsoleEditLeaseResultPacket(pos, status, leaseId), new CustomPacketPayload[0]);
   }

   public static enum Action {
      OPEN(0),
      RENEW(1),
      RELEASE(2);

      private final int id;

      private Action(int id) {
         this.id = id;
      }

      private static ControlConsoleEditLeasePacket.Action fromId(int id) {
         for (ControlConsoleEditLeasePacket.Action action : values()) {
            if (action.id == id) {
               return action;
            }
         }

         throw new IllegalArgumentException("unknown edit lease action: " + id);
      }
   }
}
