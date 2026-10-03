package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent.Nodes;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContextKey;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode.PermissionResolver;

public final class NetMusicPermissions {
   private static final int OP_LEVEL_GAMEMASTERS = 2;
   private static final int OP_LEVEL_OWNERS = 4;
   public static final PermissionNode<Boolean> AUDIT_SOURCES = booleanNode(
      "audit.sources", "NetMusic Bili audit sources", "允许查询当前正在播放的现代化唱片机/MP4 音源。", NetMusicPermissions::defaultOpLevelTwo
   );
   public static final PermissionNode<Boolean> PAD_REFRESH = booleanNode(
      "pad.refresh", "NetMusic Bili pad refresh", "允许刷新 Pad 服务端临时数据。", NetMusicPermissions::defaultOpLevelTwo
   );
   public static final PermissionNode<Boolean> WHITELIST_MANAGE = booleanNode(
      "whitelist.manage", "NetMusic Bili whitelist manage", "允许管理 Bili/NetMusic 链接白名单并打开审核界面。", NetMusicPermissions::defaultOpLevelFour
   );
   public static final PermissionNode<Boolean> CONTROL_CONSOLE_ADMIN = booleanNode(
      "control_console.admin",
      "NetMusic Bili control console admin",
      "允许绕过中控台 owner/accessMode 编辑限制并恢复无人认领的中控台；OP2 及以上始终允许。",
      NetMusicPermissions::defaultOpLevelTwo
   );

   private NetMusicPermissions() {
   }

   public static void onPermissionGather(Nodes event) {
      event.addNodes(new PermissionNode[]{AUDIT_SOURCES, PAD_REFRESH, WHITELIST_MANAGE, CONTROL_CONSOLE_ADMIN});
   }

   public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node) {
      if (source == null) {
         return false;
      } else {
         try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
               return true;
            } else {
               return isSingleplayerOwner(source, player) ? true : (Boolean)PermissionAPI.getPermission(player, node, new PermissionDynamicContext[0]);
            }
         } catch (Exception var3) {
            return false;
         }
      }
   }

   public static boolean canAdministerControlConsole(CommandSourceStack source) {
      if (source == null) {
         return false;
      } else {
         try {
            ServerPlayer player = source.getPlayer();
            if (player == null) {
               return true;
            } else {
               boolean singleplayerOwner = isSingleplayerOwner(source, player);
               boolean opLevelTwoOrHigher = hasVanillaPermission(player, 2);
               return ControlConsolePermissionPolicy.grantsAdministrator(singleplayerOwner, opLevelTwoOrHigher, false)
                  ? true
                  : ControlConsolePermissionPolicy.grantsAdministrator(
                     false, false, (Boolean)PermissionAPI.getPermission(player, CONTROL_CONSOLE_ADMIN, new PermissionDynamicContext[0])
                  );
            }
         } catch (Exception var4) {
            return false;
         }
      }
   }

   private static PermissionNode<Boolean> booleanNode(String name, String readableName, String description, PermissionResolver<Boolean> defaultResolver) {
      PermissionNode<Boolean> node = new PermissionNode(
         "net_music_can_play_bili", name, PermissionTypes.BOOLEAN, defaultResolver, new PermissionDynamicContextKey[0]
      );
      node.setInformation(Component.literal(readableName), Component.literal(description));
      return node;
   }

   private static boolean defaultOpLevelTwo(ServerPlayer player, UUID playerUUID, PermissionDynamicContext<?>... context) {
      return hasVanillaPermission(player, 2);
   }

   private static boolean defaultOpLevelFour(ServerPlayer player, UUID playerUUID, PermissionDynamicContext<?>... context) {
      return hasVanillaPermission(player, 4);
   }

   private static boolean hasVanillaPermission(ServerPlayer player, int minimum) {
      if (player == null) {
         return false;
      } else {
         MinecraftServer server = player.level().getServer();
         if (server == null) {
            return false;
         } else {
            GameProfile profile = player.getGameProfile();
            return profile == null ? false : server.getProfilePermissions(profile) >= minimum;
         }
      }
   }

   private static boolean isSingleplayerOwner(CommandSourceStack source, ServerPlayer player) {
      GameProfile profile = player.getGameProfile();
      return profile != null && source.getServer().isSingleplayerOwner(profile);
   }
}
