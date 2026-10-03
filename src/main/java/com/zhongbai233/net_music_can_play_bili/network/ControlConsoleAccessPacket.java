package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
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

public record ControlConsoleAccessPacket(
   BlockPos pos, UUID leaseId, UUID operationId, long expectedRevision, ControlConsoleDocument.AccessMode accessMode, Set<UUID> trustedPlayerIds
) implements CustomPacketPayload {
   public static final Type<ControlConsoleAccessPacket> TYPE = new Type(NetworkPayloadIds.id("control_console_access"));
   public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleAccessPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ControlConsoleAccessPacket>() {
      public ControlConsoleAccessPacket decode(RegistryFriendlyByteBuf buf) {
         BlockPos pos = (BlockPos)BlockPos.STREAM_CODEC.decode(buf);
         UUID leaseId = buf.readUUID();
         UUID operationId = buf.readUUID();
         long revision = buf.readLong();
         ControlConsoleDocument.AccessMode mode = ControlConsoleDocument.AccessMode.parse(buf.readUtf(16));
         int count = buf.readVarInt();
         if (count >= 0 && count <= 256) {
            Set<UUID> trusted = new LinkedHashSet<>();

            for (int i = 0; i < count; i++) {
               trusted.add(buf.readUUID());
            }

            return new ControlConsoleAccessPacket(pos, leaseId, operationId, revision, mode, trusted);
         } else {
            throw new IllegalArgumentException("invalid trusted player count: " + count);
         }
      }

      public void encode(RegistryFriendlyByteBuf buf, ControlConsoleAccessPacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         buf.writeUUID(packet.leaseId());
         buf.writeUUID(packet.operationId());
         buf.writeLong(packet.expectedRevision());
         buf.writeUtf(packet.accessMode().name(), 16);
         buf.writeVarInt(packet.trustedPlayerIds().size());
         packet.trustedPlayerIds().forEach(buf::writeUUID);
      }
   };

   public ControlConsoleAccessPacket(
      BlockPos pos, UUID leaseId, UUID operationId, long expectedRevision, ControlConsoleDocument.AccessMode accessMode, Set<UUID> trustedPlayerIds
   ) {
      pos = Objects.requireNonNull(pos, "pos").immutable();
      leaseId = Objects.requireNonNull(leaseId, "leaseId");
      operationId = Objects.requireNonNull(operationId, "operationId");
      accessMode = Objects.requireNonNull(accessMode, "accessMode");
      if (expectedRevision < 0L) {
         throw new IllegalArgumentException("expectedRevision must not be negative");
      } else {
         trustedPlayerIds = Set.copyOf(Objects.requireNonNull(trustedPlayerIds, "trustedPlayerIds"));
         if (trustedPlayerIds.size() > 256) {
            throw new IllegalArgumentException("too many trusted players");
         } else {
            this.pos = pos;
            this.leaseId = leaseId;
            this.operationId = operationId;
            this.expectedRevision = expectedRevision;
            this.accessMode = accessMode;
            this.trustedPlayerIds = trustedPlayerIds;
         }
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ControlConsoleAccessPacket payload, IPayloadContext context) {
      if (!(
         context.player() instanceof ServerPlayer player
            && player.level() instanceof ServerLevel level
            && NetworkRateLimiter.allow(player.getUUID(), "control_console_access", 2)
            && !(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)
            && level.hasChunk(Math.floorDiv(payload.pos().getX(), 16), Math.floorDiv(payload.pos().getZ(), 16))
            && level.getBlockEntity(payload.pos()) instanceof ControlConsoleBlockEntity console
      )) {
         reject(payload, context, -1L);
      } else if (!ControlConsoleEditLeaseRegistry.validate(
         ControlConsoleEditLeasePacket.key(level, payload.pos()), player.getUUID(), payload.leaseId(), System.currentTimeMillis()
      )) {
         reject(payload, context, console.documentRevision());
      } else {
         ControlConsoleBlockEntity.ReplaceResult result = console.replaceAccessControl(
            player, payload.operationId(), payload.expectedRevision(), payload.accessMode(), payload.trustedPlayerIds()
         );
         ControlConsoleConfigResultPacket.Status status = ControlConsoleConfigResultPacket.fromReplaceResult(result);
         PacketDistributor.sendToPlayer(
            player,
            new ControlConsoleConfigResultPacket(
               payload.pos(),
               payload.operationId(),
               console.documentRevision(),
               status,
               status == ControlConsoleConfigResultPacket.Status.CONFLICT ? console.document() : null
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   private static void reject(ControlConsoleAccessPacket payload, IPayloadContext context, long revision) {
      if (context.player() instanceof ServerPlayer player) {
         PacketDistributor.sendToPlayer(
            player,
            new ControlConsoleConfigResultPacket(payload.pos(), payload.operationId(), revision, ControlConsoleConfigResultPacket.Status.REJECTED),
            new CustomPacketPayload[0]
         );
      }
   }
}
