package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.LyricProjectorConfigPacket;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class LyricProjectorScreen extends BlackGoldScreen {
   private CycleWidget modeBtn;
   private CycleWidget aiBtn;

   public LyricProjectorScreen(BlockPos pos) {
      super(Component.literal("♫ 歌词投影仪"), pos);
   }

   @Override
   protected int boxH() {
      return 336;
   }

   @Override
   protected void buildWidgets() {
      int bx = this.boxX();
      int by = this.boxY();
      int sliderX = bx + 16 + 54;
      int resetX = sliderX + 145 + 4 + 42 + 3;
      int rowY = by + 28 + 54;
      LyricProjectorBlockEntity be = this.getProjectorBE();
      ConfigSlider yawS = this.addConfigSlider(sliderX, rowY, 0.0F, 360.0F, be != null ? be.getProjectionYaw() : 180.0F, v -> {
         if (be != null) {
            be.setProjectionYaw(v);
         }
      });
      this.addResetButton(resetX, rowY, 180.0F, yawS);
      rowY += 26;
      ConfigSlider pitchS = this.addConfigSlider(sliderX, rowY, -90.0F, 90.0F, be != null ? be.getProjectionPitch() : 0.0F, v -> {
         if (be != null) {
            be.setProjectionPitch(v);
         }
      });
      this.addResetButton(resetX, rowY, 0.0F, pitchS);
      rowY += 26;
      ConfigSlider heightS = this.addConfigSlider(sliderX, rowY, -5.0F, 5.0F, be != null ? be.getProjectionHeight() : 1.2F, v -> {
         if (be != null) {
            be.setProjectionHeight(v);
         }
      });
      this.addResetButton(resetX, rowY, 1.2F, heightS);
      rowY += 26;
      ConfigSlider distXS = this.addConfigSlider(sliderX, rowY, -5.0F, 5.0F, be != null ? be.getProjectionDistanceX() : 0.0F, v -> {
         if (be != null) {
            be.setProjectionDistanceX(v);
         }
      });
      this.addResetButton(resetX, rowY, 0.0F, distXS);
      rowY += 26;
      ConfigSlider distZS = this.addConfigSlider(sliderX, rowY, -5.0F, 5.0F, be != null ? be.getProjectionDistanceZ() : 0.0F, v -> {
         if (be != null) {
            be.setProjectionDistanceZ(v);
         }
      });
      this.addResetButton(resetX, rowY, 0.0F, distZS);
      rowY += 26;
      ConfigSlider scaleS = this.addConfigSlider(sliderX, rowY, 0.25F, 3.0F, be != null ? be.getProjectionScale() : 1.0F, v -> {
         if (be != null) {
            be.setProjectionScale(v);
         }
      });
      this.addResetButton(resetX, rowY, 1.0F, scaleS);
      rowY += 26;
      int mode = be != null ? be.getProjectionMode() : 0;
      this.modeBtn = this.addCycleWidget(sliderX, rowY, List.of("静态", "轮换·主", "轮换·副"), mode, v -> {
         if (be != null) {
            be.setProjectionMode(v);
         }
      });
      rowY += 30;
      boolean allowAi = be != null && be.getAllowAi();
      this.aiBtn = this.addCycleWidget(sliderX, rowY, List.of("AI字幕：关", "AI字幕：开"), allowAi ? 1 : 0, v -> {
         if (be != null) {
            be.setAllowAi(v == 1);
         }
      });
   }

   @Override
   protected void onSave() {
      LyricProjectorBlockEntity be = this.getProjectorBE();
      if (be != null && this.minecraft != null && this.minecraft.getConnection() != null) {
         PacketDistributor.sendToServer(
            new LyricProjectorConfigPacket(
               this.blockPos,
               be.getProjectionYaw(),
               be.getProjectionPitch(),
               be.getProjectionScale(),
               be.getProjectionHeight(),
               be.getProjectionDistanceX(),
               be.getProjectionDistanceZ(),
               be.getProjectionMode(),
               be.getAllowAi()
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   @Override
   protected void drawContent(GuiGraphics g, int bx, int by, int mx, int my) {
      this.drawLinkInfo(g, bx, by);
      this.drawLabels(g, bx, by);
      this.drawModeOverlay(g);
      this.drawAiOverlay(g);
   }

   private LyricProjectorBlockEntity getProjectorBE() {
      if (this.minecraft != null && this.minecraft.level != null) {
         return this.minecraft.level.getBlockEntity(this.blockPos) instanceof LyricProjectorBlockEntity p ? p : null;
      } else {
         return null;
      }
   }

   private void drawLinkInfo(GuiGraphics g, int bx, int by) {
      int cx = bx + 160;
      int iy = by + 28 + 10;
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         if (level.getBlockEntity(this.blockPos) instanceof LyricProjectorBlockEntity p) {
            BlockPos linked = p.getLinkedTurntablePos();
            if (linked == null) {
               g.drawCenteredString(this.font, Component.literal("未连接"), cx, iy, -10463160);
            } else {
               String info = String.format("已连接 (%d,%d,%d)", linked.getX(), linked.getY(), linked.getZ());
               if (level.getBlockEntity(linked) instanceof ModernTurntableBlockEntity t && !t.getSongName().isBlank()) {
                  String s = t.getSongName();
                  info = s.length() > 26 ? s.substring(0, 24) + ".." : s;
               }

               g.drawCenteredString(this.font, Component.literal(info), cx, iy, -2840509);
            }
         }
      }
   }

   private void drawLabels(GuiGraphics g, int bx, int by) {
      int lx = bx + 16;
      int ry = by + 28 + 58;
      String[] labels = new String[]{"水平朝向", "俯仰角度", "投影高度", "投影X轴", "投影Z轴", "文字大小", "语言/模式", "AI字幕"};

      for (String lb : labels) {
         g.drawCenteredString(this.font, Component.literal(lb), lx + 27, ry, -6252408);
         ry += 26;
      }
   }

   private void drawModeOverlay(GuiGraphics g) {
      if (this.modeBtn != null) {
         int x = this.modeBtn.getX();
         int y = this.modeBtn.getY();
         int w = this.modeBtn.getWidth();
         int h = this.modeBtn.getHeight();
         g.fillGradient(x, y, x + w, y + h, 819243075, 416589891);
         g.drawCenteredString(this.font, Component.literal("◈ " + this.modeBtn.currentOption()), x + w / 2, y + 5, -2840509);
      }
   }

   private void drawAiOverlay(GuiGraphics g) {
      if (this.aiBtn != null) {
         int x = this.aiBtn.getX();
         int y = this.aiBtn.getY();
         int w = this.aiBtn.getWidth();
         int h = this.aiBtn.getHeight();
         g.fillGradient(x, y, x + w, y + h, 819243075, 416589891);
         g.drawCenteredString(this.font, Component.literal("◈ " + this.aiBtn.currentOption()), x + w / 2, y + 5, -2840509);
      }
   }
}
