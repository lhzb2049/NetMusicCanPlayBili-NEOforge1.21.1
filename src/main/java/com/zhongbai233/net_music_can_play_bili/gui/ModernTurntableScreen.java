package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableControlPacket;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class ModernTurntableScreen extends BlackGoldScreen {
   private static final int T_BOX_W = 340;
   private static final int T_BOX_H = 210;
   private static final int T_HEADER_H = 28;
   private static final int VIS_BARS = 26;
   private static final int VIS_MAX_H = 24;
   private static final int BTN_W = 54;
   private static final int BTN_H = 22;
   private final float[] barPhases = new float[26];
   private final float[] barFreqs = new float[26];
   private final float[] barLevels = new float[26];
   private float energy = 0.1F;
   private int tickCounter;
   private String cachedFormatLabel = "";
   private int nextFormatLabelRefreshTick;
   private ModernTurntableScreen.ProgressSlider progressSlider;
   private BlackGoldButton replayButton;
   private BlackGoldButton playPauseButton;
   private BlackGoldButton repeatOneButton;
   private BlackGoldButton redstoneModeButton;
   private BlackGoldButton extractionModeButton;
   private ModernTurntableScreen.VolumeSlider volumeSlider;

   public ModernTurntableScreen(BlockPos pos) {
      super(Component.literal("♫ 现代唱片机"), pos);

      for (int i = 0; i < 26; i++) {
         this.barPhases[i] = (float)(Math.random() * Math.PI * 2.0);
         this.barFreqs[i] = 0.4F + (float)Math.random() * 2.0F;
      }
   }

   @Override
   protected int boxX() {
      return (this.width - 340) / 2;
   }

   @Override
   protected int boxY() {
      return (this.height - 210) / 2;
   }

   @Override
   protected void buildWidgets() {
      int bx = this.boxX();
      int by = this.boxY();
      int progressY = by + 28 + 56;
      int controlsY = by + 210 - 22 - 18;
      this.progressSlider = (ModernTurntableScreen.ProgressSlider)this.addRenderableWidget(
         new ModernTurntableScreen.ProgressSlider(bx + 16, progressY, 308, 18)
      );
      this.replayButton = (BlackGoldButton)this.addRenderableWidget(
         new BlackGoldButton(
            bx + 16, controlsY, 54, 22, Component.literal("⏮ 重播"), btn -> this.sendAction(ModernTurntableControlPacket.Action.REPLAY), -2840509
         )
      );
      this.playPauseButton = (BlackGoldButton)this.addRenderableWidget(
         new BlackGoldButton(bx + 16 + 54 + 6, controlsY, 54, 22, Component.literal("▶ 播放"), btn -> this.onPlayPause(), -2840509)
      );
      this.repeatOneButton = (BlackGoldButton)this.addRenderableWidget(
         new BlackGoldButton(
            bx + 16 + 120,
            controlsY,
            68,
            22,
            Component.literal("\ud83d\udd01 单曲"),
            btn -> this.sendAction(ModernTurntableControlPacket.Action.TOGGLE_REPEAT_ONE),
            -2840509
         )
      );
      this.redstoneModeButton = (BlackGoldButton)this.addRenderableWidget(
         new BlackGoldButton(
            bx + 16,
            controlsY - 22 - 6,
            126,
            22,
            Component.literal("红石：忽略"),
            btn -> this.sendAction(ModernTurntableControlPacket.Action.CYCLE_REDSTONE_MODE),
            -2840509
         )
      );
      this.extractionModeButton = (BlackGoldButton)this.addRenderableWidget(
         new BlackGoldButton(
            bx + 340 - 16 - 126,
            controlsY - 22 - 6,
            126,
            22,
            Component.literal("提取：播完提取"),
            btn -> this.sendAction(ModernTurntableControlPacket.Action.CYCLE_EXTRACTION_MODE),
            -2840509
         )
      );
      this.volumeSlider = (ModernTurntableScreen.VolumeSlider)this.addRenderableWidget(
         new ModernTurntableScreen.VolumeSlider(bx + 340 - 16 - 110, controlsY, 110, 22)
      );
      this.refreshWidgets();
   }

   @Override
   protected void onSave() {
   }

   public void tick() {
      super.tick();
      this.tickCounter++;
      ModernTurntableBlockEntity t = this.turntable();
      boolean playing = t != null && t.isPlaying();
      float audioLevel = playing ? ClientAudioOutputRegistry.audioLevel(this.blockPos) : 0.0F;
      float target = playing ? Math.max(0.08F, (float)Math.sqrt(audioLevel) * 1.15F) : 0.02F;
      this.energy = this.energy + (target - this.energy) * 0.06F;
      this.updateVisualizerLevels(playing, audioLevel);
      if (this.tickCounter >= this.nextFormatLabelRefreshTick) {
         this.cachedFormatLabel = resolveFormatLabel();
         this.nextFormatLabelRefreshTick = this.tickCounter + 20;
      }

      this.refreshWidgets();
   }

   @Override
   public void render(GuiGraphics g, int mx, int my, float pt) {
      this.renderBackground(g, mx, my, pt);
      int bx = this.boxX();
      int by = this.boxY();
      ModernTurntableBlockEntity t = this.turntable();
      this.drawBox(g, bx, by);
      this.drawHeader(g, bx, by, mx, my);
      this.drawSongInfo(g, bx, by, t);
      this.drawVisualizer(g, bx, by, pt);
      this.drawStatusDot(g, bx, by, t);
      this.renderWidgets(g, mx, my, pt);
      this.drawProgressOverlay(g, bx, by);
      this.drawVolumeOverlay(g, bx, by);
   }

   @Override
   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int bx = this.boxX();
      int by = this.boxY();
      int cx = bx + 340 - 14 - 8;
      int cy = by + 7;
      if (mouseX >= cx && mouseX <= cx + 14 && mouseY >= cy && mouseY <= cy + 14) {
         this.onClose();
         return true;
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   @Override
   protected void drawBox(GuiGraphics g, int x, int y) {
      g.fillGradient(x - 2, y - 2, x + 340 + 2, y + 210 + 2, 819243075, 819243075);
      g.fillGradient(x, y, x + 340, y + 210, -15921907, -15921907);
      g.fillGradient(x + 1, y + 1, x + 340 - 1, y + 2, 1090519039, 553648127);
   }

   @Override
   protected void drawHeader(GuiGraphics g, int bx, int by, int mx, int my) {
      g.fillGradient(bx + 1, by + 1, bx + 340 - 1, by + 28, -14935012, -14935012);
      g.fillGradient(bx + 8, by + 28 - 1, bx + 340 - 8, by + 28, -9744622, -9744622);
      g.drawCenteredString(this.font, this.getTitle(), bx + 170, by + 9, -2840509);
      int cx = bx + 340 - 14 - 8;
      int cy = by + 7;
      g.drawCenteredString(this.font, Component.literal("✕"), cx + 7, cy + 4, mx >= cx && mx <= cx + 14 && my >= cy && my <= cy + 14 ? -2840509 : -6252408);
   }

   private void drawSongInfo(GuiGraphics g, int bx, int by, ModernTurntableBlockEntity t) {
      int iy = by + 28 + 8;
      int cx = bx + 170;
      String song = t != null && !t.getSongName().isBlank() ? t.getSongName() : "未选择唱片";
      String displayName = truncateText(song, 300, this.font);
      int songColor = t != null && t.isPlaying() ? -2840509 : -2041656;
      g.drawCenteredString(this.font, Component.literal(displayName), cx, iy, songColor);
      String fmtLabel = this.cachedFormatLabel;
      if (fmtLabel.isEmpty() && t != null && t.hasDisc()) {
         fmtLabel = "CD 唱片";
      }

      if (!fmtLabel.isEmpty()) {
         g.drawCenteredString(this.font, Component.literal(fmtLabel), cx, iy + 14, -6252408);
      }
   }

   private void drawVisualizer(GuiGraphics g, int bx, int by, float pt) {
      int baseY = by + 28 + 90;
      int leftX = bx + 16;
      int tw = 308;
      float barW = tw / 26.0F;
      float gap = 2.0F;
      float animTime = (this.tickCounter + pt) * 0.05F;

      for (int i = 0; i < 26; i++) {
         float shimmer = (float)Math.sin(animTime * this.barFreqs[i] * Math.PI * 0.7 + this.barPhases[i]) * 0.12F;
         float raw = Math.max(0.03F, this.barLevels[i] * 0.88F + this.energy * 0.12F + shimmer);
         float h = Math.max(2.0F, Math.min(1.0F, raw + this.energy * 0.35F) * 24.0F);
         int bx2 = leftX + (int)(i * barW + gap);
         int bw = Math.max(1, (int)barW - (int)gap);
         int top = baseY - (int)h;
         float t = i / 26.0F;
         g.fillGradient(bx2, top, bx2 + bw, baseY, lerpColor(-9744622, -2840509, t), -9744622);
      }
   }

   private void drawProgressOverlay(GuiGraphics g, int bx, int by) {
      if (this.progressSlider != null) {
         int x = this.progressSlider.getX();
         int y = this.progressSlider.getY();
         int w = this.progressSlider.getWidth();
         int h = this.progressSlider.getHeight();
         double val = this.progressSlider.getSliderValue();
         g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, -15921907, -15921907);
         int pad = 4;
         int trackY = y + h / 2 - 1;
         int trackH = 3;
         int trackLeft = x + pad;
         int trackW = w - pad * 2;
         g.fillGradient(trackLeft, trackY, trackLeft + trackW, trackY + trackH, -15066598, -15066598);
         int fillW = (int)(val * trackW);
         if (fillW > 0) {
            g.fillGradient(trackLeft, trackY, trackLeft + fillW, trackY + trackH, -9744622, -2840509);
         }

         int hx = trackLeft + fillW;
         int hr = !this.progressSlider.isHoveredOrFocused() && !this.progressSlider.isUserDragging() ? 4 : 5;
         int hc = !this.progressSlider.isHoveredOrFocused() && !this.progressSlider.isUserDragging() ? -2041656 : -2840509;
         g.fillGradient(hx - hr, trackY - hr + 1, hx + hr, trackY + trackH + hr - 1, hc, hc);
         g.fillGradient(hx - 2, trackY - 1, hx + 2, trackY + trackH + 1, -15921907, -15921907);
         String label = this.progressSlider.getMessage().getString();
         int lw = this.font.width(label);
         g.drawString(this.font, Component.literal(label), x + (w - lw) / 2, y + h + 1, -6252408);
      }
   }

   private void drawVolumeOverlay(GuiGraphics g, int bx, int by) {
      if (this.volumeSlider != null) {
         int x = this.volumeSlider.getX();
         int y = this.volumeSlider.getY();
         int w = this.volumeSlider.getWidth();
         int h = this.volumeSlider.getHeight();
         double val = this.volumeSlider.getSliderValue();
         g.fillGradient(x - 1, y - 1, x + w + 1, y + h + 1, -15921907, -15921907);
         int pad = 4;
         int trackY = y + h / 2 - 2;
         int trackH = 4;
         int trackLeft = x + pad;
         int trackW = w - pad * 2;
         g.fillGradient(trackLeft, trackY, trackLeft + trackW, trackY + trackH, -15066598, -15066598);
         int fillW = (int)(val * trackW);
         if (fillW > 0) {
            g.fillGradient(trackLeft, trackY, trackLeft + fillW, trackY + trackH, -9744622, -2840509);
         }

         int hx = trackLeft + fillW;
         int hc = this.volumeSlider.isHoveredOrFocused() ? -2840509 : -2041656;
         g.fillGradient(hx - 3, trackY - 2, hx + 3, trackY + trackH + 2, hc, this.volumeSlider.isHoveredOrFocused() ? -2840509 : -6252408);
      }
   }

   private void drawStatusDot(GuiGraphics g, int bx, int by, ModernTurntableBlockEntity t) {
      int sy = by + 210 - 10;
      boolean playing = t != null && t.isPlaying();
      float dotPulse = playing ? 0.7F + (float)Math.sin(this.tickCounter * 0.15F) * 0.3F : 1.0F;
      int dotColor = playing ? -2840509 : -12303292;
      int dc = (int)(dotPulse * 255.0F) << 24 | dotColor & 16777215;
      g.fillGradient(bx + 16, sy - 3, bx + 16 + 6, sy + 3, dc, dc);
   }

   private void updateVisualizerLevels(boolean playing, float audioLevel) {
      for (int i = 0; i < 25; i++) {
         this.barLevels[i] = this.barLevels[i + 1] * 0.94F;
      }

      float pulse = playing ? Math.max(0.0F, Math.min(1.0F, audioLevel)) : 0.0F;
      float shaped = (float)Math.sqrt(pulse);
      float motion = 0.82F + 0.18F * (float)Math.sin(this.tickCounter * 0.41F + this.barPhases[this.tickCounter % 26]);
      this.barLevels[25] = Math.max(0.02F, shaped * motion);
      if (!playing || pulse <= 0.001F) {
         for (int i = 0; i < 26; i++) {
            this.barLevels[i] = this.barLevels[i] * 0.9F;
         }
      }
   }

   private void refreshWidgets() {
      ModernTurntableBlockEntity t = this.turntable();
      if (t == null) {
         if (this.progressSlider != null) {
            this.progressSlider.setProgress(0.0);
         }
      } else {
         boolean hasPlayback = t.hasPlaybackData();
         boolean playing = t.isPlaying();
         int dur = t.getDurationSeconds();
         long elapsed = this.currentElapsedMillis();
         double progress = dur <= 0 ? 0.0 : elapsed / (dur * 1000.0);
         if (this.progressSlider != null && !this.progressSlider.isUserDragging()) {
            this.progressSlider.setProgress(progress);
         }

         if (this.replayButton != null) {
            this.replayButton.active = t.hasDisc();
         }

         if (this.playPauseButton != null) {
            this.playPauseButton.active = playing || hasPlayback || t.hasDisc();
            this.playPauseButton.setMessage(Component.literal(playing ? "⏸ 暂停" : "▶ 播放"));
         }

         if (this.repeatOneButton != null) {
            this.repeatOneButton.active = t.hasDisc() || hasPlayback;
            this.repeatOneButton.setMessage(Component.literal(t.isRepeatOne() ? "\ud83d\udd02 循环中" : "\ud83d\udd01 单曲"));
         }

         if (this.redstoneModeButton != null) {
            this.redstoneModeButton.setMessage(Component.literal("红石：" + t.getRedstoneMode().displayName()));
         }

         if (this.extractionModeButton != null) {
            this.extractionModeButton.setMessage(Component.literal("提取：" + t.getExtractionMode().displayName()));
         }

         if (this.volumeSlider != null) {
            if (!this.volumeSlider.isUserDragging()) {
               this.volumeSlider.setGain(t.getVolume());
            }

            Minecraft mc = Minecraft.getInstance();
            this.volumeSlider.active = mc.player != null && mc.player.mayBuild();
         }

         if (this.progressSlider != null) {
            this.progressSlider.active = hasPlayback && dur > 0;
         }
      }
   }

   private void onPlayPause() {
      ModernTurntableBlockEntity t = this.turntable();
      if (t != null) {
         this.send(t.isPlaying() ? ModernTurntableControlPacket.Action.PAUSE : ModernTurntableControlPacket.Action.START, this.currentSliderMillis());
      }
   }

   private void sendAction(ModernTurntableControlPacket.Action action) {
      this.send(action, this.currentSliderMillis());
   }

   private void send(ModernTurntableControlPacket.Action action, long targetMillis) {
      if (Minecraft.getInstance().getConnection() != null) {
         PacketDistributor.sendToServer(new ModernTurntableControlPacket(this.blockPos, action, Math.max(0L, targetMillis)), new CustomPacketPayload[0]);
      }
   }

   private ModernTurntableBlockEntity turntable() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.level == null) {
         return null;
      } else {
         return mc.level.getBlockEntity(this.blockPos) instanceof ModernTurntableBlockEntity t ? t : null;
      }
   }

   private long currentElapsedMillis() {
      ModernTurntableBlockEntity t = this.turntable();
      return Minecraft.getInstance().level != null && t != null ? t.getPlaybackElapsedMillis() : 0L;
   }

   private long currentSliderMillis() {
      ModernTurntableBlockEntity t = this.turntable();
      return t != null && t.getDurationSeconds() > 0 && this.progressSlider != null
         ? Math.round(this.progressSlider.getSliderValue() * t.getDurationSeconds() * 1000.0)
         : this.currentElapsedMillis();
   }

   private static String resolveFormatLabel() {
      String codec = BiliPlaybackDiagnostics.currentCodecSummary();
      if (codec == null || codec.equals("unknown / unknown") || codec.contains("resolving")) {
         return "";
      } else {
         return !codec.contains("unknown") ? formatCodec(codec) : getFormatLabel();
      }
   }

   private static String getFormatLabel() {
      try {
         List<String> diag = BiliPlaybackDiagnostics.describeCurrentPlayback();
         if (diag instanceof List && !diag.isEmpty()) {
            for (Object line : diag) {
               String s = line.toString();
               if (s.startsWith("容器/编码: ")) {
                  String codec = s.substring(s.indexOf(": ") + 2);
                  if (!codec.equals("unknown") && !codec.equals("resolving")) {
                     return formatCodec(codec);
                  }
               }
            }
         }
      } catch (Exception var6) {
      }

      return "";
   }

   private static String formatCodec(String raw) {
      String[] parts = raw.split(" / ");
      String codec = parts.length > 1 ? parts[1].trim().toLowerCase() : raw.trim().toLowerCase();

      return switch (codec) {
         case "flac" -> "♫ FLAC 无损";
         case "ec-3" -> "♪ Dolby Digital Plus";
         case "aac" -> "♫ AAC 高音质";
         default -> raw.contains("/") ? raw.substring(raw.lastIndexOf(47) + 1).trim().toUpperCase() : raw;
      };
   }

   private static String formatTime(long millis) {
      long s = Math.max(0L, millis / 1000L);
      return s / 60L + ":" + (s % 60L < 10L ? "0" : "") + s % 60L;
   }

   private static String truncateText(String text, int maxW, Font font) {
      if (font.width(text) <= maxW) {
         return text;
      } else {
         String ellipsis = "...";
         int ew = font.width(ellipsis);
         StringBuilder sb = new StringBuilder();
         int cw = 0;

         for (char c : text.toCharArray()) {
            if (cw + font.width(String.valueOf(c)) + ew > maxW) {
               break;
            }

            sb.append(c);
            cw += font.width(String.valueOf(c));
         }

         return sb.append(ellipsis).toString();
      }
   }

   private static int lerpColor(int c1, int c2, float t) {
      t = Math.max(0.0F, Math.min(1.0F, t));
      return (int)((c1 >> 24 & 0xFF) + ((c2 >> 24 & 0xFF) - (c1 >> 24 & 0xFF)) * t) << 24
         | (int)((c1 >> 16 & 0xFF) + ((c2 >> 16 & 0xFF) - (c1 >> 16 & 0xFF)) * t) << 16
         | (int)((c1 >> 8 & 0xFF) + ((c2 >> 8 & 0xFF) - (c1 >> 8 & 0xFF)) * t) << 8
         | (int)((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
   }

   private final class ProgressSlider extends AbstractSliderButton {
      private boolean userDragging;

      ProgressSlider(int x, int y, int w, int h) {
         super(x, y, w, h, Component.literal(""), 0.0);
         this.updateMessage();
      }

      boolean isUserDragging() {
         return this.userDragging;
      }

      double getSliderValue() {
         return this.value;
      }

      void setProgress(double p) {
         this.value = Math.max(0.0, Math.min(1.0, p));
         this.updateMessage();
      }

      public void onClick(double mouseX, double mouseY) {
         this.userDragging = true;
         super.onClick(mouseX, mouseY);
      }

      public void onRelease(double mouseX, double mouseY) {
         super.onRelease(mouseX, mouseY);
         this.userDragging = false;
         ModernTurntableBlockEntity t = ModernTurntableScreen.this.turntable();
         if (t != null && t.getDurationSeconds() > 0) {
            ModernTurntableScreen.this.send(ModernTurntableControlPacket.Action.SEEK, Math.round(this.value * t.getDurationSeconds() * 1000.0));
         }
      }

      protected void updateMessage() {
         ModernTurntableBlockEntity t = ModernTurntableScreen.this.turntable();
         long dur = t != null ? t.getDurationSeconds() * 1000L : 0L;
         long val = Math.round(this.value * dur);
         this.setMessage(Component.literal(ModernTurntableScreen.formatTime(val) + " / " + ModernTurntableScreen.formatTime(dur)));
      }

      protected void applyValue() {
         this.updateMessage();
      }
   }

   private final class VolumeSlider extends AbstractSliderButton {
      private boolean userDragging;

      VolumeSlider(int x, int y, int w, int h) {
         super(x, y, w, h, Component.literal(""), 1.0);
         this.updateMessage();
      }

      boolean isUserDragging() {
         return this.userDragging;
      }

      void setGain(float gain) {
         this.value = gainToSlider(gain);
         this.updateMessage();
      }

      public void onClick(double mouseX, double mouseY) {
         this.userDragging = true;
         super.onClick(mouseX, mouseY);
      }

      public void onRelease(double mouseX, double mouseY) {
         super.onRelease(mouseX, mouseY);
         this.userDragging = false;
         ModernTurntableScreen.this.send(ModernTurntableControlPacket.Action.SET_VOLUME, (long)Math.round(sliderToGain((float)this.value) * 1000.0F));
      }

      protected void updateMessage() {
         this.setMessage(Component.literal((int)(this.value * 100.0) + "%"));
      }

      protected void applyValue() {
         this.updateMessage();
      }

      double getSliderValue() {
         return this.value;
      }

      private static float sliderToGain(float slider) {
         return slider * slider;
      }

      private static float gainToSlider(float gain) {
         return (float)Math.sqrt(Math.max(0.0F, gain));
      }
   }
}
