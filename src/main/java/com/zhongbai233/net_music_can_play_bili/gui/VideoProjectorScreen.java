package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoFallbackReason;
import com.zhongbai233.net_music_can_play_bili.network.VideoProjectorConfigPacket;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class VideoProjectorScreen extends BlackGoldScreen {
   private static final int[] QUALITY_VALUES = new int[]{127, 120, 116, 112, 80, 64, 32, 16};
   private static final List<String> QUALITY_LABELS = List.of("尝试 8K", "尝试 4K", "尝试 1080P60", "尝试 1080P+", "尝试 1080P", "尝试 720P", "尝试 480P", "尝试 360P");
   private CycleWidget qualityBtn;

   public VideoProjectorScreen(BlockPos pos) {
      super(Component.literal("▣ 视频投影仪"), pos);
   }

   @Override
   protected int boxH() {
      return 344;
   }

   @Override
   protected void buildWidgets() {
      int bx = this.boxX();
      int by = this.boxY();
      int sliderX = bx + 16 + 54;
      int resetX = sliderX + 145 + 4 + 42 + 3;
      int rowY = by + 28 + 54;
      VideoProjectorBlockEntity be = this.getProjectorBE();
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
      ConfigSlider heightS = this.addConfigSlider(sliderX, rowY, -5.0F, 5.0F, be != null ? be.getProjectionHeight() : 1.8F, v -> {
         if (be != null) {
            be.setProjectionHeight(v);
         }
      });
      this.addResetButton(resetX, rowY, 1.8F, heightS);
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
      ConfigSlider brightnessS = this.addConfigSlider(sliderX, rowY, 0.0F, 100.0F, (be != null ? be.getProjectionBrightness() : 1.0F) * 100.0F, v -> {
         if (be != null) {
            be.setProjectionBrightness(v / 100.0F);
         }
      });
      this.addResetButton(resetX, rowY, 100.0F, brightnessS);
      rowY += 30;
      int qualityIndex = qualityIndex(be != null ? be.getPreferredQuality() : 116);
      this.qualityBtn = this.addCycleWidget(sliderX, rowY, QUALITY_LABELS, qualityIndex, v -> {
         if (be != null) {
            be.setPreferredQuality(QUALITY_VALUES[Math.clamp((long)v.intValue(), 0, QUALITY_VALUES.length - 1)]);
            this.sendConfigToServer(be);
            ModernTurntableVideoClient.refreshProjector(this.blockPos);
         }
      });
   }

   @Override
   protected void onSave() {
      VideoProjectorBlockEntity be = this.getProjectorBE();
      if (be != null) {
         this.sendConfigToServer(be);
      }
   }

   private void sendConfigToServer(VideoProjectorBlockEntity be) {
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         PacketDistributor.sendToServer(
            new VideoProjectorConfigPacket(
               this.blockPos,
               be.getProjectionYaw(),
               be.getProjectionPitch(),
               be.getProjectionScale(),
               be.getProjectionHeight(),
               be.getProjectionDistanceX(),
               be.getProjectionDistanceZ(),
               be.getProjectionBrightness(),
               be.getPreferredQuality()
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   @Override
   protected void drawContent(GuiGraphics g, int bx, int by, int mx, int my) {
      this.drawLinkInfo(g, bx, by);
      this.drawLabels(g, bx, by);
      this.drawQualityOverlay(g);
   }

   private VideoProjectorBlockEntity getProjectorBE() {
      if (this.minecraft != null && this.minecraft.level != null) {
         return this.minecraft.level.getBlockEntity(this.blockPos) instanceof VideoProjectorBlockEntity p ? p : null;
      } else {
         return null;
      }
   }

   private void drawLinkInfo(GuiGraphics g, int bx, int by) {
      int cx = bx + 160;
      int iy = by + 28 + 10;
      ClientLevel level = Minecraft.getInstance().level;
      if (level != null) {
         if (level.getBlockEntity(this.blockPos) instanceof VideoProjectorBlockEntity p) {
            BlockPos linked = p.getLinkedTurntablePos();
            if (linked == null) {
               g.drawCenteredString(this.font, Component.literal("未连接"), cx, iy, -10463160);
            } else {
               String info = String.format("已连接 (%d,%d,%d)", linked.getX(), linked.getY(), linked.getZ());
               if (level.getBlockEntity(linked) instanceof ModernTurntableBlockEntity t && !t.getSongName().isBlank()) {
                  String s = t.getSongName();
                  info = s.length() > 26 ? s.substring(0, 24) + ".." : s;
               } else if (level.getBlockEntity(linked) instanceof LiveStreamerBlockEntity live && !live.getRoomId().isEmpty()) {
                  info = "B站直播 " + live.getRoomId();
               }

               g.drawCenteredString(this.font, Component.literal(info), cx, iy, -2840509);
               VideoBillboardState.VideoStatus status = VideoBillboardPreview.getStatusForProjector(this.blockPos);
               String playbackInfo;
               if (status.active()) {
                  playbackInfo = String.format("当前播放：%dx%d @ %dfps%s", status.width(), status.height(), status.fps(), status.synced() ? " · 同步中" : "");
               } else {
                  playbackInfo = "当前播放：无";
               }

               g.drawCenteredString(this.font, Component.literal(playbackInfo), cx, iy + 12, -10463160);
               if (status.active() && status.actualQuality() > 0) {
                  String backend = status.backend().length() > 18 ? status.backend().substring(0, 18) + "…" : status.backend();
                  String fallback = status.fallbackReason().isBlank() ? "" : " · " + fallbackLabel(status.fallbackReason());
                  String actualInfo = "请求Q"
                     + status.requestedQuality()
                     + " → 实际Q"
                     + status.actualQuality()
                     + " · "
                     + status.codecLabel()
                     + " · "
                     + backend
                     + fallback;
                  g.drawCenteredString(this.font, Component.literal(actualInfo), cx, iy + 24, -10463160);
               }
            }
         }
      }
   }

   private static String fallbackLabel(String reason) {
      return VideoFallbackReason.userLabel(reason);
   }

   private void drawLabels(GuiGraphics g, int bx, int by) {
      int lx = bx + 16;
      int ry = by + 28 + 58;
      String[] labels = new String[]{"水平朝向", "俯仰角度", "投影高度", "投影X轴", "投影Z轴", "画面大小", "画面亮度", "请求画质"};

      for (String lb : labels) {
         g.drawCenteredString(this.font, Component.literal(lb), lx + 27, ry, -6252408);
         ry += 26;
      }

      g.drawCenteredString(this.font, Component.literal("实际清晰度会受视频源和账号权限影响"), bx + 160, by + this.boxH() - 28, -10463160);
   }

   private void drawQualityOverlay(GuiGraphics g) {
      if (this.qualityBtn != null) {
         int x = this.qualityBtn.getX();
         int y = this.qualityBtn.getY();
         int w = this.qualityBtn.getWidth();
         int h = this.qualityBtn.getHeight();
         g.fillGradient(x, y, x + w, y + h, 819243075, 416589891);
         g.drawCenteredString(this.font, Component.literal("◈ " + this.qualityBtn.currentOption()), x + w / 2, y + 5, -2840509);
      }
   }

   private static int qualityIndex(int quality) {
      for (int i = 0; i < QUALITY_VALUES.length; i++) {
         if (QUALITY_VALUES[i] == quality) {
            return i;
         }
      }

      return 0;
   }
}
