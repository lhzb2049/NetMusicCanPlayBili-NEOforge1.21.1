package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.network.WhitelistCsvExportPacket;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class WhitelistCsvExportClient {
   private static final long TRANSFER_TIMEOUT_MILLIS = 120000L;
   private static final int MAX_ACTIVE_TRANSFERS = 4;
   private static final Map<String, WhitelistCsvExportClient.Transfer> TRANSFERS = new LinkedHashMap<>();

   private WhitelistCsvExportClient() {
   }

   public static synchronized void save(WhitelistCsvExportPacket payload) {
      Minecraft minecraft = Minecraft.getInstance();
      long now = System.currentTimeMillis();
      pruneExpired(now);

      try {
         validate(payload);
         WhitelistCsvExportClient.Transfer transfer = TRANSFERS.get(payload.transferId());
         if (transfer == null) {
            if (payload.chunkIndex() != 0) {
               throw new IOException("传输必须从第一个分块开始");
            }

            evictOldestIfFull();
            transfer = WhitelistCsvExportClient.Transfer.create(minecraft, payload, now);
            TRANSFERS.put(payload.transferId(), transfer);
            scheduleExpiry(payload.transferId(), transfer.temporary, 120000L);
         } else if (!transfer.matches(payload)) {
            discard(payload.transferId(), transfer);
            throw new IOException("分块元数据不一致");
         }

         transfer.accept(payload, now);
         if (!transfer.complete()) {
            return;
         }

         Path path = transfer.finish();
         TRANSFERS.remove(payload.transferId());
         notifyPlayer(minecraft, "白名单 CSV 已导出到本地：" + path.toAbsolutePath());
      } catch (Exception var6) {
         if (payload != null && payload.transferId() != null) {
            WhitelistCsvExportClient.Transfer transferx = TRANSFERS.remove(payload.transferId());
            deleteTemporary(transferx);
         }

         notifyPlayer(minecraft, "白名单 CSV 导出失败：" + var6.getMessage());
      }
   }

   private static void validate(WhitelistCsvExportPacket payload) throws IOException {
      if (payload != null && payload.transferId() != null && !payload.transferId().isBlank()) {
         if (payload.chunkCount() <= 0 || payload.chunkCount() > 1024 || payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()) {
            throw new IOException("分块序号无效");
         } else if (payload.totalBytes() < 0 || payload.totalBytes() > 67108864 || payload.chunk() == null || payload.chunk().length > 65536) {
            throw new IOException("分块大小无效");
         }
      } else {
         throw new IOException("缺少传输标识");
      }
   }

   private static void pruneExpired(long now) {
      Iterator<Entry<String, WhitelistCsvExportClient.Transfer>> iterator = TRANSFERS.entrySet().iterator();

      while (iterator.hasNext()) {
         WhitelistCsvExportClient.Transfer transfer = iterator.next().getValue();
         if (now - transfer.updatedAt > 120000L) {
            iterator.remove();
            deleteTemporary(transfer);
         }
      }
   }

   private static void evictOldestIfFull() {
      if (TRANSFERS.size() >= 4) {
         Iterator<Entry<String, WhitelistCsvExportClient.Transfer>> iterator = TRANSFERS.entrySet().iterator();
         if (iterator.hasNext()) {
            WhitelistCsvExportClient.Transfer transfer = iterator.next().getValue();
            iterator.remove();
            deleteTemporary(transfer);
         }
      }
   }

   private static void discard(String transferId, WhitelistCsvExportClient.Transfer transfer) {
      TRANSFERS.remove(transferId);
      deleteTemporary(transfer);
   }

   private static void deleteTemporary(WhitelistCsvExportClient.Transfer transfer) {
      if (transfer != null) {
         transfer.closeQuietly();

         try {
            Files.deleteIfExists(transfer.temporary);
         } catch (IOException var2) {
         }
      }
   }

   private static void scheduleExpiry(String transferId, Path temporary, long delayMillis) {
      CompletableFuture.delayedExecutor(Math.max(1L, delayMillis), TimeUnit.MILLISECONDS).execute(() -> expireTransfer(transferId, temporary));
   }

   private static synchronized void expireTransfer(String transferId, Path temporary) {
      WhitelistCsvExportClient.Transfer transfer = TRANSFERS.get(transferId);
      if (transfer != null && transfer.temporary.equals(temporary)) {
         long remaining = 120000L - (System.currentTimeMillis() - transfer.updatedAt);
         if (remaining > 0L) {
            scheduleExpiry(transferId, temporary, remaining);
         } else {
            TRANSFERS.remove(transferId);
            deleteTemporary(transfer);
         }
      }
   }

   private static void cleanupAbandoned(Path directory, long now) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, ".ncpb-whitelist-*.part")) {
         for (Path file : files) {
            try {
               long age = now - Files.getLastModifiedTime(file).toMillis();
               if (age >= 120000L) {
                  Files.deleteIfExists(file);
               }
            } catch (IOException var9) {
            }
         }
      } catch (IOException var11) {
      }
   }

   private static void notifyPlayer(Minecraft minecraft, String message) {
      if (minecraft.player != null) {
         minecraft.player.sendSystemMessage(Component.literal(message));
      }
   }

   private static String safeFileName(String value) {
      String name = value != null && !value.isBlank() ? value : "net_music_can_play_bili_link_whitelist.csv";
      String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
      return !safe.isBlank() && !".".equals(safe) && !"..".equals(safe) ? safe : "net_music_can_play_bili_link_whitelist.csv";
   }

   private static String safeTransferId(String value) {
      String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
      return safe.isBlank() ? "unknown" : safe;
   }

   private static final class Transfer {
      private final String fileName;
      private final int chunkCount;
      private final int totalBytes;
      private final Path target;
      private final Path temporary;
      private final OutputStream output;
      private int nextChunkIndex;
      private int receivedBytes;
      private long updatedAt;
      private boolean closed;

      private Transfer(String fileName, int chunkCount, int totalBytes, Path target, Path temporary, OutputStream output, long now) {
         this.fileName = fileName;
         this.chunkCount = chunkCount;
         this.totalBytes = totalBytes;
         this.target = target;
         this.temporary = temporary;
         this.output = output;
         this.updatedAt = now;
      }

      private static WhitelistCsvExportClient.Transfer create(Minecraft minecraft, WhitelistCsvExportPacket first, long now) throws IOException {
         Path dir = minecraft.gameDirectory.toPath().resolve("exports").resolve("net_music_can_play_bili");
         Files.createDirectories(dir);
         WhitelistCsvExportClient.cleanupAbandoned(dir, now);
         String fileName = WhitelistCsvExportClient.safeFileName(first.fileName());
         Path target = dir.resolve(fileName);
         Path temporary = dir.resolve(".ncpb-whitelist-" + WhitelistCsvExportClient.safeTransferId(first.transferId()) + ".part");
         Files.deleteIfExists(temporary);
         OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
         return new WhitelistCsvExportClient.Transfer(first.fileName(), first.chunkCount(), first.totalBytes(), target, temporary, output, now);
      }

      private boolean matches(WhitelistCsvExportPacket packet) {
         return this.chunkCount == packet.chunkCount() && this.totalBytes == packet.totalBytes() && Objects.equals(this.fileName, packet.fileName());
      }

      private void accept(WhitelistCsvExportPacket packet, long now) throws IOException {
         if (packet.chunkIndex() != this.nextChunkIndex) {
            throw new IOException("CSV 分块乱序或重复");
         } else {
            int nextBytes = this.receivedBytes + packet.chunk().length;
            if (nextBytes > this.totalBytes) {
               throw new IOException("收到的数据超过声明长度");
            } else {
               this.output.write(packet.chunk());
               this.nextChunkIndex++;
               this.receivedBytes = nextBytes;
               this.updatedAt = now;
            }
         }
      }

      private boolean complete() {
         return this.nextChunkIndex == this.chunkCount;
      }

      private Path finish() throws IOException {
         if (this.complete() && this.receivedBytes == this.totalBytes) {
            this.closeOutput();

            try {
               Files.move(this.temporary, this.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var2) {
               Files.move(this.temporary, this.target, StandardCopyOption.REPLACE_EXISTING);
            }

            return this.target;
         } else {
            throw new IOException("分块不完整或总长度不匹配");
         }
      }

      private void closeOutput() throws IOException {
         if (!this.closed) {
            this.output.close();
            this.closed = true;
         }
      }

      private void closeQuietly() {
         try {
            this.closeOutput();
         } catch (IOException var2) {
         }
      }
   }
}
