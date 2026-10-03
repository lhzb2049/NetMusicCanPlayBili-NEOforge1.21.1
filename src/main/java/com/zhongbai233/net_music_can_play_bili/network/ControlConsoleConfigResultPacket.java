package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleConflictAuthority;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ControlConsoleConfigResultPacket(
   BlockPos pos, UUID operationId, long revision, ControlConsoleConfigResultPacket.Status status, ControlConsoleDocument authoritativeDocument
) implements CustomPacketPayload {
   public static final Type<ControlConsoleConfigResultPacket> TYPE = new Type(NetworkPayloadIds.id("control_console_config_result"));
   private static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = new StreamCodec<RegistryFriendlyByteBuf, UUID>() {
      public UUID decode(RegistryFriendlyByteBuf buffer) {
         return buffer.readUUID();
      }

      public void encode(RegistryFriendlyByteBuf buffer, UUID value) {
         buffer.writeUUID(value);
      }
   };
   public static final StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConfigResultPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ControlConsoleConfigResultPacket>() {
      public ControlConsoleConfigResultPacket decode(RegistryFriendlyByteBuf buf) {
         BlockPos pos = (BlockPos)BlockPos.STREAM_CODEC.decode(buf);
         UUID operationId = (UUID)ControlConsoleConfigResultPacket.UUID_CODEC.decode(buf);
         long revision = buf.readLong();
         ControlConsoleConfigResultPacket.Status status = ControlConsoleConfigResultPacket.Status.fromId(buf.readVarInt());
         ControlConsoleDocument authoritative = buf.readBoolean() ? ControlConsoleDocumentPacketCodec.decode(buf) : null;
         return new ControlConsoleConfigResultPacket(pos, operationId, revision, status, authoritative);
      }

      public void encode(RegistryFriendlyByteBuf buf, ControlConsoleConfigResultPacket packet) {
         BlockPos.STREAM_CODEC.encode(buf, packet.pos());
         ControlConsoleConfigResultPacket.UUID_CODEC.encode(buf, packet.operationId());
         buf.writeLong(packet.revision());
         buf.writeVarInt(packet.status().id());
         buf.writeBoolean(packet.authoritativeDocument() != null);
         if (packet.authoritativeDocument() != null) {
            ControlConsoleDocumentPacketCodec.encode(buf, packet.authoritativeDocument());
         }
      }
   };

   public ControlConsoleConfigResultPacket(BlockPos pos, UUID operationId, long revision, ControlConsoleConfigResultPacket.Status status) {
      this(pos, operationId, revision, status, null);
   }

   public ControlConsoleConfigResultPacket(
      BlockPos pos, UUID operationId, long revision, ControlConsoleConfigResultPacket.Status status, ControlConsoleDocument authoritativeDocument
   ) {
      pos = Objects.requireNonNull(pos, "pos").immutable();
      operationId = Objects.requireNonNull(operationId, "operationId");
      status = Objects.requireNonNull(status, "status");
      if (revision < -1L) {
         throw new IllegalArgumentException("revision must be -1 or non-negative");
      } else {
         ControlConsoleConflictAuthority.validate(status == ControlConsoleConfigResultPacket.Status.CONFLICT, revision, authoritativeDocument);
         this.pos = pos;
         this.operationId = operationId;
         this.revision = revision;
         this.status = status;
         this.authoritativeDocument = authoritativeDocument;
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(ControlConsoleConfigResultPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> HolographicScreenConfigTestScreen.acceptControlConsoleConfigResult(payload));
   }

   public static ControlConsoleConfigResultPacket.Status fromReplaceResult(ControlConsoleBlockEntity.ReplaceResult result) {
      return switch (result) {
         case APPLIED -> ControlConsoleConfigResultPacket.Status.APPLIED;
         case DUPLICATE -> ControlConsoleConfigResultPacket.Status.DUPLICATE;
         case CONFLICT -> ControlConsoleConfigResultPacket.Status.CONFLICT;
         case READ_ONLY -> ControlConsoleConfigResultPacket.Status.READ_ONLY;
         case REJECTED -> ControlConsoleConfigResultPacket.Status.REJECTED;
      };
   }

   public static enum Status {
      APPLIED(0),
      DUPLICATE(1),
      CONFLICT(2),
      REJECTED(3),
      READ_ONLY(4);

      private final int id;

      private Status(int id) {
         this.id = id;
      }

      int id() {
         return this.id;
      }

      static ControlConsoleConfigResultPacket.Status fromId(int id) {
         for (ControlConsoleConfigResultPacket.Status status : values()) {
            if (status.id == id) {
               return status;
            }
         }

         throw new IllegalArgumentException("unknown control console result status: " + id);
      }
   }
}
