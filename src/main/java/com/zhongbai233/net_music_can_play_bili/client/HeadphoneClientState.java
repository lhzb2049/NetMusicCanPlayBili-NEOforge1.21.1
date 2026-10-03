package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public final class HeadphoneClientState {
   private HeadphoneClientState() {
   }

   public static boolean equipped() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player == null ? false : HeadphoneAbility.has(EquippedMediaItems.firstHeadphones(minecraft.player));
   }

   public static boolean handlesTurntable(BlockPos turntablePos) {
      if (equipped() && turntablePos != null) {
         ItemStack stack = equippedStack();
         if (stack.isEmpty()) {
            return false;
         } else {
            BlockPos linked = AudioLinkData.readHeadphoneTurntable(stack);
            return turntablePos.equals(linked) && withinDecodeRange(turntablePos);
         }
      } else {
         return false;
      }
   }

   public static boolean suppressesTurntable(BlockPos turntablePos) {
      return equipped() && !handlesTurntable(turntablePos);
   }

   public static boolean linkedTurntableOutOfRange(BlockPos turntablePos) {
      if (equipped() && turntablePos != null) {
         ItemStack stack = equippedStack();
         if (stack.isEmpty()) {
            return false;
         } else {
            BlockPos linked = AudioLinkData.readHeadphoneTurntable(stack);
            return turntablePos.equals(linked) && !withinDecodeRange(turntablePos);
         }
      } else {
         return false;
      }
   }

   public static boolean handlesMp4(UUID deviceId) {
      return handlesMediaDevice(deviceId);
   }

   public static boolean handlesMediaDevice(UUID deviceId) {
      if (equipped() && deviceId != null) {
         ItemStack stack = equippedStack();
         return !stack.isEmpty() && deviceId.equals(AudioLinkData.readHeadphoneMediaDevice(stack));
      } else {
         return false;
      }
   }

   private static boolean withinDecodeRange(BlockPos turntablePos) {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player == null ? false : minecraft.player.distanceToSqr(turntablePos.getCenter()) <= 4096.0;
   }

   private static ItemStack equippedStack() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player == null ? ItemStack.EMPTY : EquippedMediaItems.firstHeadphones(minecraft.player);
   }
}
