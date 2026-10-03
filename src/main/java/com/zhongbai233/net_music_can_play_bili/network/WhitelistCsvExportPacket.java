package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.WhitelistCsvExportClient;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record WhitelistCsvExportPacket(String transferId, String fileName, int chunkIndex, int chunkCount, int totalBytes, byte[] chunk)
   implements CustomPacketPayload {
   public static final Type<WhitelistCsvExportPacket> TYPE = new Type(NetworkPayloadIds.id("whitelist_csv_export"));
   public static final int CHUNK_BYTES = 65536;
   public static final int MAX_CHUNKS = 1024;
   public static final int MAX_TOTAL_BYTES = 67108864;
   private static final int MAX_TRANSFER_ID_LENGTH = 64;
   private static final int MAX_FILE_NAME_LENGTH = 128;
   public static final StreamCodec<RegistryFriendlyByteBuf, WhitelistCsvExportPacket> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, WhitelistCsvExportPacket>() {
      public WhitelistCsvExportPacket decode(RegistryFriendlyByteBuf buffer) {
         String transferId = buffer.readUtf(64);
         String fileName = buffer.readUtf(128);
         int chunkIndex = WhitelistCsvExportPacket.readBoundedInt(buffer, 1023, "chunk index");
         int chunkCount = WhitelistCsvExportPacket.readBoundedInt(buffer, 1024, "chunk count");
         int totalBytes = WhitelistCsvExportPacket.readBoundedInt(buffer, 67108864, "total bytes");
         byte[] chunk = buffer.readByteArray(65536);
         WhitelistCsvExportPacket packet = new WhitelistCsvExportPacket(transferId, fileName, chunkIndex, chunkCount, totalBytes, chunk);
         WhitelistCsvExportPacket.validate(packet, false);
         return packet;
      }

      public void encode(RegistryFriendlyByteBuf buffer, WhitelistCsvExportPacket packet) {
         WhitelistCsvExportPacket.validate(packet, true);
         buffer.writeUtf(packet.transferId(), 64);
         buffer.writeUtf(packet.fileName(), 128);
         buffer.writeVarInt(packet.chunkIndex());
         buffer.writeVarInt(packet.chunkCount());
         buffer.writeVarInt(packet.totalBytes());
         buffer.writeByteArray(packet.chunk());
      }
   };

   public WhitelistCsvExportPacket(String fileName, String csv) {
      this(singlePacket(fileName, csv));
   }

   private WhitelistCsvExportPacket(WhitelistCsvExportPacket packet) {
      this(packet.transferId, packet.fileName, packet.chunkIndex, packet.chunkCount, packet.totalBytes, packet.chunk);
   }

   public static List<WhitelistCsvExportPacket> createChunks(String csv) {
      byte[] bytes = encodeCsv(csv);
      WhitelistCsvExportPacket.TransferInfo transfer = createTransfer(bytes.length);
      List<WhitelistCsvExportPacket> packets = new ArrayList<>(transfer.chunkCount());

      for (int index = 0; index < transfer.chunkCount(); index++) {
         packets.add(packetAt(transfer, bytes, index));
      }

      return List.copyOf(packets);
   }

   public static int exportTo(ServerPlayer player) {
      if (player == null) {
         throw new IllegalArgumentException("缺少 CSV 导出玩家");
      } else if (!NetworkRateLimiter.allow(player.getUUID(), "whitelist_csv_export", 1)) {
         throw new IllegalArgumentException("导出过于频繁，请稍后再试");
      } else {
         return sendTo(player, BiliWhitelistManager.exportCsv(player.level().getServer()));
      }
   }

   public static int sendTo(ServerPlayer player, String csv) {
      if (player == null) {
         throw new IllegalArgumentException("缺少 CSV 导出玩家");
      } else {
         byte[] bytes = encodeCsv(csv);
         WhitelistCsvExportPacket.TransferInfo transfer = createTransfer(bytes.length);

         for (int index = 0; index < transfer.chunkCount(); index++) {
            PacketDistributor.sendToPlayer(player, packetAt(transfer, bytes, index), new CustomPacketPayload[0]);
         }

         return transfer.chunkCount();
      }
   }

   private static byte[] encodeCsv(String csv) {
      byte[] bytes = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 67108864) {
         throw new IllegalArgumentException("白名单 CSV 超过 64 MiB 安全传输上限");
      } else {
         return bytes;
      }
   }

   private static WhitelistCsvExportPacket.TransferInfo createTransfer(int totalBytes) {
      String transferId = UUID.randomUUID().toString();
      String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
      String fileName = "net_music_can_play_bili_link_whitelist_" + timestamp + "-" + transferId.substring(0, 8) + ".csv";
      int chunkCount = Math.max(1, (totalBytes + 65536 - 1) / 65536);
      return new WhitelistCsvExportPacket.TransferInfo(transferId, fileName, chunkCount, totalBytes);
   }

   private static WhitelistCsvExportPacket packetAt(WhitelistCsvExportPacket.TransferInfo transfer, byte[] bytes, int index) {
      int start = index * 65536;
      int end = Math.min(bytes.length, start + 65536);
      return new WhitelistCsvExportPacket(
         transfer.transferId(), transfer.fileName(), index, transfer.chunkCount(), transfer.totalBytes(), Arrays.copyOfRange(bytes, start, end)
      );
   }

   private static WhitelistCsvExportPacket singlePacket(String fileName, String csv) {
      byte[] bytes = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 65536) {
         throw new IllegalArgumentException("单包 CSV 构造器只支持不超过 64 KiB 的测试数据");
      } else {
         return new WhitelistCsvExportPacket(UUID.randomUUID().toString(), fileName == null ? "" : fileName, 0, 1, bytes.length, bytes);
      }
   }

   private static int readBoundedInt(RegistryFriendlyByteBuf buffer, int maximum, String field) {
      int value = buffer.readVarInt();
      if (value >= 0 && value <= maximum) {
         return value;
      } else {
         throw new DecoderException("Invalid whitelist CSV " + field + ": " + value);
      }
   }

   private static void validate(WhitelistCsvExportPacket packet, boolean encoding) {
      String message = null;
      if (packet == null || packet.transferId() == null || packet.transferId().isBlank() || packet.transferId().length() > 64) {
         message = "invalid transfer id";
      } else if (packet.fileName() == null || packet.fileName().length() > 128) {
         message = "invalid file name";
      } else if (packet.chunkCount() <= 0 || packet.chunkCount() > 1024) {
         message = "invalid chunk count";
      } else if (packet.chunkIndex() < 0 || packet.chunkIndex() >= packet.chunkCount()) {
         message = "invalid chunk index";
      } else if (packet.totalBytes() < 0 || packet.totalBytes() > 67108864) {
         message = "invalid total byte count";
      } else if (packet.chunk() == null || packet.chunk().length > 65536) {
         message = "invalid chunk payload";
      }

      if (message != null) {
         if (encoding) {
            throw new EncoderException("Cannot encode whitelist CSV packet: " + message);
         } else {
            throw new DecoderException("Cannot decode whitelist CSV packet: " + message);
         }
      }
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static void handle(WhitelistCsvExportPacket payload, IPayloadContext context) {
      context.enqueueWork(() -> WhitelistCsvExportClient.save(payload));
   }

   private record TransferInfo(String transferId, String fileName, int chunkCount, int totalBytes) {
   }
}
