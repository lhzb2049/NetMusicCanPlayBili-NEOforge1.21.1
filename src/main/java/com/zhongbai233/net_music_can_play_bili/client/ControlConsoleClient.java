package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.ControlConsoleGuideScreen;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeasePacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeaseResultPacket;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ControlConsoleClient {
   private static final long RENEW_INTERVAL_MILLIS = 5000L;
   private static BlockPos pendingOpen;
   private static ControlConsoleClient.ActiveLease activeLease;

   private ControlConsoleClient() {
   }

   public static void openScreen(BlockPos pos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null) {
         BlockPos immutablePos = pos.immutable();
         if (activeLease != null && activeLease.pos().equals(immutablePos)) {
            openGrantedWorkflow(immutablePos, minecraft);
            return;
         }

         if (activeLease != null) {
            releaseLease(activeLease.pos());
         }

         pendingOpen = immutablePos;
         PacketDistributor.sendToServer(new ControlConsoleEditLeasePacket(pos, ControlConsoleEditLeasePacket.Action.OPEN, null), new CustomPacketPayload[0]);
      }
   }

   public static void acceptLeaseResult(ControlConsoleEditLeaseResultPacket result) {
      Minecraft minecraft = Minecraft.getInstance();
      if (activeLease != null && activeLease.pos().equals(result.pos())) {
         if (result.status() == ControlConsoleEditLeaseResultPacket.Status.GRANTED && activeLease.leaseId().equals(result.leaseId())) {
            activeLease = new ControlConsoleClient.ActiveLease(activeLease.pos(), activeLease.leaseId(), System.currentTimeMillis() + 5000L);
            return;
         }

         if (result.status() != ControlConsoleEditLeaseResultPacket.Status.GRANTED) {
            activeLease = null;
            if (minecraft.screen instanceof HolographicScreenConfigTestScreen || minecraft.screen instanceof ControlConsoleGuideScreen) {
               minecraft.setScreen(null);
            }

            if (minecraft.player != null) {
               minecraft.player.sendSystemMessage(Component.literal("中控台编辑租约已失效"));
            }

            return;
         }
      }

      if (pendingOpen != null && pendingOpen.equals(result.pos())) {
         pendingOpen = null;
         if (result.status() != ControlConsoleEditLeaseResultPacket.Status.GRANTED) {
            if (minecraft.player != null) {
               String message = result.status() == ControlConsoleEditLeaseResultPacket.Status.BUSY ? "该中控台正由其他玩家编辑" : "服务器拒绝打开中控台编辑器";
               minecraft.player.sendSystemMessage(Component.literal(message));
            }
         } else {
            activeLease = new ControlConsoleClient.ActiveLease(result.pos(), result.leaseId(), System.currentTimeMillis() + 5000L);
            openGrantedWorkflow(result.pos(), minecraft);
         }
      }
   }

   private static void openGrantedWorkflow(BlockPos pos, Minecraft minecraft) {
      if (minecraft.level != null && minecraft.player != null) {
         if (ClientPlayerPreferences.defaults().isControlConsoleGuideDismissed(minecraft.player.getUUID())) {
            openEditor(pos);
         } else {
            minecraft.setScreen(new ControlConsoleGuideScreen(pos));
         }
      }
   }

   public static UUID leaseId(BlockPos pos) {
      return activeLease != null && activeLease.pos().equals(pos) ? activeLease.leaseId() : null;
   }

   public static boolean hasLease(BlockPos pos) {
      return leaseId(pos) != null;
   }

   public static void tickLease(BlockPos pos) {
      if (activeLease != null && activeLease.pos().equals(pos) && System.currentTimeMillis() >= activeLease.nextRenewMillis()) {
         PacketDistributor.sendToServer(
            new ControlConsoleEditLeasePacket(pos, ControlConsoleEditLeasePacket.Action.RENEW, activeLease.leaseId()), new CustomPacketPayload[0]
         );
         activeLease = new ControlConsoleClient.ActiveLease(activeLease.pos(), activeLease.leaseId(), System.currentTimeMillis() + 5000L);
      }
   }

   public static void releaseLease(BlockPos pos) {
      if (activeLease != null && activeLease.pos().equals(pos)) {
         PacketDistributor.sendToServer(
            new ControlConsoleEditLeasePacket(pos, ControlConsoleEditLeasePacket.Action.RELEASE, activeLease.leaseId()), new CustomPacketPayload[0]
         );
         activeLease = null;
      }
   }

   public static void clearLease() {
      pendingOpen = null;
      activeLease = null;
   }

   public static void openEditor(BlockPos pos) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null && hasLease(pos)) {
         minecraft.setScreen(HolographicScreenConfigTestScreen.forControlConsole(pos));
      }
   }

   private record ActiveLease(BlockPos pos, UUID leaseId, long nextRenewMillis) {
   }
}
