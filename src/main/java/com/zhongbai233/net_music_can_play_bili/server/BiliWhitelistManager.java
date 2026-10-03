package com.zhongbai233.net_music_can_play_bili.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.bili.BiliApiClient;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveRoomInput;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ClickEvent.Action;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

public final class BiliWhitelistManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private static final String DATA_FILE = "net_music_can_play_bili_link_whitelist.json";
   static final int MAX_AUDIT_TEXT_LENGTH = 256;
   private static final int MAX_REVIEW_RESOURCE_LENGTH = 512;
   private static final String LEGACY_REMOVAL_NOTE = "系统兼容调用";
   private static Path loadedPath;
   private static BiliWhitelistManager.WhitelistData data = new BiliWhitelistManager.WhitelistData();
   private static Exception loadFailure;

   private BiliWhitelistManager() {
   }

   public static boolean enabled() {
      return Config.enableLinkWhitelist;
   }

   public static synchronized Optional<String> canonicalId(String raw) {
      return canonicalResource(raw).map(resource -> resource.key());
   }

   public static synchronized Optional<BiliWhitelistManager.CanonicalResource> canonicalResource(String raw) {
      if (raw != null && !raw.isBlank()) {
         String trimmed = raw.trim();
         Optional<BiliWhitelistManager.CanonicalResource> canonicalId = canonicalPrefixedResource(trimmed);
         if (canonicalId.isPresent()) {
            return canonicalId;
         } else {
            String liveRoomId = BiliLiveRoomInput.parseExplicitRoomId(trimmed);
            if (!liveRoomId.isEmpty()) {
               return Optional.of(new BiliWhitelistManager.CanonicalResource("live", liveRoomId));
            } else {
               BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(raw);
               if (selection != null) {
                  return Optional.of(new BiliWhitelistManager.CanonicalResource("bili", normalizeVideoSelection(selection)));
               } else {
                  String normalizedUrl = normalizeUrl(trimmed);
                  return normalizedUrl != null && !normalizedUrl.isBlank()
                     ? Optional.of(new BiliWhitelistManager.CanonicalResource("url", normalizedUrl))
                     : Optional.empty();
               }
            }
         }
      } else {
         return Optional.empty();
      }
   }

   private static Optional<BiliWhitelistManager.CanonicalResource> canonicalPrefixedResource(String raw) {
      int split = raw.indexOf(58);
      if (split <= 0) {
         return Optional.empty();
      } else {
         String type = raw.substring(0, split).toLowerCase(Locale.ROOT);
         String value = raw.substring(split + 1).trim();
         if (value.isBlank()) {
            return Optional.empty();
         } else if ("bili".equals(type)) {
            BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenientWithShortLink(value);
            return selection != null ? Optional.of(new BiliWhitelistManager.CanonicalResource("bili", normalizeVideoSelection(selection))) : Optional.empty();
         } else if (!"url".equals(type)) {
            return Optional.empty();
         } else {
            String normalizedUrl = normalizeUrl(value);
            return normalizedUrl != null && !normalizedUrl.isBlank()
               ? Optional.of(new BiliWhitelistManager.CanonicalResource("url", normalizedUrl))
               : Optional.empty();
         }
      }
   }

   public static synchronized boolean isAllowed(MinecraftServer server, String raw) {
      if (!enabled()) {
         return true;
      } else {
         Optional<BiliWhitelistManager.CanonicalResource> canonical = canonicalResource(raw);
         return canonical.isPresent() && isAllowedCanonical(server, canonical.get().key());
      }
   }

   public static synchronized boolean isAllowedCanonical(MinecraftServer server, String canonicalId) {
      if (!enabled()) {
         return true;
      } else {
         ensureLoaded(server);
         String key = storageKey(canonicalId);
         return data.entries.containsKey(key);
      }
   }

   public static synchronized BiliWhitelistManager.AddResult add(MinecraftServer server, String raw, ServerPlayer player) throws IOException {
      ensureLoaded(server);
      Optional<BiliWhitelistManager.CanonicalResource> canonical = canonicalResource(raw);
      if (canonical.isEmpty()) {
         return BiliWhitelistManager.AddResult.invalid();
      } else {
         String canonicalId = canonical.get().key();
         if (canonicalId.length() > 512) {
            return BiliWhitelistManager.AddResult.invalid();
         } else {
            String key = storageKey(canonicalId);
            BiliWhitelistManager.Entry previous = data.entries.get(key);
            if (previous != null) {
               return BiliWhitelistManager.AddResult.duplicate(previous);
            } else {
               BiliWhitelistManager.Entry entry = new BiliWhitelistManager.Entry();
               entry.id = canonicalId;
               entry.type = canonical.get().type();
               entry.originalInput = raw == null ? "" : raw.trim();
               entry.addedByName = player != null ? player.getDisplayName().getString() : "Console";
               UUID uuid = player != null ? player.getUUID() : null;
               entry.addedByUuid = uuid != null ? uuid.toString() : "";
               entry.addedAt = Instant.now().toString();
               BiliWhitelistManager.WhitelistData next = data.copy();
               next.entries.put(key, entry);
               save(server, next);
               data = next;
               return BiliWhitelistManager.AddResult.added(entry);
            }
         }
      }
   }

   public static synchronized BiliWhitelistManager.RemoveResult remove(MinecraftServer server, String raw) throws IOException {
      return remove(server, raw, null, "系统兼容调用");
   }

   public static synchronized BiliWhitelistManager.RemoveResult remove(MinecraftServer server, String raw, ServerPlayer player, String note) throws IOException {
      return remove(server, raw, player, note, null);
   }

   public static synchronized BiliWhitelistManager.RemoveResult remove(
      MinecraftServer server, String raw, ServerPlayer player, String note, String expectedAddedAt
   ) throws IOException {
      ensureLoaded(server);
      Optional<BiliWhitelistManager.CanonicalResource> canonical = canonicalResource(raw);
      if (canonical.isEmpty()) {
         return BiliWhitelistManager.RemoveResult.invalid();
      } else {
         String normalizedNote = normalizeAuditText(note);
         if (normalizedNote.isEmpty()) {
            return BiliWhitelistManager.RemoveResult.noteRequired(canonical.get().key());
         } else {
            String key = storageKey(canonical.get().key());
            BiliWhitelistManager.Entry removed = data.entries.get(key);
            if (removed == null) {
               return BiliWhitelistManager.RemoveResult.missing(canonical.get().key());
            } else if (expectedAddedAt != null && !safe(removed.addedAt).equals(expectedAddedAt)) {
               return BiliWhitelistManager.RemoveResult.stale(canonical.get().key());
            } else {
               BiliWhitelistManager.RemovalRecord record = BiliWhitelistManager.RemovalRecord.create(removed, player, normalizedNote);
               BiliWhitelistManager.WhitelistData next = data.copy();
               next.entries.remove(key);
               next.removalRecords.add(record);
               save(server, next);
               data = next;
               return BiliWhitelistManager.RemoveResult.removed(removed, record);
            }
         }
      }
   }

   public static synchronized BiliWhitelistManager.CommentResult addComment(MinecraftServer server, String raw, ServerPlayer player, String text) throws IOException {
      return addComment(server, raw, player, text, null);
   }

   public static synchronized BiliWhitelistManager.CommentResult addComment(
      MinecraftServer server, String raw, ServerPlayer player, String text, String expectedAddedAt
   ) throws IOException {
      ensureLoaded(server);
      Optional<BiliWhitelistManager.CanonicalResource> canonical = canonicalResource(raw);
      if (canonical.isEmpty()) {
         return BiliWhitelistManager.CommentResult.invalid();
      } else {
         String normalizedText = normalizeAuditText(text);
         if (normalizedText.isEmpty()) {
            return BiliWhitelistManager.CommentResult.commentRequired(canonical.get().key());
         } else {
            String key = storageKey(canonical.get().key());
            BiliWhitelistManager.Entry existing = data.entries.get(key);
            if (existing == null) {
               return BiliWhitelistManager.CommentResult.missing(canonical.get().key());
            } else if (expectedAddedAt != null && !safe(existing.addedAt).equals(expectedAddedAt)) {
               return BiliWhitelistManager.CommentResult.stale(canonical.get().key());
            } else {
               BiliWhitelistManager.ReviewComment comment = BiliWhitelistManager.ReviewComment.create(player, normalizedText);
               BiliWhitelistManager.WhitelistData next = data.copy();
               BiliWhitelistManager.Entry updated = next.entries.get(key);
               updated.comments.add(comment);
               save(server, next);
               data = next;
               return BiliWhitelistManager.CommentResult.added(updated, comment);
            }
         }
      }
   }

   public static synchronized List<BiliWhitelistManager.Entry> entries(MinecraftServer server) {
      return entries(server, Integer.MAX_VALUE);
   }

   public static synchronized List<BiliWhitelistManager.Entry> entries(MinecraftServer server, int limit) {
      ensureLoaded(server);
      return data.entries
         .values()
         .stream()
         .sorted(Comparator.comparing(entry -> entry.addedAt == null ? "" : entry.addedAt))
         .limit(Math.max(0, limit))
         .map(entry -> entry.copy())
         .toList();
   }

   public static synchronized List<BiliWhitelistManager.Entry> entryPage(MinecraftServer server, int offset, int limit) {
      ensureLoaded(server);
      int safeOffset = Math.max(0, offset);
      int safeLimit = Math.max(0, limit);
      Comparator<BiliWhitelistManager.Entry> newestFirst = Comparator.<BiliWhitelistManager.Entry, String>comparing(entry -> safe(entry.addedAt))
         .thenComparing(entry -> safe(entry.id))
         .reversed();
      return data.entries.values().stream().sorted(newestFirst).skip(safeOffset).limit(safeLimit).map(entry -> entry.copy()).toList();
   }

   public static synchronized int entryCount(MinecraftServer server) {
      ensureLoaded(server);
      return data.entries.size();
   }

   public static synchronized List<BiliWhitelistManager.RemovalRecord> removalRecords(MinecraftServer server) {
      return removalRecords(server, Integer.MAX_VALUE);
   }

   public static synchronized List<BiliWhitelistManager.RemovalRecord> removalRecords(MinecraftServer server, int limit) {
      ensureLoaded(server);
      int count = Math.min(data.removalRecords.size(), Math.max(0, limit));
      List<BiliWhitelistManager.RemovalRecord> records = new ArrayList<>(count);

      for (int index = data.removalRecords.size() - 1; index >= data.removalRecords.size() - count; index--) {
         records.add(data.removalRecords.get(index).copy());
      }

      return List.copyOf(records);
   }

   public static synchronized List<BiliWhitelistManager.RemovalRecord> removalRecordPage(MinecraftServer server, int offset, int limit) {
      ensureLoaded(server);
      int size = data.removalRecords.size();
      int safeOffset = Math.min(size, Math.max(0, offset));
      int safeLimit = Math.max(0, limit);
      int end = Math.min(size, safeOffset + safeLimit);
      List<BiliWhitelistManager.RemovalRecord> records = new ArrayList<>(Math.max(0, end - safeOffset));

      for (int position = safeOffset; position < end; position++) {
         records.add(data.removalRecords.get(size - 1 - position).copy());
      }

      return List.copyOf(records);
   }

   public static synchronized int removalRecordCount(MinecraftServer server) {
      ensureLoaded(server);
      return data.removalRecords.size();
   }

   public static synchronized BiliWhitelistManager.ReviewSnapshot reviewSnapshot(
      MinecraftServer server, int requestedEntryOffset, int entryPageSize, int requestedRemovalOffset, int removalPageSize
   ) {
      ensureLoaded(server);
      int totalEntries = data.entries.size();
      int totalRemovals = data.removalRecords.size();
      int entryOffset = clampPageOffset(requestedEntryOffset, totalEntries, entryPageSize);
      int removalOffset = clampPageOffset(requestedRemovalOffset, totalRemovals, removalPageSize);
      return new BiliWhitelistManager.ReviewSnapshot(
         entryOffset,
         totalEntries,
         entryPage(server, entryOffset, entryPageSize),
         removalOffset,
         totalRemovals,
         removalRecordPage(server, removalOffset, removalPageSize)
      );
   }

   private static int clampPageOffset(int requestedOffset, int total, int pageSize) {
      if (total > 0 && pageSize > 0) {
         int maximum = (total - 1) / pageSize * pageSize;
         return Math.min(maximum, Math.max(0, requestedOffset));
      } else {
         return 0;
      }
   }

   public static synchronized String exportCsv(MinecraftServer server) {
      ensureLoaded(server);
      return exportCsv(data);
   }

   static String exportCsv(BiliWhitelistManager.WhitelistData source) {
      List<String> lines = new ArrayList<>();
      lines.add("type,id,addedAt,addedByName,addedByUuid,originalInput,status,comments,removedAt,removedByName,removedByUuid,removalNote,removalRecordId");
      if (source == null) {
         return String.join("\r\n", lines) + "\r\n";
      } else {
         for (BiliWhitelistManager.Entry entry : source.entries.values()) {
            lines.add(csvRow(entry, "ACTIVE", "", "", "", "", ""));
         }

         for (BiliWhitelistManager.RemovalRecord record : source.removalRecords) {
            lines.add(csvRow(record.entry, "REMOVED", record.removedAt, record.removedByName, record.removedByUuid, record.note, record.recordId));
         }

         return String.join("\r\n", lines) + "\r\n";
      }
   }

   private static String csvRow(
      BiliWhitelistManager.Entry entry, String status, String removedAt, String removedByName, String removedByUuid, String removalNote, String removalRecordId
   ) {
      BiliWhitelistManager.Entry safeEntry = entry == null ? new BiliWhitelistManager.Entry() : entry;
      return String.join(
         ",",
         csv(safeEntry.type),
         csv(safeEntry.id),
         csv(safeEntry.addedAt),
         csv(safeEntry.addedByName),
         csv(safeEntry.addedByUuid),
         csv(safeEntry.originalInput),
         csv(status),
         csv(formatComments(safeEntry.comments)),
         csv(removedAt),
         csv(removedByName),
         csv(removedByUuid),
         csv(removalNote),
         csv(removalRecordId)
      );
   }

   private static String formatComments(List<BiliWhitelistManager.ReviewComment> comments) {
      return comments != null && !comments.isEmpty()
         ? comments.stream()
            .map(
               comment -> spreadsheetSafe(safe(comment.createdAt))
                  + " | "
                  + spreadsheetSafe(safe(comment.authorName))
                  + " | "
                  + spreadsheetSafe(safe(comment.text))
            )
            .reduce((left, right) -> left + " || " + right)
            .orElse("")
         : "";
   }

   public static Component denialMessage(ServerPlayer player, String sourceUrl, String actionText) {
      String display = canonicalId(sourceUrl).orElse(sourceUrl == null ? "" : sourceUrl);
      MutableComponent message = Component.literal("该音源未加入白名单，已拒绝" + actionText + "：")
         .withStyle(ChatFormatting.RED)
         .append(Component.literal(display).withStyle(ChatFormatting.YELLOW));
      CommandSourceStack source = player == null ? null : player.createCommandSourceStack();
      if (NetMusicBiliServerCommands.canManageWhitelist(source)) {
         String command = "/netmusicbiliserver whitelist add " + commandArgument(sourceUrl == null ? "" : sourceUrl);
         return message.append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
            .append(
               Component.literal("[点击添加白名单]")
                  .withStyle(
                     style -> style.withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(Action.RUN_COMMAND, command))
                        .withHoverEvent(
                           new HoverEvent(
                              net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                              Component.literal("点击执行：").withStyle(ChatFormatting.YELLOW).append(Component.literal(command).withStyle(ChatFormatting.GRAY))
                           )
                        )
                  )
            );
      } else {
         String contact = Config.linkWhitelistContactPlaceholder != null && !Config.linkWhitelistContactPlaceholder.isBlank()
            ? Config.linkWhitelistContactPlaceholder.trim()
            : "管理员";
         return message.append(Component.literal("。请联系 " + contact + " 添加白名单。").withStyle(ChatFormatting.GRAY));
      }
   }

   private static void ensureLoaded(MinecraftServer server) {
      Path path = storagePath(server);
      if (!path.equals(loadedPath)) {
         loadedPath = path;
         loadFailure = null;
         data = new BiliWhitelistManager.WhitelistData();
         if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
               BiliWhitelistManager.WhitelistData loaded = (BiliWhitelistManager.WhitelistData)GSON.fromJson(reader, BiliWhitelistManager.WhitelistData.class);
               if (loaded == null) {
                  throw new JsonParseException("白名单文件内容为空");
               }

               loaded.normalizeAfterLoad();
               data = loaded;
            } catch (JsonParseException | IOException var7) {
               LOGGER.error("读取链接白名单失败；为避免覆盖原文件，本次运行将拒绝白名单写入: {}", path, var7);
               loadFailure = var7;
               data = new BiliWhitelistManager.WhitelistData();
            }
         }
      }
   }

   private static void save(MinecraftServer server, BiliWhitelistManager.WhitelistData next) throws IOException {
      Path path = storagePath(server);
      if (loadFailure != null && path.equals(loadedPath)) {
         throw new IOException("白名单文件读取失败，已拒绝覆盖原文件；请修复文件后重启服务器", loadFailure);
      } else {
         Files.createDirectories(path.getParent());
         Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

         try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
               GSON.toJson(next, writer);
            }

            try {
               Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var9) {
               Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }

            loadFailure = null;
         } catch (IOException var11) {
            try {
               Files.deleteIfExists(temporary);
            } catch (IOException var7) {
               var11.addSuppressed(var7);
            }

            throw var11;
         }
      }
   }

   private static Path storagePath(MinecraftServer server) {
      return storageDir(server).resolve("net_music_can_play_bili_link_whitelist.json");
   }

   private static Path storageDir(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve("net_music_can_play_bili");
   }

   private static String storageKey(String canonicalId) {
      if (canonicalId == null) {
         return "";
      } else {
         String value = canonicalId.trim();
         int split = value.indexOf(58);
         return split < 0 ? value : value.substring(0, split).toLowerCase(Locale.ROOT) + value.substring(split);
      }
   }

   private static String normalizeVideoSelection(BiliApiClient.VideoSelection selection) {
      return selection == null ? "" : BiliApiClient.formatStoredVideoSelection(selection.videoId(), selection.page());
   }

   private static String normalizeUrl(String raw) {
      String value = raw.trim();
      if (value.isBlank()) {
         return null;
      } else {
         try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
               return value;
            } else if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme) && !"ftp".equalsIgnoreCase(scheme)) {
               return uri.normalize().toString();
            } else {
               String host = uri.getHost();
               URI normalized = new URI(
                  scheme.toLowerCase(Locale.ROOT),
                  uri.getUserInfo(),
                  host == null ? null : host.toLowerCase(Locale.ROOT),
                  uri.getPort(),
                  uri.getPath(),
                  uri.getQuery(),
                  null
               );
               return normalized.toASCIIString();
            }
         } catch (Exception var6) {
            return value;
         }
      }
   }

   private static String commandArgument(String value) {
      return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
   }

   private static String normalizeAuditText(String value) {
      String normalized = safe(value).replace('\r', ' ').replace('\n', ' ').trim();
      return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
   }

   private static String safe(String value) {
      return value == null ? "" : value;
   }

   private static String spreadsheetSafe(String value) {
      String safeValue = safe(value);
      int firstVisible = 0;

      while (firstVisible < safeValue.length() && Character.isWhitespace(safeValue.charAt(firstVisible))) {
         firstVisible++;
      }

      return firstVisible < safeValue.length() && "=+-@".indexOf(safeValue.charAt(firstVisible)) >= 0 ? "'" + safeValue : safeValue;
   }

   private static String csv(String value) {
      String safeValue = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
      safeValue = spreadsheetSafe(safeValue);
      return "\"" + safeValue.replace("\"", "\"\"") + "\"";
   }

   public record AddResult(BiliWhitelistManager.AddResult.Status status, BiliWhitelistManager.Entry entry) {
      static BiliWhitelistManager.AddResult added(BiliWhitelistManager.Entry entry) {
         return new BiliWhitelistManager.AddResult(BiliWhitelistManager.AddResult.Status.ADDED, entry.copy());
      }

      static BiliWhitelistManager.AddResult duplicate(BiliWhitelistManager.Entry entry) {
         return new BiliWhitelistManager.AddResult(BiliWhitelistManager.AddResult.Status.DUPLICATE, entry.copy());
      }

      static BiliWhitelistManager.AddResult invalid() {
         return new BiliWhitelistManager.AddResult(BiliWhitelistManager.AddResult.Status.INVALID, null);
      }

      public static enum Status {
         ADDED,
         DUPLICATE,
         INVALID;
      }
   }

   public record CanonicalResource(String type, String id) {
      public String key() {
         return this.type + ":" + this.id;
      }
   }

   public record CommentResult(
      BiliWhitelistManager.CommentResult.Status status, String requestedId, BiliWhitelistManager.Entry entry, BiliWhitelistManager.ReviewComment comment
   ) {
      static BiliWhitelistManager.CommentResult added(BiliWhitelistManager.Entry entry, BiliWhitelistManager.ReviewComment comment) {
         return new BiliWhitelistManager.CommentResult(BiliWhitelistManager.CommentResult.Status.ADDED, entry.id, entry.copy(), comment.copy());
      }

      static BiliWhitelistManager.CommentResult missing(String id) {
         return new BiliWhitelistManager.CommentResult(BiliWhitelistManager.CommentResult.Status.MISSING, id, null, null);
      }

      static BiliWhitelistManager.CommentResult invalid() {
         return new BiliWhitelistManager.CommentResult(BiliWhitelistManager.CommentResult.Status.INVALID, "", null, null);
      }

      static BiliWhitelistManager.CommentResult commentRequired(String id) {
         return new BiliWhitelistManager.CommentResult(BiliWhitelistManager.CommentResult.Status.COMMENT_REQUIRED, id, null, null);
      }

      static BiliWhitelistManager.CommentResult stale(String id) {
         return new BiliWhitelistManager.CommentResult(BiliWhitelistManager.CommentResult.Status.STALE, id, null, null);
      }

      public static enum Status {
         ADDED,
         MISSING,
         INVALID,
         COMMENT_REQUIRED,
         STALE;
      }
   }

   public static final class Entry {
      public String type = "";
      public String id = "";
      public String originalInput = "";
      public String addedByName = "";
      public String addedByUuid = "";
      public String addedAt = "";
      public List<BiliWhitelistManager.ReviewComment> comments = new ArrayList<>();

      private void normalizeAfterLoad() {
         this.type = BiliWhitelistManager.safe(this.type);
         this.id = BiliWhitelistManager.safe(this.id);
         this.originalInput = BiliWhitelistManager.safe(this.originalInput);
         this.addedByName = BiliWhitelistManager.safe(this.addedByName);
         this.addedByUuid = BiliWhitelistManager.safe(this.addedByUuid);
         this.addedAt = BiliWhitelistManager.safe(this.addedAt);
         if (this.comments == null) {
            this.comments = new ArrayList<>();
         }

         this.comments.removeIf(Objects::isNull);
         this.comments.forEach(comment -> comment.normalizeAfterLoad());
      }

      private BiliWhitelistManager.Entry copy() {
         BiliWhitelistManager.Entry copy = new BiliWhitelistManager.Entry();
         copy.type = this.type;
         copy.id = this.id;
         copy.originalInput = this.originalInput;
         copy.addedByName = this.addedByName;
         copy.addedByUuid = this.addedByUuid;
         copy.addedAt = this.addedAt;
         copy.comments = (List<BiliWhitelistManager.ReviewComment>)(this.comments == null
            ? new ArrayList<>()
            : this.comments.stream().filter(Objects::nonNull).map(comment -> comment.copy()).collect(Collectors.toCollection(ArrayList::new)));
         return copy;
      }
   }

   public static final class RemovalRecord {
      public String recordId = "";
      public BiliWhitelistManager.Entry entry = new BiliWhitelistManager.Entry();
      public String removedAt = "";
      public String removedByName = "";
      public String removedByUuid = "";
      public String note = "";

      private static BiliWhitelistManager.RemovalRecord create(BiliWhitelistManager.Entry entry, ServerPlayer player, String note) {
         BiliWhitelistManager.RemovalRecord record = new BiliWhitelistManager.RemovalRecord();
         record.recordId = UUID.randomUUID().toString();
         record.entry = entry.copy();
         record.removedAt = Instant.now().toString();
         record.removedByName = player != null ? player.getDisplayName().getString() : "Console";
         record.removedByUuid = player != null ? player.getUUID().toString() : "";
         record.note = note;
         return record;
      }

      private void normalizeAfterLoad() {
         this.recordId = BiliWhitelistManager.safe(this.recordId);
         if (this.entry == null) {
            this.entry = new BiliWhitelistManager.Entry();
         }

         this.entry.normalizeAfterLoad();
         this.removedAt = BiliWhitelistManager.safe(this.removedAt);
         this.removedByName = BiliWhitelistManager.safe(this.removedByName);
         this.removedByUuid = BiliWhitelistManager.safe(this.removedByUuid);
         this.note = BiliWhitelistManager.safe(this.note);
         if (this.recordId.isBlank()) {
            this.recordId = this.stableLegacyRecordId();
         }
      }

      private String stableLegacyRecordId() {
         String fingerprint = String.join("\n", BiliWhitelistManager.safe(this.entry.id), this.removedAt, this.removedByName, this.removedByUuid, this.note);
         return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString();
      }

      private BiliWhitelistManager.RemovalRecord copy() {
         BiliWhitelistManager.RemovalRecord copy = new BiliWhitelistManager.RemovalRecord();
         copy.recordId = this.recordId;
         copy.entry = this.entry == null ? new BiliWhitelistManager.Entry() : this.entry.copy();
         copy.removedAt = this.removedAt;
         copy.removedByName = this.removedByName;
         copy.removedByUuid = this.removedByUuid;
         copy.note = this.note;
         return copy;
      }
   }

   public record RemoveResult(
      BiliWhitelistManager.RemoveResult.Status status, String requestedId, BiliWhitelistManager.Entry entry, BiliWhitelistManager.RemovalRecord removalRecord
   ) {
      static BiliWhitelistManager.RemoveResult removed(BiliWhitelistManager.Entry entry, BiliWhitelistManager.RemovalRecord record) {
         return new BiliWhitelistManager.RemoveResult(BiliWhitelistManager.RemoveResult.Status.REMOVED, entry.id, entry.copy(), record.copy());
      }

      static BiliWhitelistManager.RemoveResult missing(String id) {
         return new BiliWhitelistManager.RemoveResult(BiliWhitelistManager.RemoveResult.Status.MISSING, id, null, null);
      }

      static BiliWhitelistManager.RemoveResult invalid() {
         return new BiliWhitelistManager.RemoveResult(BiliWhitelistManager.RemoveResult.Status.INVALID, "", null, null);
      }

      static BiliWhitelistManager.RemoveResult noteRequired(String id) {
         return new BiliWhitelistManager.RemoveResult(BiliWhitelistManager.RemoveResult.Status.NOTE_REQUIRED, id, null, null);
      }

      static BiliWhitelistManager.RemoveResult stale(String id) {
         return new BiliWhitelistManager.RemoveResult(BiliWhitelistManager.RemoveResult.Status.STALE, id, null, null);
      }

      public static enum Status {
         REMOVED,
         MISSING,
         INVALID,
         NOTE_REQUIRED,
         STALE;
      }
   }

   public static final class ReviewComment {
      public String text = "";
      public String authorName = "";
      public String authorUuid = "";
      public String createdAt = "";

      private static BiliWhitelistManager.ReviewComment create(ServerPlayer player, String text) {
         BiliWhitelistManager.ReviewComment comment = new BiliWhitelistManager.ReviewComment();
         comment.text = text;
         comment.authorName = player != null ? player.getDisplayName().getString() : "Console";
         comment.authorUuid = player != null ? player.getUUID().toString() : "";
         comment.createdAt = Instant.now().toString();
         return comment;
      }

      private void normalizeAfterLoad() {
         this.text = BiliWhitelistManager.safe(this.text);
         this.authorName = BiliWhitelistManager.safe(this.authorName);
         this.authorUuid = BiliWhitelistManager.safe(this.authorUuid);
         this.createdAt = BiliWhitelistManager.safe(this.createdAt);
      }

      private BiliWhitelistManager.ReviewComment copy() {
         BiliWhitelistManager.ReviewComment copy = new BiliWhitelistManager.ReviewComment();
         copy.text = this.text;
         copy.authorName = this.authorName;
         copy.authorUuid = this.authorUuid;
         copy.createdAt = this.createdAt;
         return copy;
      }
   }

   public record ReviewSnapshot(
      int entryOffset,
      int totalEntries,
      List<BiliWhitelistManager.Entry> entries,
      int removalOffset,
      int totalRemovalRecords,
      List<BiliWhitelistManager.RemovalRecord> removalRecords
   ) {
   }

   private static final class WhitelistData {
      int schemaVersion = 2;
      Map<String, BiliWhitelistManager.Entry> entries = new LinkedHashMap<>();
      List<BiliWhitelistManager.RemovalRecord> removalRecords = new ArrayList<>();

      private BiliWhitelistManager.WhitelistData copy() {
         BiliWhitelistManager.WhitelistData copy = new BiliWhitelistManager.WhitelistData();
         copy.schemaVersion = this.schemaVersion;
         copy.entries = new LinkedHashMap<>();
         this.entries.forEach((key, entry) -> copy.entries.put(key, entry.copy()));
         copy.removalRecords = this.removalRecords.stream().map(record -> record.copy()).collect(Collectors.toCollection(ArrayList::new));
         return copy;
      }

      private void normalizeAfterLoad() {
         this.schemaVersion = 2;
         if (this.entries == null) {
            this.entries = new LinkedHashMap<>();
         }

         this.entries.values().removeIf(Objects::isNull);
         this.entries.values().forEach(entry -> entry.normalizeAfterLoad());
         if (this.removalRecords == null) {
            this.removalRecords = new ArrayList<>();
         }

         this.removalRecords.removeIf(Objects::isNull);
         this.removalRecords.forEach(record -> record.normalizeAfterLoad());
         this.removalRecords.sort(Comparator.comparing(record -> BiliWhitelistManager.safe(record.removedAt)));
      }
   }
}
