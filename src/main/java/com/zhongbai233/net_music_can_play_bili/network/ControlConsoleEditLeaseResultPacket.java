package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ControlConsoleEditLeaseResultPacket(BlockPos pos, ControlConsoleEditLeaseResultPacket.Status status, UUID leaseId) implements CustomPacketPayload {
   public static final Type<ControlConsoleEditLeaseResultPacket> TYPE = new Type(NetworkPayloadIds.id("control_console_edit_lease_result"));
   public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleEditLeaseResultPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ControlConsoleEditLeaseResultPacket>() {
      public ControlConsoleEditLeaseResultPacket decode(RegistryFriendlyByteBuf buf) {
         BlockPos pos = (BlockPos)BlockPos.STREAM_CODEC.decode(buf);
         ControlConsoleEditLeaseResultPacket.Status status = ControlConsoleEditLeaseResultPacket.Status.fromId(buf.readVarInt());
         UUID leaseId = buf.readBoolean() ? buf.readUUID() : null;
         return new ControlConsoleEditLeaseResultPacket(pos, status, leaseId);
      }

      public void encode(RegistryFriendlyByteBuf buf, ControlConsoleEditLeaseResultPacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         buf.writeVarInt(packet.status().id);
         buf.writeBoolean(packet.leaseId() != null);
         if (packet.leaseId() != null) {
            buf.writeUUID(packet.leaseId());
         }
      }
   };

   public ControlConsoleEditLeaseResultPacket(BlockPos pos, ControlConsoleEditLeaseResultPacket.Status status, UUID leaseId) {
      pos = Objects.requireNonNull(pos, "pos").immutable();
      status = Objects.requireNonNull(status, "status");
      if (status == ControlConsoleEditLeaseResultPacket.Status.GRANTED && leaseId == null) {
         throw new IllegalArgumentException("granted lease requires leaseId");
      } else {
         this.pos = pos;
         this.status = status;
         this.leaseId = leaseId;
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ControlConsoleEditLeaseResultPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> ControlConsoleClient.acceptLeaseResult(payload));
   }

   public static enum Status {
      GRANTED(0),
      BUSY(1),
      REJECTED(2),
      EXPIRED(3);

      private final int id;

      private Status(int id) {
         this.id = id;
      }

      private static ControlConsoleEditLeaseResultPacket.Status fromId(int id) {
         for (ControlConsoleEditLeaseResultPacket.Status status : values()) {
            if (status.id == id) {
               return status;
            }
         }

         throw new IllegalArgumentException("unknown edit lease result status: " + id);
      }
   }
}
