package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.network.LiveStreamerControlPacket;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class LiveStreamerScreen extends BlackGoldScreen {
   private static final int FIELD_H = 20;
   private EditBox roomField;
   private BlackGoldButton startButton;
   private BlackGoldButton stopButton;
   private float volume = 1.0F;
   private int lastSentVolumePerMille = -1;

   public LiveStreamerScreen(BlockPos pos) {
      super(Component.translatable("gui.net_music_can_play_bili.live_streamer"), pos);
   }

   @Override
   protected int boxH() {
      return 190;
   }

   @Override
   protected void buildWidgets() {
      int bx = this.boxX();
      int by = this.boxY();
      LiveStreamerBlockEntity be = this.getStreamerBE();
      String savedRoom = this.roomField != null ? this.roomField.getValue() : null;
      this.volume = be != null ? be.getVolume() : this.volume;
      int fx = bx + 16;
      int fy = by + 28 + 26;
      int fieldW = 288;
      this.roomField = new EditBox(this.font, fx, fy, fieldW, 20, Component.empty());
      this.roomField.setMaxLength(128);
      this.roomField.setHint(Component.translatable("gui.net_music_can_play_bili.live_streamer.room_hint"));
      this.roomField.setValue(savedRoom != null ? savedRoom : (be != null ? be.getRoomId() : ""));
      this.addRenderableWidget(this.roomField);
      int btnY = fy + 20 + 12;
      int btnW = (fieldW - 8) / 2;
      this.startButton = new BlackGoldButton(
         fx,
         btnY,
         btnW,
         20,
         Component.translatable("gui.net_music_can_play_bili.live_streamer.start"),
         btn -> this.sendControl(LiveStreamerControlPacket.Action.START),
         -2840509
      );
      this.addRenderableWidget(this.startButton);
      this.stopButton = new BlackGoldButton(
         fx + btnW + 8,
         btnY,
         btnW,
         20,
         Component.translatable("gui.net_music_can_play_bili.live_streamer.stop"),
         btn -> this.sendControl(LiveStreamerControlPacket.Action.STOP),
         -6252408
      );
      this.addRenderableWidget(this.stopButton);
      ConfigSlider volumeSlider = new ConfigSlider(fx + 24, btnY + 20 + 16, 145, 20, 0.0F, 1.0F, this.volume, v -> this.applyLocalVolume(v)) {
         public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            LiveStreamerScreen.this.sendVolume();
         }
      };
      this.addConfigSlider(volumeSlider, this.volume, v -> {
         this.applyLocalVolume(v);
         if (volumeSlider.linkedBox != null && volumeSlider.linkedBox.isFocused()) {
            this.sendVolume();
         }
      });
   }

   private void applyLocalVolume(float value) {
      this.volume = value;
      LiveStreamerBlockEntity be = this.getStreamerBE();
      if (be != null) {
         be.setClientVolumePerMille(Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 1000.0F));
      }
   }

   private void sendVolume() {
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         int volumePerMille = Math.round(Math.max(0.0F, Math.min(1.0F, this.volume)) * 1000.0F);
         if (volumePerMille != this.lastSentVolumePerMille) {
            this.lastSentVolumePerMille = volumePerMille;
            PacketDistributor.sendToServer(
               new LiveStreamerControlPacket(this.blockPos, LiveStreamerControlPacket.Action.SET_VOLUME, "", volumePerMille), new CustomPacketPayload[0]
            );
         }
      }
   }

   private void sendControl(LiveStreamerControlPacket.Action action) {
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         String roomInput = this.roomField != null ? this.roomField.getValue().trim() : "";
         PacketDistributor.sendToServer(new LiveStreamerControlPacket(this.blockPos, action, roomInput, 0), new CustomPacketPayload[0]);
      }
   }

   @Override
   protected void onSave() {
      if (this.minecraft != null && this.minecraft.getConnection() != null) {
         LiveStreamerBlockEntity be = this.getStreamerBE();
         String roomInput = this.roomField != null ? this.roomField.getValue().trim() : "";
         if (be != null && !roomInput.equals(be.getRoomId())) {
            PacketDistributor.sendToServer(
               new LiveStreamerControlPacket(this.blockPos, LiveStreamerControlPacket.Action.SET_ROOM, roomInput, 0), new CustomPacketPayload[0]
            );
         }

         int volumePerMille = Math.round(Math.max(0.0F, Math.min(1.0F, this.volume)) * 1000.0F);
         if (be == null || be.getVolumePerMille() != volumePerMille) {
            PacketDistributor.sendToServer(
               new LiveStreamerControlPacket(this.blockPos, LiveStreamerControlPacket.Action.SET_VOLUME, "", volumePerMille), new CustomPacketPayload[0]
            );
         }
      }
   }

   @Override
   protected void drawContent(GuiGraphics g, int bx, int by, int mx, int my) {
      int cx = bx + 160;
      g.drawCenteredString(this.font, Component.translatable("gui.net_music_can_play_bili.live_streamer.room_label"), cx, by + 28 + 8, -6252408);
      LiveStreamerBlockEntity be = this.getStreamerBE();
      Component status;
      int color;
      if (be != null && be.isPlaying()) {
         status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_playing", new Object[]{be.getRoomId()});
         color = -2840509;
      } else if (be != null && be.isWaitingForLive()) {
         status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_waiting", new Object[]{be.getRoomId()});
         color = -865972;
      } else if (be != null && !be.getRoomId().isEmpty()) {
         status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_ready", new Object[]{be.getRoomId()});
         color = -6252408;
      } else {
         status = Component.translatable("gui.net_music_can_play_bili.live_streamer.status_empty");
         color = -10463160;
      }

      g.drawCenteredString(this.font, status, cx, by + this.boxH() - 22, color);
   }

   private LiveStreamerBlockEntity getStreamerBE() {
      if (this.minecraft != null && this.minecraft.level != null) {
         return this.minecraft.level.getBlockEntity(this.blockPos) instanceof LiveStreamerBlockEntity streamer ? streamer : null;
      } else {
         return null;
      }
   }
}
