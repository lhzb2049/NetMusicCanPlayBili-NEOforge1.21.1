package com.zhongbai233.net_music_can_play_bili.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.neoforged.fml.loading.FMLPaths;

public final class ClientPlayerPreferences {
   private static final Logger LOGGER = Logger.getLogger(ClientPlayerPreferences.class.getName());
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
   private static final int SCHEMA_VERSION = 1;
   private final Path file;
   private final Set<UUID> dismissedControlConsoleGuides = new HashSet<>();
   private boolean loaded;

   public ClientPlayerPreferences(Path file) {
      this.file = Objects.requireNonNull(file, "file");
   }

   public static ClientPlayerPreferences defaults() {
      return ClientPlayerPreferences.DefaultHolder.INSTANCE;
   }

   public synchronized boolean isControlConsoleGuideDismissed(UUID playerId) {
      this.loadIfNeeded();
      return this.dismissedControlConsoleGuides.contains(Objects.requireNonNull(playerId, "playerId"));
   }

   public synchronized boolean dismissControlConsoleGuide(UUID playerId) {
      this.loadIfNeeded();
      if (!this.dismissedControlConsoleGuides.add(Objects.requireNonNull(playerId, "playerId"))) {
         return true;
      } else {
         try {
            this.saveAtomically();
            return true;
         } catch (IOException var3) {
            LOGGER.log(Level.WARNING, "保存中控台说明书偏好失败: " + this.file, (Throwable)var3);
            return false;
         }
      }
   }

   private void loadIfNeeded() {
      if (!this.loaded) {
         this.loaded = true;
         if (Files.isRegularFile(this.file)) {
            try {
               JsonObject root = JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8)).getAsJsonObject();

               for (JsonElement element : root.has("controlConsoleGuideDismissedPlayers") && root.get("controlConsoleGuideDismissedPlayers").isJsonArray()
                  ? root.getAsJsonArray("controlConsoleGuideDismissedPlayers")
                  : new JsonArray()) {
                  try {
                     this.dismissedControlConsoleGuides.add(UUID.fromString(element.getAsString()));
                  } catch (RuntimeException var6) {
                     LOGGER.warning("忽略无效的中控台说明书玩家 UUID: " + element);
                  }
               }
            } catch (Exception var7) {
               this.dismissedControlConsoleGuides.clear();
               LOGGER.log(Level.WARNING, "加载客户端玩家偏好失败，将使用默认值: " + this.file, (Throwable)var7);
            }
         }
      }
   }

   private void saveAtomically() throws IOException {
      Path parent = this.file.toAbsolutePath().getParent();
      if (parent == null) {
         throw new IOException("preferences path has no parent: " + this.file);
      } else {
         Files.createDirectories(parent);
         JsonObject root = new JsonObject();
         root.addProperty("schemaVersion", 1);
         JsonArray players = new JsonArray();
         this.dismissedControlConsoleGuides.stream().sorted().forEach(id -> players.add(id.toString()));
         root.add("controlConsoleGuideDismissedPlayers", players);
         Path temporary = Files.createTempFile(parent, this.file.getFileName() + ".", ".tmp");

         try {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);

            try {
               Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException var9) {
               Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            }
         } finally {
            Files.deleteIfExists(temporary);
         }
      }
   }

   private static final class DefaultHolder {
      private static final ClientPlayerPreferences INSTANCE = new ClientPlayerPreferences(
         FMLPaths.CONFIGDIR.get().resolve("net_music_can_play_bili").resolve("client-player-preferences.json")
      );
   }
}
