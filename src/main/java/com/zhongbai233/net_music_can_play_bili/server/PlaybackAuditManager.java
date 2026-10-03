package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.authlib.GameProfile;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.HoverEvent.Action;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

public final class PlaybackAuditManager {
   private static final int STALE_AFTER_TICKS = 80;
   private static final int PARTICLE_INTERVAL_TICKS = 8;
   private static final long REPORT_NOTIFY_COOLDOWN_TICKS = 900L;
   private static final int REPORT_OP_REMINDER_LIMIT = 5;
   private static final PlaybackSourceRegistry<PlaybackAuditManager.ActiveSource> SOURCES = new PlaybackSourceRegistry<>();
   private static final PlaybackReportTracker<UUID> REPORTS = new PlaybackReportTracker<>(900L, 5);

   private PlaybackAuditManager() {
   }

   public static void recordModernTurntable(
      ServerLevel level, BlockPos pos, String songName, String rawUrl, int durationSeconds, long elapsedMillis, UUID ownerId
   ) {
      record(
         "turntable:" + level.dimension() + ":" + pos.asLong(),
         PlaybackAuditManager.SourceKind.MODERN_TURNTABLE,
         level,
         pos,
         pos.getX() + 0.5,
         pos.getY() + 1.15,
         pos.getZ() + 0.5,
         songName,
         rawUrl,
         durationSeconds,
         elapsedMillis,
         ownerId
      );
   }

   public static void recordMp4(
      ServerLevel level, UUID deviceId, BlockPos sourcePos, String songName, String rawUrl, int durationSeconds, long elapsedMillis, UUID ownerId
   ) {
      if (sourcePos != null) {
         recordMp4(
            level,
            deviceId,
            sourcePos,
            sourcePos.getX() + 0.5,
            sourcePos.getY() + 1.15,
            sourcePos.getZ() + 0.5,
            songName,
            rawUrl,
            durationSeconds,
            elapsedMillis,
            ownerId
         );
      }
   }

   public static void recordMp4(
      ServerLevel level,
      UUID deviceId,
      BlockPos sourcePos,
      double particleX,
      double particleY,
      double particleZ,
      String songName,
      String rawUrl,
      int durationSeconds,
      long elapsedMillis,
      UUID ownerId
   ) {
      if (deviceId != null) {
         record(
            "mp4:" + deviceId,
            PlaybackAuditManager.SourceKind.MP4,
            level,
            sourcePos,
            particleX,
            particleY,
            particleZ,
            songName,
            rawUrl,
            durationSeconds,
            elapsedMillis,
            ownerId
         );
      }
   }

   private static void record(
      String key,
      PlaybackAuditManager.SourceKind kind,
      ServerLevel level,
      BlockPos sourcePos,
      double particleX,
      double particleY,
      double particleZ,
      String songName,
      String rawUrl,
      int durationSeconds,
      long elapsedMillis,
      UUID ownerId
   ) {
      if (level != null && sourcePos != null) {
         long gameTime = MonotonicMediaClock.nowTick();
         SOURCES.put(
            key,
            new PlaybackAuditManager.ActiveSource(
               key,
               kind,
               level.dimension(),
               sourcePos.immutable(),
               safe(songName, "未知歌曲"),
               safe(rawUrl, ""),
               Math.max(0, durationSeconds),
               Math.max(0L, elapsedMillis),
               ownerId,
               gameTime
            )
         );
         spawnSourceNoteParticle(level, particleX, particleY, particleZ, gameTime);
      }
   }

   public static List<PlaybackAuditManager.ActiveSource> snapshot(MinecraftServer server) {
      if (server == null) {
         return List.of();
      } else {
         prune(server);
         List<PlaybackAuditManager.ActiveSource> result = new ArrayList<>(SOURCES.snapshot());
         result.sort(
            Comparator.<PlaybackAuditManager.ActiveSource, String>comparing(source -> source.kind().displayName())
               .thenComparing(source -> source.levelKey().toString())
               .thenComparingInt(source -> source.sourcePos().getX())
               .thenComparingInt(source -> source.sourcePos().getY())
               .thenComparingInt(source -> source.sourcePos().getZ())
         );
         return result;
      }
   }

   public static PlaybackAuditManager.ActiveSource findModernTurntable(MinecraftServer server, ServerLevel level, BlockPos pos) {
      if (server != null && level != null && pos != null) {
         prune(server);
         return SOURCES.get("turntable:" + level.dimension() + ":" + pos.asLong());
      } else {
         return null;
      }
   }

