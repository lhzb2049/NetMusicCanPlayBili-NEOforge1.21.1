package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WhitelistReviewMutationResultPacket(
   WhitelistReviewMutationResultPacket.Mutation mutation, WhitelistReviewMutationResultPacket.Status status, String targetId, String message, long requestId
) implements CustomPacketPayload {
   private static final int MAX_TARGET_LENGTH = 512;
   private static final int MAX_MESSAGE_LENGTH = 256;
   public static final Type<WhitelistReviewMutationResultPacket> TYPE = new Type(NetworkPayloadIds.id("whitelist_review_mutation_result"));
   public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewMutationResultPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewMutationResultPacket>() {
      public WhitelistReviewMutationResultPacket decode(RegistryFriendlyByteBuf buffer) {
         return new WhitelistReviewMutationResultPacket(
            WhitelistReviewMutationResultPacket.Mutation.byId(buffer.readVarInt()),
            WhitelistReviewMutationResultPacket.Status.byId(buffer.readVarInt()),
            buffer.readUtf(512),
            buffer.readUtf(256),
            WhitelistReviewMutationResultPacket.readRequestId(buffer)
         );
      }

      public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewMutationResultPacket packet) {
         buffer.writeVarInt(packet.mutation().ordinal());
         buffer.writeVarInt(packet.status().ordinal());
         buffer.writeUtf(WhitelistReviewMutationResultPacket.bounded(packet.targetId(), 512), 512);
         buffer.writeUtf(WhitelistReviewMutationResultPacket.bounded(packet.message(), 256), 256);
         buffer.writeVarLong(Math.max(0L, packet.requestId()));
      }
   };

   public WhitelistReviewMutationResultPacket(
      WhitelistReviewMutationResultPacket.Mutation mutation, WhitelistReviewMutationResultPacket.Status status, String targetId, String message
   ) {
      this(mutation, status, targetId, message, 0L);
   }

   public boolean successful() {
      return this.status == WhitelistReviewMutationResultPacket.Status.SUCCESS;
   }

   public static void sendTo(
      ServerPlayer player,
      WhitelistReviewMutationResultPacket.Mutation mutation,
      WhitelistReviewMutationResultPacket.Status status,
      String targetId,
      String message,
      long requestId
   ) {
      if (player != null) {
         PacketDistributor.sendToPlayer(
            player, new WhitelistReviewMutationResultPacket(mutation, status, targetId, message, requestId), new CustomPacketPayload[0]
         );
      }
   }

   private static String bounded(String value, int maximum) {
      String safe = value == null ? "" : value;
      return safe.length() <= maximum ? safe : safe.substring(0, maximum);
   }

   private static long readRequestId(RegistryFriendlyByteBuf buffer) {
      long value = buffer.readVarLong();
      if (value < 0L) {
         throw new DecoderException("Invalid whitelist mutation request id: " + value);
      } else {
         return value;
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(WhitelistReviewMutationResultPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> WhitelistReviewScreen.routeMutationResult(payload));
   }

   public static enum Mutation {
      REMOVE,
      COMMENT,
      REFRESH,
      PREVIEW,
      PREVIEW_SEEK;

      private static WhitelistReviewMutationResultPacket.Mutation byId(int id) {
         WhitelistReviewMutationResultPacket.Mutation[] values = values();
         if (id >= 0 && id < values.length) {
            return values[id];
         } else {
            throw new DecoderException("Invalid whitelist mutation id: " + id);
         }
      }
   }

   public static enum Status {
      SUCCESS,
      RATE_LIMITED,
      DENIED,
      MISSING,
      INVALID,
      REQUIRED,
      STALE,
      SAVE_FAILED;

      private static WhitelistReviewMutationResultPacket.Status byId(int id) {
         WhitelistReviewMutationResultPacket.Status[] values = values();
         if (id >= 0 && id < values.length) {
            return values[id];
         } else {
            throw new DecoderException("Invalid whitelist mutation result id: " + id);
         }
      }
   }
}
