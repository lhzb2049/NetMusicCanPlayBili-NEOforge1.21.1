package com.zhongbai233.net_music_can_play_bili.menu;

import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public class MediaToolReportMenu extends AbstractContainerMenu {
   public static final int MAX_SOURCES = 12;
   private final List<MediaToolReportMenu.ReportSourceInfo> sources;

   public MediaToolReportMenu(int containerId, Inventory inventory) {
      this(containerId, inventory, List.of());
   }

   public MediaToolReportMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
      this(containerId, inventory, readSources(buffer));
   }

   public MediaToolReportMenu(int containerId, Inventory inventory, List<MediaToolReportMenu.ReportSourceInfo> sources) {
      super((MenuType)ModMenus.MEDIA_TOOL_REPORT.get(), containerId);
      this.sources = List.copyOf(sources != null ? sources : List.of());
   }

   public List<MediaToolReportMenu.ReportSourceInfo> sources() {
      return this.sources;
   }

   public boolean containsSourceKey(String key) {
      return key != null && !key.isBlank() ? this.sources.stream().anyMatch(source -> key.equals(source.key())) : false;
   }

   public boolean stillValid(Player player) {
      return true;
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   public static MediaToolReportMenu.ReportSourceInfo fromActiveSource(MinecraftServer server, ServerPlayer viewer, PlaybackAuditManager.ActiveSource source) {
      String ownerName = Component.translatable("gui.net_music_can_play_bili.media_tool_report.unknown_owner").getString();
      if (server != null && source.ownerId() != null) {
         ServerPlayer owner = server.getPlayerList().getPlayer(source.ownerId());
         ownerName = owner != null ? owner.getDisplayName().getString() : source.ownerId().toString();
      }

      double distance = viewer != null && viewer.level().dimension().equals(source.levelKey())
         ? Math.sqrt(source.sourcePos().distToCenterSqr(viewer.position()))
         : -1.0;
      return new MediaToolReportMenu.ReportSourceInfo(
         source.key(),
         source.kind().shortName(),
         source.kind().displayName(),
         source.levelKey().location().toString(),
         source.sourcePos(),
         source.songName(),
         source.rawUrl(),
         source.elapsedMillis(),
         source.durationSeconds(),
         ownerName,
         distance
      );
   }

   public static void writeClientData(RegistryFriendlyByteBuf buffer, List<MediaToolReportMenu.ReportSourceInfo> sources) {
      List<MediaToolReportMenu.ReportSourceInfo> safeSources = sources != null ? sources : List.of();
      int count = Math.min(12, safeSources.size());
      buffer.writeVarInt(count);

      for (int i = 0; i < count; i++) {
         safeSources.get(i).write(buffer);
      }
   }

   private static List<MediaToolReportMenu.ReportSourceInfo> readSources(RegistryFriendlyByteBuf buffer) {
      if (buffer != null && buffer.isReadable()) {
         int count = Math.min(12, Math.max(0, buffer.readVarInt()));
         List<MediaToolReportMenu.ReportSourceInfo> result = new ArrayList<>(count);

         for (int i = 0; i < count; i++) {
            result.add(MediaToolReportMenu.ReportSourceInfo.read(buffer));
         }

         return result;
      } else {
         return List.of();
      }
   }

   public record ReportSourceInfo(
      String key,
      String kindShortName,
      String kindDisplayName,
      String dimension,
      BlockPos pos,
      String songName,
      String rawUrl,
      long elapsedMillis,
      int durationSeconds,
      String ownerName,
      double distance
   ) {
      private static final int MAX_TEXT_LENGTH = 512;

      private void write(RegistryFriendlyByteBuf buffer) {
         buffer.writeUtf(safe(this.key), 512);
         buffer.writeUtf(safe(this.kindShortName), 512);
         buffer.writeUtf(safe(this.kindDisplayName), 512);
         buffer.writeUtf(safe(this.dimension), 512);
         buffer.writeBlockPos(this.pos != null ? this.pos : BlockPos.ZERO);
         buffer.writeUtf(safe(this.songName), 512);
         buffer.writeUtf(safe(this.rawUrl), 512);
         buffer.writeVarLong(Math.max(0L, this.elapsedMillis));
         buffer.writeVarInt(Math.max(0, this.durationSeconds));
         buffer.writeUtf(safe(this.ownerName), 512);
         buffer.writeDouble(this.distance);
      }

      private static MediaToolReportMenu.ReportSourceInfo read(RegistryFriendlyByteBuf buffer) {
         return new MediaToolReportMenu.ReportSourceInfo(
            buffer.readUtf(512),
            buffer.readUtf(512),
            buffer.readUtf(512),
            buffer.readUtf(512),
            buffer.readBlockPos(),
            buffer.readUtf(512),
            buffer.readUtf(512),
            buffer.readVarLong(),
            buffer.readVarInt(),
            buffer.readUtf(512),
            buffer.readDouble()
         );
      }

      public String shortSongName() {
         return trim(this.songName, 28);
      }

      public String shortOwnerName() {
         return trim(this.ownerName, 18);
      }

      public String positionText() {
         return this.pos.getX() + " " + this.pos.getY() + " " + this.pos.getZ();
      }

      public String progressText() {
         return formatMillis(this.elapsedMillis) + "/" + formatMillis(this.durationSeconds * 1000L);
      }

      public String distanceText() {
         return this.distance >= 0.0 ? String.format(Locale.ROOT, "%.1fm", this.distance) : "?m";
      }

      private static String safe(String value) {
         return value != null ? value : "";
      }

      private static String trim(String value, int maxLength) {
         String safeValue = safe(value).isBlank() ? "?" : value;
         return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, Math.max(1, maxLength - 1)) + "…";
      }

      private static String formatMillis(long millis) {
         long totalSeconds = Math.max(0L, millis) / 1000L;
         return totalSeconds / 60L + ":" + String.format(Locale.ROOT, "%02d", totalSeconds % 60L);
      }
   }
}