   public static PlaybackAuditManager.ActiveSource findMp4(MinecraftServer server, UUID deviceId) {
      if (server != null && deviceId != null) {
         prune(server);
         return SOURCES.get("mp4:" + deviceId);
      } else {
         return null;
      }
   }

   public static PlaybackAuditManager.ActiveSource findByKey(MinecraftServer server, String key) {
      if (server != null && key != null && !key.isBlank()) {
         prune(server);
         return SOURCES.get(key);
      } else {
         return null;
      }
   }

   public static PlaybackAuditManager.ReportResult notifyOpsOfReport(ServerPlayer reporter, PlaybackAuditManager.ActiveSource source, String reason) {
      if (reporter != null && source != null) {
         MinecraftServer server = reporter.level().getServer();
         if (server == null) {
            return PlaybackAuditManager.ReportResult.EMPTY;
         } else {
            long now = MonotonicMediaClock.nowTick();
            PlaybackReportTracker.Decision<UUID> decision = REPORTS.record(source.key(), reporter.getUUID(), now);
            PlaybackReportTracker.Snapshot<UUID> state = decision.snapshot();
            if (!decision.shouldNotify()) {
               return new PlaybackAuditManager.ReportResult(
                  false, decision.merged(), state.totalReports(), state.uniqueReporterCount(), false, state.reminderLimitReached()
               );
            } else {
               MutableComponent message = reportMessage(server, source, reporter.getDisplayName().copy(), reason, state, true);
               if (decision.reachedLimit() && !state.reminderLimitReached()) {
                  message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
                     .append(Component.literal("该音源举报已达到 OP 提醒上限，后续同源举报将静默合并").withStyle(new ChatFormatting[]{ChatFormatting.RED, ChatFormatting.BOLD}));
               }

               int notified = broadcastToOnlineOps(server, message);
               server.sendSystemMessage(message);
               PlaybackReportTracker.Snapshot<UUID> notifiedState = REPORTS.markNotified(source.key(), now, decision.reachedLimit());
               return new PlaybackAuditManager.ReportResult(
                  notified > 0,
                  decision.merged(),
                  notifiedState.totalReports(),
                  notifiedState.uniqueReporterCount(),
                  decision.reachedLimit(),
                  notifiedState.reminderLimitReached()
               );
            }
         }
      } else {
         return PlaybackAuditManager.ReportResult.EMPTY;
      }
   }

   private static MutableComponent reportMessage(
      MinecraftServer server,
      PlaybackAuditManager.ActiveSource source,
      Component reporterName,
      String reason,
      PlaybackReportTracker.Snapshot<UUID> state,
      boolean includeSuppressed
   ) {
      MutableComponent message = Component.literal("[音源举报] ")
         .withStyle(new ChatFormatting[]{ChatFormatting.RED, ChatFormatting.BOLD})
         .append(reporterName.copy().withStyle(ChatFormatting.YELLOW))
         .append(Component.literal(" 举报 ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal(source.kind().displayName()).withStyle(new ChatFormatting[]{source.kind().color(), ChatFormatting.BOLD}))
         .append(Component.literal("：").withStyle(ChatFormatting.GRAY))
         .append(Component.literal(reason != null && !reason.isBlank() ? reason : "未填写原因").withStyle(ChatFormatting.WHITE))
         .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
         .append(Component.literal("累计 " + state.totalReports() + " 次 / " + state.uniqueReporterCount() + " 人").withStyle(ChatFormatting.GOLD))
         .append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
         .append(source.describe(server));
      int suppressed = state.suppressedReportCount();
      if (includeSuppressed && suppressed > 0) {
         message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("已合并未单独提醒的同源举报：" + suppressed + " 次").withStyle(ChatFormatting.YELLOW));
      }

      if (isLikelyBiliSource(source.rawUrl())) {
         message.append(Component.literal("\n  ").withStyle(ChatFormatting.DARK_GRAY)).append(openBiliComponent(source.rawUrl()));
      }

      return message;
   }

   private static int broadcastToOnlineOps(MinecraftServer server, Component message) {
      int notified = 0;

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (isOpLevelTwoOrHigher(server, player)) {
            player.sendSystemMessage(message);
            notified++;
         }
      }

      return notified;
   }

