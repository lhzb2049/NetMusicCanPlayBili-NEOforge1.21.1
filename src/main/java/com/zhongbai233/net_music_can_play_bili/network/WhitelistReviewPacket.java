package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicBiliServerCommands;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WhitelistReviewPacket(
   List<WhitelistReviewPacket.Entry> entries,
   List<WhitelistReviewPacket.RemovalRecord> removalRecords,
   int entryOffset,
   int totalEntries,
   int removalOffset,
   int totalRemovalRecords,
   boolean openScreen
) implements CustomPacketPayload {
   public static final Type<WhitelistReviewPacket> TYPE = new Type(NetworkPayloadIds.id("whitelist_review"));
   public static final int ENTRY_PAGE_SIZE = 64;
   public static final int REMOVAL_PAGE_SIZE = 32;
   private static final int MAX_FIELD_LENGTH = 512;
   private static final int MAX_COMMENTS_PER_ENTRY = 2;
   private static final int MAX_COMMENT_LENGTH = 256;
   public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, WhitelistReviewPacket>() {
      public WhitelistReviewPacket decode(RegistryFriendlyByteBuf buffer) {
         int entryOffset = WhitelistReviewPacket.readNonNegative(buffer, "entry offset");
         int totalEntries = WhitelistReviewPacket.readNonNegative(buffer, "entry total");
         int removalOffset = WhitelistReviewPacket.readNonNegative(buffer, "removal offset");
         int totalRemovals = WhitelistReviewPacket.readNonNegative(buffer, "removal total");
         int count = WhitelistReviewPacket.readCount(buffer, 64, "entries");
         List<WhitelistReviewPacket.Entry> entries = new ArrayList<>(count);

         for (int i = 0; i < count; i++) {
            String type = buffer.readUtf(32);
            String id = buffer.readUtf(512);
            String addedAt = buffer.readUtf(128);
            String addedByName = buffer.readUtf(128);
            String addedByUuid = buffer.readUtf(64);
            String originalInput = buffer.readUtf(512);
            int commentCount = WhitelistReviewPacket.readCount(buffer, 2, "entry comments");
            List<WhitelistReviewPacket.Comment> comments = new ArrayList<>(commentCount);

            for (int j = 0; j < commentCount; j++) {
               comments.add(new WhitelistReviewPacket.Comment(buffer.readUtf(256), buffer.readUtf(128), buffer.readUtf(64), buffer.readUtf(64)));
            }

            entries.add(new WhitelistReviewPacket.Entry(type, id, addedAt, addedByName, addedByUuid, originalInput, List.copyOf(comments)));
         }

         int removalCount = WhitelistReviewPacket.readCount(buffer, 32, "removal records");
         List<WhitelistReviewPacket.RemovalRecord> removals = new ArrayList<>(removalCount);

         for (int i = 0; i < removalCount; i++) {
            String recordId = buffer.readUtf(64);
            WhitelistReviewPacket.Entry entry = WhitelistReviewPacket.readEntry(buffer);
            removals.add(
               new WhitelistReviewPacket.RemovalRecord(recordId, entry, buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(64), buffer.readUtf(256))
            );
         }

         if (entryOffset <= totalEntries && removalOffset <= totalRemovals) {
            return new WhitelistReviewPacket(
               List.copyOf(entries), List.copyOf(removals), entryOffset, totalEntries, removalOffset, totalRemovals, buffer.readBoolean()
            );
         } else {
            throw new DecoderException("Invalid whitelist review page metadata");
         }
      }

      public void encode(RegistryFriendlyByteBuf buffer, WhitelistReviewPacket packet) {
         int totalEntries = Math.max(0, packet.totalEntries());
         int totalRemovals = Math.max(0, packet.totalRemovalRecords());
         buffer.writeVarInt(Math.min(Math.max(0, packet.entryOffset()), totalEntries));
         buffer.writeVarInt(totalEntries);
         buffer.writeVarInt(Math.min(Math.max(0, packet.removalOffset()), totalRemovals));
         buffer.writeVarInt(totalRemovals);
         List<WhitelistReviewPacket.Entry> safeEntries = packet.entries() == null ? List.of() : packet.entries();
         if (safeEntries.size() > 64) {
            throw new EncoderException("Whitelist review entry page exceeds limit");
         } else {
            int count = safeEntries.size();
            buffer.writeVarInt(count);

            for (int i = 0; i < count; i++) {
               WhitelistReviewPacket.Entry entry = safeEntries.get(i);
               buffer.writeUtf(WhitelistReviewPacket.bounded(entry.type(), 32), 32);
               buffer.writeUtf(WhitelistReviewPacket.safe(entry.id()), 512);
               buffer.writeUtf(WhitelistReviewPacket.bounded(entry.addedAt(), 128), 128);
               buffer.writeUtf(WhitelistReviewPacket.bounded(entry.addedByName(), 128), 128);
               buffer.writeUtf(WhitelistReviewPacket.bounded(entry.addedByUuid(), 64), 64);
               buffer.writeUtf(WhitelistReviewPacket.bounded(entry.originalInput(), 512), 512);
               List<WhitelistReviewPacket.Comment> comments = entry.comments() == null ? List.of() : entry.comments();
               int commentCount = Math.min(2, comments.size());
               buffer.writeVarInt(commentCount);
               int commentStart = Math.max(0, comments.size() - commentCount);

               for (int j = commentStart; j < comments.size(); j++) {
                  WhitelistReviewPacket.Comment comment = comments.get(j);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(comment.text(), 256), 256);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(comment.authorName(), 128), 128);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(comment.authorUuid(), 64), 64);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(comment.createdAt(), 64), 64);
               }
            }

            List<WhitelistReviewPacket.RemovalRecord> safeRemovals = packet.removalRecords() == null ? List.of() : packet.removalRecords();
            if (safeRemovals.size() > 32) {
               throw new EncoderException("Whitelist review history page exceeds limit");
            } else {
               int removalCount = safeRemovals.size();
               buffer.writeVarInt(removalCount);

               for (int i = 0; i < removalCount; i++) {
                  WhitelistReviewPacket.RemovalRecord record = safeRemovals.get(i);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(record.recordId(), 64), 64);
                  WhitelistReviewPacket.writeEntry(buffer, record.entry());
                  buffer.writeUtf(WhitelistReviewPacket.bounded(record.removedAt(), 128), 128);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(record.removedByName(), 128), 128);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(record.removedByUuid(), 64), 64);
                  buffer.writeUtf(WhitelistReviewPacket.bounded(record.note(), 256), 256);
               }

               buffer.writeBoolean(packet.openScreen());
            }
         }
      }
   };

   public WhitelistReviewPacket(List<WhitelistReviewPacket.Entry> entries) {
      this(entries, List.of(), 0, entries == null ? 0 : entries.size(), 0, 0, true);
   }

   public WhitelistReviewPacket(List<WhitelistReviewPacket.Entry> entries, List<WhitelistReviewPacket.RemovalRecord> removalRecords) {
      this(entries, removalRecords, 0, entries == null ? 0 : entries.size(), 0, removalRecords == null ? 0 : removalRecords.size(), true);
   }

   public WhitelistReviewPacket(
      List<WhitelistReviewPacket.Entry> entries,
      List<WhitelistReviewPacket.RemovalRecord> removalRecords,
      int entryOffset,
      int totalEntries,
      int removalOffset,
      int totalRemovalRecords
   ) {
      this(entries, removalRecords, entryOffset, totalEntries, removalOffset, totalRemovalRecords, false);
   }

   private static WhitelistReviewPacket.Entry readEntry(RegistryFriendlyByteBuf buffer) {
      String type = buffer.readUtf(32);
      String id = buffer.readUtf(512);
      String addedAt = buffer.readUtf(128);
      String addedByName = buffer.readUtf(128);
      String addedByUuid = buffer.readUtf(64);
      String originalInput = buffer.readUtf(512);
      int count = readCount(buffer, 2, "removal entry comments");
      List<WhitelistReviewPacket.Comment> comments = new ArrayList<>(count);

      for (int i = 0; i < count; i++) {
         comments.add(new WhitelistReviewPacket.Comment(buffer.readUtf(256), buffer.readUtf(128), buffer.readUtf(64), buffer.readUtf(64)));
      }

      return new WhitelistReviewPacket.Entry(type, id, addedAt, addedByName, addedByUuid, originalInput, List.copyOf(comments));
   }

   private static void writeEntry(RegistryFriendlyByteBuf buffer, WhitelistReviewPacket.Entry entry) {
      WhitelistReviewPacket.Entry safeEntry = entry == null ? new WhitelistReviewPacket.Entry("", "", "", "", "", "", List.of()) : entry;
      buffer.writeUtf(bounded(safeEntry.type(), 32), 32);
      buffer.writeUtf(bounded(safeEntry.id(), 512), 512);
      buffer.writeUtf(bounded(safeEntry.addedAt(), 128), 128);
      buffer.writeUtf(bounded(safeEntry.addedByName(), 128), 128);
      buffer.writeUtf(bounded(safeEntry.addedByUuid(), 64), 64);
      buffer.writeUtf(bounded(safeEntry.originalInput(), 512), 512);
      List<WhitelistReviewPacket.Comment> comments = safeEntry.comments() == null ? List.of() : safeEntry.comments();
      int commentCount = Math.min(2, comments.size());
      buffer.writeVarInt(commentCount);
      int start = Math.max(0, comments.size() - commentCount);

      for (int i = start; i < comments.size(); i++) {
         WhitelistReviewPacket.Comment comment = comments.get(i);
         buffer.writeUtf(bounded(comment.text(), 256), 256);
         buffer.writeUtf(bounded(comment.authorName(), 128), 128);
         buffer.writeUtf(bounded(comment.authorUuid(), 64), 64);
         buffer.writeUtf(bounded(comment.createdAt(), 64), 64);
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries) {
      return create(entries, List.of());
   }

   public static WhitelistReviewPacket create(List<BiliWhitelistManager.Entry> entries, List<BiliWhitelistManager.RemovalRecord> removalRecords) {
      return create(entries, removalRecords, 0, entries == null ? 0 : entries.size(), 0, removalRecords == null ? 0 : removalRecords.size(), false);
   }

   private static WhitelistReviewPacket create(
      List<BiliWhitelistManager.Entry> entries,
      List<BiliWhitelistManager.RemovalRecord> removalRecords,
      int entryOffset,
      int totalEntries,
      int removalOffset,
      int totalRemovals,
      boolean openScreen
   ) {
      List<WhitelistReviewPacket.Entry> result = new ArrayList<>();
      if (entries != null) {
         for (BiliWhitelistManager.Entry entry : entries) {
            List<WhitelistReviewPacket.Comment> comments = new ArrayList<>();
            if (entry.comments != null) {
               int start = Math.max(0, entry.comments.size() - 2);

               for (int i = start; i < entry.comments.size(); i++) {
                  comments.add(packetComment(entry.comments.get(i)));
               }
            }

            result.add(
               new WhitelistReviewPacket.Entry(
                  bounded(entry.type, 32),
                  safe(entry.id),
                  bounded(entry.addedAt, 128),
                  bounded(entry.addedByName, 128),
                  bounded(entry.addedByUuid, 64),
                  bounded(entry.originalInput, 512),
                  List.copyOf(comments)
               )
            );
            if (result.size() >= 64) {
               break;
            }
         }
      }

      List<WhitelistReviewPacket.RemovalRecord> records = new ArrayList<>();
      if (removalRecords != null) {
         for (BiliWhitelistManager.RemovalRecord record : removalRecords) {
            records.add(
               new WhitelistReviewPacket.RemovalRecord(
                  record.recordId, packetEntry(record.entry), record.removedAt, record.removedByName, record.removedByUuid, record.note
               )
            );
            if (records.size() >= 32) {
               break;
            }
         }
      }

      return new WhitelistReviewPacket(
         List.copyOf(result), List.copyOf(records), entryOffset, Math.max(0, totalEntries), removalOffset, Math.max(0, totalRemovals), openScreen
      );
   }

   private static WhitelistReviewPacket.Entry packetEntry(BiliWhitelistManager.Entry entry) {
      if (entry == null) {
         return new WhitelistReviewPacket.Entry("", "", "", "", "", "", List.of());
      } else {
         List<WhitelistReviewPacket.Comment> comments = new ArrayList<>();
         if (entry.comments != null) {
            int start = Math.max(0, entry.comments.size() - 2);

            for (int i = start; i < entry.comments.size(); i++) {
               comments.add(packetComment(entry.comments.get(i)));
            }
         }

         return packetEntry(entry, comments);
      }
   }

   private static WhitelistReviewPacket.Entry packetEntry(BiliWhitelistManager.Entry entry, List<WhitelistReviewPacket.Comment> comments) {
      return new WhitelistReviewPacket.Entry(
         bounded(entry.type, 32),
         bounded(entry.id, 512),
         bounded(entry.addedAt, 128),
         bounded(entry.addedByName, 128),
         bounded(entry.addedByUuid, 64),
         bounded(entry.originalInput, 512),
         List.copyOf(comments)
      );
   }

   private static WhitelistReviewPacket.Comment packetComment(BiliWhitelistManager.ReviewComment comment) {
      return comment == null
         ? new WhitelistReviewPacket.Comment("", "", "", "")
         : new WhitelistReviewPacket.Comment(
            bounded(comment.text, 256), bounded(comment.authorName, 128), bounded(comment.authorUuid, 64), bounded(comment.createdAt, 64)
         );
   }

   public static void sendTo(ServerPlayer player) {
      sendTo(player, 0, 0, true);
   }

   public static void sendTo(ServerPlayer player, int requestedEntryOffset, int requestedRemovalOffset) {
      sendTo(player, requestedEntryOffset, requestedRemovalOffset, false);
   }

   private static void sendTo(ServerPlayer player, int requestedEntryOffset, int requestedRemovalOffset, boolean openScreen) {
      if (player != null) {
         MinecraftServer server = player.level().getServer();
         BiliWhitelistManager.ReviewSnapshot snapshot = BiliWhitelistManager.reviewSnapshot(server, requestedEntryOffset, 64, requestedRemovalOffset, 32);
         PacketDistributor.sendToPlayer(
            player,
            create(
               snapshot.entries(),
               snapshot.removalRecords(),
               snapshot.entryOffset(),
               snapshot.totalEntries(),
               snapshot.removalOffset(),
               snapshot.totalRemovalRecords(),
               openScreen
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   public static boolean canOpen(ServerPlayer player) {
      return player != null && NetMusicBiliServerCommands.canManageWhitelist(player.createCommandSourceStack());
   }

   public static void handle(WhitelistReviewPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> WhitelistReviewScreen.openOrUpdate(payload));
   }

   public static void rejectUnauthorized(ServerPlayer player) {
      if (player != null) {
         player.sendSystemMessage(Component.literal("需要白名单管理权限（默认 OP4）才能进行审核。").withStyle(ChatFormatting.RED));
      }
   }

   private static int readCount(RegistryFriendlyByteBuf buffer, int maximum, String field) {
      int count = buffer.readVarInt();
      if (count >= 0 && count <= maximum) {
         return count;
      } else {
         throw new DecoderException("Invalid whitelist review " + field + " count: " + count + " (max " + maximum + ")");
      }
   }

   private static int readNonNegative(RegistryFriendlyByteBuf buffer, String field) {
      int value = buffer.readVarInt();
      if (value < 0) {
         throw new DecoderException("Invalid whitelist review " + field + ": " + value);
      } else {
         return value;
      }
   }

   private static String safe(String value) {
      return value == null ? "" : value;
   }

   private static String bounded(String value, int maximum) {
      String safe = safe(value);
      return safe.length() <= maximum ? safe : safe.substring(0, maximum);
   }

   public record Comment(String text, String authorName, String authorUuid, String createdAt) {
   }

   public record Entry(
      String type, String id, String addedAt, String addedByName, String addedByUuid, String originalInput, List<WhitelistReviewPacket.Comment> comments
   ) {
      public Entry(String type, String id, String addedAt, String addedByName, String addedByUuid, String originalInput) {
         this(type, id, addedAt, addedByName, addedByUuid, originalInput, List.of());
      }
   }

   public record RemovalRecord(String recordId, WhitelistReviewPacket.Entry entry, String removedAt, String removedByName, String removedByUuid, String note) {
   }
}
