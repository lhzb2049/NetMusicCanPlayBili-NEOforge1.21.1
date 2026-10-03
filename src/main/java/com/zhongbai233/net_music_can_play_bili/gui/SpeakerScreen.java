package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.SpeakerConfigPacket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class SpeakerScreen extends BlackGoldScreen {
   private static final int CH_BTN_W = 24;
   private static final int CH_BTN_H = 16;
   private int channelIndex = -1;
   private float volume = 1.0F;
   private boolean autoMixJoc;
   private final Map<Integer, BlackGoldButton> chButtons = new LinkedHashMap<>();
   private BlackGoldButton noneBtn;
   private BlackGoldButton jocBtn;

   public SpeakerScreen(BlockPos pos) {
      super(Component.translatable("gui.net_music_can_play_bili.speaker"), pos);
   }

   @Override
   protected void buildWidgets() {
      this.chButtons.clear();
      int bx = this.boxX();
      int by = this.boxY();
      SpeakerBlockEntity be = this.getSpeakerBE();
      this.channelIndex = be != null ? be.getChannelIndex() : -1;
      this.volume = be != null ? be.getVolume() : 1.0F;
      this.autoMixJoc = be != null ? be.isAutoMixJoc() : false;
      int gx = bx + 16 + 14;
      int gy = by + 28 + 10;
      int[][] layout = new int[][]{
         {-1, -1, 8, -1, -1, -1, 9, -1}, {0, -1, 2, -1, 1, -1, 3, -1}, {4, -1, 5, -1, 6, -1, 7, -1}, {-1, -1, 10, -1, -1, -1, 11, -1}
      };
      String[] labels = new String[]{
         "",
         "",
         "Ltf",
         "",
         "",
         "",
         "Rtf",
         "",
         "L",
         "",
         "C",
         "",
         "R",
         "",
         "LFE",
         "",
         "Ls",
         "",
         "Rs",
         "",
         "Lrs",
         "",
         "Rrs",
         "",
         "",
         "",
         "Ltr",
         "",
         "",
         "",
         "Rtr",
         ""
      };

      for (int row = 0; row < layout.length; row++) {
         for (int col = 0; col < 8; col++) {
            int ch = layout[row][col];
            if (ch >= 0) {
               this.addChBtn(gx + col * 26, gy + row * 18, labels[row * 8 + col], ch);
            }
         }
      }

      int btnY = gy + layout.length * 18 + 8;
      boolean none = this.channelIndex == -1;
      this.noneBtn = new BlackGoldButton(gx, btnY, 50, 18, Component.literal(none ? "◉静音" : "○静音"), btn -> {
         this.channelIndex = -1;
         this.refreshAll();
      }, none ? -2840509 : -6252408);
      this.addRenderableWidget(this.noneBtn);
      this.jocBtn = new BlackGoldButton(gx + 58, btnY, 128, 18, Component.literal(this.autoMixJoc ? "◉融合未分配声道" : "○融合未分配声道"), btn -> {
         this.autoMixJoc = !this.autoMixJoc;
         this.refreshAll();
      }, this.autoMixJoc ? -2840509 : -6252408);
      this.addRenderableWidget(this.jocBtn);
      this.addConfigSlider(gx + 24, btnY + 16 + 12, 0.0F, 2.0F, this.volume, v -> this.volume = v);
   }

   private void addChBtn(int x, int y, String label, int ch) {
      boolean on = this.channelIndex == ch;
      BlackGoldButton btn = new BlackGoldButton(x, y, 24, 16, Component.literal(on ? "◉" + label : "○" + label), b -> {
         this.channelIndex = ch;
         this.refreshAll();
      }, on ? -2840509 : -6252408);
      this.addRenderableWidget(btn);
      this.chButtons.put(ch, btn);
   }

   private void refreshAll() {
      for (Entry<Integer, BlackGoldButton> e : this.chButtons.entrySet()) {
         int ch = e.getKey();
         BlackGoldButton btn = e.getValue();
         boolean on = this.channelIndex == ch;
         String pure = btn.getMessage().getString();
         if (pure.length() > 1 && (pure.charAt(0) == 9673 || pure.charAt(0) == 9675)) {
            pure = pure.substring(1);
         }

         btn.setMessage(Component.literal(on ? "◉" + pure : "○" + pure));
      }

      boolean none = this.channelIndex == -1;
      if (this.noneBtn != null) {
         this.noneBtn.setMessage(Component.literal(none ? "◉静音" : "○静音"));
      }

      if (this.jocBtn != null) {
         this.jocBtn.setMessage(Component.literal(this.autoMixJoc ? "◉融合未分配声道" : "○融合未分配声道"));
      }
   }

   private SpeakerBlockEntity getSpeakerBE() {
      if (this.minecraft != null && this.minecraft.level != null) {
         return this.minecraft.level.getBlockEntity(this.blockPos) instanceof SpeakerBlockEntity s ? s : null;
      } else {
         return null;
      }
   }

   @Override
   protected void onSave() {
      SpeakerBlockEntity be = this.getSpeakerBE();
      if (be != null && this.minecraft != null && this.minecraft.getConnection() != null) {
         PacketDistributor.sendToServer(new SpeakerConfigPacket(this.blockPos, this.channelIndex, this.volume, this.autoMixJoc), new CustomPacketPayload[0]);
         be.setChannelIndex(this.channelIndex);
         be.setVolume(this.volume);
         be.setAutoMixJoc(this.autoMixJoc);
         be.markDirtyAndSync();
      }
   }

   @Override
   protected void drawContent(GuiGraphics g, int bx, int by, int mx, int my) {
      int cx = bx + 160;
      g.drawCenteredString(this.font, Component.literal("声道选择 7.1.4"), cx, by + 28 + 2, -6252408);
      SpeakerBlockEntity be = this.getSpeakerBE();
      if (be != null) {
         BlockPos linked = be.getLinkedTurntablePos();
         String info = linked != null ? String.format("已连接 (%d,%d,%d)", linked.getX(), linked.getY(), linked.getZ()) : "未连接";
         g.drawCenteredString(this.font, Component.literal(info), cx, by + 310 - 20, linked != null ? -2840509 : -10463160);
      }
   }
}