   public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         MinecraftServer server = player.level().getServer();
         if (server != null && isOpLevelTwoOrHigher(server, player)) {
            prune(server);
            List<PlaybackAuditManager.PendingReport> pendingReports = REPORTS.snapshots()
               .stream()
               .map(entry -> new PlaybackAuditManager.PendingReport(SOURCES.get(entry.sourceKey()), entry.snapshot()))
               .filter(reportx -> reportx.source() != null && reportx.state().totalReports() > 0)
               .sorted(Comparator.comparingLong(reportx -> reportx.state().lastReportGameTime()))
               .toList();
            if (!pendingReports.isEmpty()) {
               player.sendSystemMessage(
                  Component.literal("[音源举报] ")
                     .withStyle(new ChatFormatting[]{ChatFormatting.RED, ChatFormatting.BOLD})
                     .append(Component.literal("当前有 " + pendingReports.size() + " 个活跃举报音源").withStyle(ChatFormatting.GOLD))
                     .append(Component.literal("（上线补发）").withStyle(ChatFormatting.GRAY))
               );

               for (PlaybackAuditManager.PendingReport report : pendingReports) {
                  player.sendSystemMessage(reportMessage(server, report.source(), Component.literal("离线期间玩家"), "上线补发聚合举报", report.state(), false));
               }
            }
         }
      }
   }

   private static boolean isLikelyBiliSource(String rawUrl) {
      String value = rawUrl != null ? rawUrl.toLowerCase(Locale.ROOT) : "";
      return value.contains("bilibili.com") || value.contains("b23.tv") || value.contains("bv");
   }

   private static Component openBiliComponent(String rawUrl) {
      String url = normalizedBiliUrl(rawUrl);
      URI uri = safeUri(url);
      if (uri == null) {
         String source = rawUrl != null ? rawUrl : "";
         return Component.literal("[复制 B站来源]")
            .withStyle(
               style -> style.withColor(ChatFormatting.AQUA)
                  .withUnderlined(true)
                  .withHoverEvent(
                     new HoverEvent(
                        Action.SHOW_TEXT,
                        Component.literal("来源不是完整 URL，请手动复制：\n")
                           .withStyle(ChatFormatting.YELLOW)
                           .append(Component.literal(source).withStyle(ChatFormatting.GRAY))
                     )
                  )
            );
      } else {
         return Component.literal("[打开/复制 B站来源]")
            .withStyle(
               style -> style.withColor(ChatFormatting.AQUA)
                  .withUnderlined(true)
                  .withClickEvent(new ClickEvent(net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, uri.toString()))
                  .withHoverEvent(
                     new HoverEvent(
                        Action.SHOW_TEXT,
                        Component.literal("点击打开来源；如无法打开请复制：\n").withStyle(ChatFormatting.YELLOW).append(Component.literal(url).withStyle(ChatFormatting.GRAY))
                     )
                  )
            );
      }
   }

   private static URI safeUri(String url) {
      if (url != null && !url.isBlank()) {
         try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) ? null : uri;
         } catch (IllegalArgumentException var3) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static String normalizedBiliUrl(String rawUrl) {
      String value = rawUrl != null ? rawUrl.trim() : "";
      if (value.isBlank()) {
         return "";
      } else if (!value.startsWith("http://") && !value.startsWith("https://")) {
         Matcher matcher = Pattern.compile("(BV[0-9A-Za-z]+)").matcher(value);
         if (!matcher.find()) {
            return "";
         } else {
            String bvid = matcher.group(1);
            String page = "";
            Matcher pageMatcher = Pattern.compile("(?:^|[|&?])p=(\\d+)").matcher(value);
            if (pageMatcher.find()) {
               page = "?p=" + pageMatcher.group(1);
            }

            return "https://www.bilibili.com/video/" + bvid + page;
         }
      } else {
         try {
            URI uri = URI.create(value);
            return uri.toString();
         } catch (IllegalArgumentException var6) {
            return "";
         }
      }
   }

   private static boolean isOpLevelTwoOrHigher(MinecraftServer server, ServerPlayer player) {
      if (server != null && player != null) {
         try {
            GameProfile profile = player.getGameProfile();
            if (profile == null) {
               return false;
            } else if (server.isSingleplayerOwner(profile)) {
               return true;
            } else {
               int level = server.getProfilePermissions(profile);
               return level >= 2;
            }
         } catch (Exception var4) {
            return false;
         }
      } else {
         return false;
      }
   }

   public static void onServerTick(Post event) {
      if (event.getServer() != null && event.getServer().getTickCount() % 80 == 0) {
         prune(event.getServer());
      }
   }

   private static void prune(MinecraftServer server) {
      SOURCES.removeIf(source -> {
         ServerLevel level = server.getLevel(source.levelKey());
         return level == null || MonotonicMediaClock.nowTick() - source.lastSeenGameTime() > 80L;
      });
      REPORTS.retainSources(SOURCES.keys());
   }

   private static void spawnSourceNoteParticle(ServerLevel level, double x, double y, double z, long gameTime) {
      if (gameTime % 8L == 0L) {
         level.sendParticles(ParticleTypes.NOTE, x, y, z, 2, 0.35, 0.15, 0.35, 0.0);
      }
   }

   private static String safe(String value, String fallback) {
      return value != null && !value.isBlank() ? value : fallback;
   }

   private static String formatMillis(long millis) {
      long safeMillis = Math.max(0L, millis);
      long totalSeconds = safeMillis / 1000L;
      return totalSeconds / 60L + ":" + String.format("%02d", totalSeconds % 60L);
   }

   public record ActiveSource(
      String key,
      PlaybackAuditManager.SourceKind kind,
      ResourceKey<Level> levelKey,
      BlockPos sourcePos,
      String songName,
      String rawUrl,
      int durationSeconds,
      long elapsedMillis,
      UUID ownerId,
      long lastSeenGameTime
   ) {
      public Component describe(MinecraftServer server) {
         String ownerName = "未知玩家";
         if (this.ownerId != null) {
            ServerPlayer player = server.getPlayerList().getPlayer(this.ownerId);
            ownerName = player != null ? player.getDisplayName().getString() : this.ownerId.toString();
         }

         MutableComponent line = Component.literal("• ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(this.kind.shortName()).withStyle(new ChatFormatting[]{this.kind.color(), ChatFormatting.BOLD}))
            .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(trimmed(this.songName, 32).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(
               Component.literal(PlaybackAuditManager.formatMillis(this.elapsedMillis) + "/" + PlaybackAuditManager.formatMillis(this.durationSeconds * 1000L))
                  .withStyle(ChatFormatting.AQUA)
            )
            .append(Component.literal("  @").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(trimPlain(ownerName, 18)).withStyle(ChatFormatting.GRAY))
            .append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
            .append(this.teleportComponent());
         if (!this.rawUrl.isBlank()) {
            line = line.withStyle(
               style -> style.withHoverEvent(
                  new HoverEvent(
                     Action.SHOW_TEXT,
                     Component.literal("来源: ").withStyle(ChatFormatting.GRAY).append(Component.literal(this.rawUrl).withStyle(ChatFormatting.YELLOW))
                  )
               )
            );
         }

         return line;
      }

      private Component teleportComponent() {
         String dimension = this.levelKey.location().toString();
         String posText = this.sourcePos.getX() + " " + this.sourcePos.getY() + " " + this.sourcePos.getZ();
         String command = "/execute in "
            + dimension
            + " run tp @s "
            + (this.sourcePos.getX() + 0.5)
            + " "
            + (this.sourcePos.getY() + 1.0)
            + " "
            + (this.sourcePos.getZ() + 0.5);
         return Component.literal("[" + posText + "]")
            .withStyle(
               style -> style.withColor(ChatFormatting.GOLD)
                  .withUnderlined(true)
                  .withClickEvent(new ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, command))
                  .withHoverEvent(
                     new HoverEvent(
                        Action.SHOW_TEXT,
                        Component.literal("点击传送到音源\n")
                           .withStyle(ChatFormatting.YELLOW)
                           .append(Component.literal(dimension + " " + posText).withStyle(ChatFormatting.GRAY))
                     )
                  )
            );
      }

      private static MutableComponent trimmed(String value, int maxLength) {
         return Component.literal(trimPlain(value, maxLength));
      }

      private static String trimPlain(String value, int maxLength) {
         String safeValue = PlaybackAuditManager.safe(value, "?");
         return safeValue.length() <= maxLength ? safeValue : safeValue.substring(0, Math.max(1, maxLength - 1)) + "…";
      }
   }

   private record PendingReport(PlaybackAuditManager.ActiveSource source, PlaybackReportTracker.Snapshot<UUID> state) {
   }

   public record ReportResult(
      boolean notifiedOps, boolean merged, int totalReports, int uniqueReporters, boolean opReminderLimitReachedNow, boolean opReminderLimitReached
   ) {
      private static final PlaybackAuditManager.ReportResult EMPTY = new PlaybackAuditManager.ReportResult(false, false, 0, 0, false, false);
   }

   public static enum SourceKind {
      MODERN_TURNTABLE("现代化唱片机", "唱片机", ChatFormatting.LIGHT_PURPLE),
      MP4("MP4", "MP4", ChatFormatting.GREEN);

      private final String displayName;
      private final String shortName;
      private final ChatFormatting color;

      private SourceKind(String displayName, String shortName, ChatFormatting color) {
         this.displayName = displayName;
         this.shortName = shortName;
         this.color = color;
      }

      public String displayName() {
         return this.displayName;
      }

      public String shortName() {
         return this.shortName;
      }

      public ChatFormatting color() {
         return this.color;
      }
   }
}
