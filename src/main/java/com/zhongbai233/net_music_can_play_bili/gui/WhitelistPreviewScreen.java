package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.MP4ClientMediaSync;
import com.zhongbai233.net_music_can_play_bili.client.audio.AudioDurationProbe;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaAudioRouting;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaTimelineView;
import com.zhongbai233.net_music_can_play_bili.gui.core.WhitelistReviewSelection;
import com.zhongbai233.net_music_can_play_bili.media.stream.MediaNetworkFailureClassifier;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewMutationResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortGuiTextures;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.CancellableTaskFuture;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.MediaIoExecutor;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class WhitelistPreviewScreen extends Screen {
   private static final int PREFERRED_BOX_W = 460;
   private static final int PREFERRED_BOX_H = 364;
   private static final int MIN_BOX_W = 300;
   private static final int MIN_BOX_H = 220;
   private static final int HEADER_H = 28;
   private static final int MAX_VIDEO_W = 408;
   private static final int MAX_VIDEO_H = 230;
   private static final int VIDEO_TOP = 52;
   private static final int PROGRESS_H = 8;
   private static final int CLOSE_SIZE = 14;
   private static final int PREVIEW_QUALITY = 32;
   private static final long DELETE_TIMEOUT_MILLIS = 10000L;
   private static final long PREVIEW_TIMEOUT_MILLIS = 10000L;
   private WhitelistPreviewPacket payload;
   private float previewProgress;
   private long probedDurationMillis;
   private long pausedAtMillis = -1L;
   private boolean locallyPaused;
   private boolean scrubbing;
   private boolean closeHovered;
   private String durationProbeKey = "";
   private CancellableTaskFuture<OptionalLong> durationProbeTask;
   private String resolvingVideoKey = "";
   private boolean videoResolveFailed;
   private boolean videoResolveNetworkFailure;
   private boolean promptingDelete;
   private boolean deletionPending;
   private EditBox deleteNoteField;
   private BlackGoldButton deleteSubmitButton;
   private String deleteNoteDraft = "";
   private String deleteTargetId = "";
   private String deleteExpectedAddedAt = "";
   private String deleteStatus = "";
   private long deletionRequestStartedAt;
   private long deletionRequestId;
   private String pendingPreviewTarget = "";
   private long pendingPreviewRequestId;
   private long previewRequestStartedAt;
   private String previewRequestStatus = "";

   public WhitelistPreviewScreen(WhitelistPreviewPacket payload) {
      super(Component.literal("白名单视频预览"));
      this.payload = payload;
      this.previewProgress = progressFrom(payload);
      this.beginDurationProbe(payload);
   }

   public static void openOrUpdate(WhitelistPreviewPacket payload) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen instanceof WhitelistPreviewScreen screen) {
         if (screen.acceptPreviewResponse(payload)) {
            screen.update(payload);
         }
      } else if (minecraft.screen instanceof WhitelistReviewScreen review && review.acceptPreviewResponse(payload)) {
         minecraft.setScreen(new WhitelistPreviewScreen(payload));
      }
   }

   private void update(WhitelistPreviewPacket next) {
      this.cancelDurationProbe();
      this.stopCurrentPlayback();
      this.payload = next;
      this.probedDurationMillis = 0L;
      this.durationProbeKey = "";
      this.previewProgress = progressFrom(next);
      this.pausedAtMillis = -1L;
      this.locallyPaused = !next.playing();
      this.resolvingVideoKey = "";
      this.videoResolveFailed = false;
      this.videoResolveNetworkFailure = false;
      WhitelistReviewPacket.Entry promptedEntry = this.currentEntry();
      if (this.promptingDelete
         && (promptedEntry == null || !promptedEntry.id().equals(this.deleteTargetId) || !Objects.equals(promptedEntry.addedAt(), this.deleteExpectedAddedAt))) {
         this.clearDeletePromptState();
      }

      this.beginDurationProbe(next);
      this.startPlayback(next);
      this.rebuildButtons();
   }

   protected void init() {
      this.beginDurationProbe(this.payload);
      this.rebuildButtons();
      this.startPlayback(this.payload);
   }

   private void rebuildButtons() {
      if (this.deleteNoteField != null) {
         this.deleteNoteDraft = this.deleteNoteField.getValue();
      }

      this.clearWidgets();
      this.deleteNoteField = null;
      this.deleteSubmitButton = null;
      int bx = this.boxX();
      int by = this.boxY();
      if (this.promptingDelete) {
         int fieldX = bx + 14;
         int submitWidth = 52;
         int cancelWidth = 40;
         int gap = 6;
         int fieldWidth = this.boxWidth() - 28 - submitWidth - cancelWidth - gap * 2;
         this.deleteNoteField = new EditBox(this.font, fieldX, this.controlY(by), fieldWidth, 20, Component.literal("移除备注"));
         this.deleteNoteField.setMaxLength(256);
         this.deleteNoteField.setValue(this.deleteNoteDraft);
         this.deleteNoteField.setHint(Component.literal("移除备注（必填）"));
         this.deleteNoteField.setEditable(!this.deletionPending);
         this.addRenderableWidget(this.deleteNoteField);
         int submitX = fieldX + fieldWidth + gap;
         this.deleteSubmitButton = new BlackGoldButton(
            submitX, this.controlY(by), submitWidth, 20, Component.literal(this.deletionPending ? "处理中" : "提交"), button -> this.confirmDelete(), -2840509
         );
         this.deleteSubmitButton.active = this.canSubmitDelete();
         this.addRenderableWidget(this.deleteSubmitButton);
         BlackGoldButton cancel = new BlackGoldButton(
            submitX + submitWidth + gap, this.controlY(by), cancelWidth, 20, Component.literal("取消"), button -> this.cancelDelete(), -9744622
         );
         cancel.active = !this.deletionPending;
         this.addRenderableWidget(cancel);
         this.deleteNoteField.setResponder(value -> {
            this.deleteNoteDraft = value;
            this.deleteStatus = "";
            if (!this.deletionPending) {
               this.deletionRequestId = 0L;
            }

            if (this.deleteSubmitButton != null) {
               this.deleteSubmitButton.active = this.canSubmitDelete();
            }
         });
         if (!this.deletionPending) {
            this.setInitialFocus(this.deleteNoteField);
         }
      } else {
         int gap = 6;
         int buttonWidth = Math.min(76, (this.boxWidth() - 28 - gap * 3) / 4);
         int buttonX = bx + (this.boxWidth() - (buttonWidth * 4 + gap * 3)) / 2;
         boolean navigationEnabled = this.pendingPreviewRequestId <= 0L;
         BlackGoldButton previous = new BlackGoldButton(
            buttonX, this.controlY(by), buttonWidth, 20, Component.literal("上一个"), button -> this.previewSibling(-1), -2840509
         );
         previous.active = navigationEnabled && this.siblingId(-1) != null;
         this.addRenderableWidget(previous);
         buttonX += buttonWidth + gap;
         BlackGoldButton pause = new BlackGoldButton(
            buttonX, this.controlY(by), buttonWidth, 20, Component.literal(this.locallyPaused ? "继续" : "暂停"), button -> this.togglePause(), -2840509
         );
         pause.active = navigationEnabled;
         this.addRenderableWidget(pause);
         buttonX += buttonWidth + gap;
         BlackGoldButton next = new BlackGoldButton(
            buttonX, this.controlY(by), buttonWidth, 20, Component.literal("下一个"), button -> this.previewSibling(1), -2840509
         );
         next.active = navigationEnabled && this.siblingId(1) != null;
         this.addRenderableWidget(next);
         buttonX += buttonWidth + gap;
         BlackGoldButton delete = new BlackGoldButton(
            buttonX, this.controlY(by), buttonWidth, 20, Component.literal("删除"), button -> this.requestDelete(), -38037
         );
         delete.active = navigationEnabled && this.currentEntry() != null;
         this.addRenderableWidget(delete);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      if (!this.deletionPending) {
         if (this.promptingDelete) {
            this.cancelDelete();
         } else {
            this.cancelDurationProbe();
            this.stopCurrentPlayback();
            this.returnToReviewMenu();
         }
      }
   }

   public void removed() {
      this.cancelDurationProbe();
      this.clearPendingPreviewResponse();
      this.stopCurrentPlayback();
   }

   public void tick() {
      super.tick();
      if (this.deletionPending && System.currentTimeMillis() - this.deletionRequestStartedAt >= 10000L) {
         this.deletionPending = false;
         this.deletionRequestStartedAt = 0L;
         this.deleteStatus = "未收到服务器确认，备注已保留，可重试";
         WhitelistReviewScreen.cancelPreparedPreviewRemoval();
         this.rebuildButtons();
      }

      if (this.pendingPreviewRequestId > 0L && System.currentTimeMillis() - this.previewRequestStartedAt >= 10000L) {
         this.clearPendingPreviewResponse();
         this.previewRequestStatus = "预览请求超时，仍保留当前页面，可重试";
         this.rebuildButtons();
      }

      if (!this.scrubbing) {
         float live = this.liveProgress();
         if (live >= 0.0F) {
            this.previewProgress = live;
         }
      }
   }

   public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
      BlackGoldUi.drawBackground(g, this.width, this.height);
   }

   public void render(GuiGraphics g, int mx, int my, float pt) {
      this.renderBackground(g, mx, my, pt);
      int bx = this.boxX();
      int by = this.boxY();
      BlackGoldUi.drawPanel(g, bx, by, this.boxWidth(), this.boxHeight());
      this.drawHeader(g, bx, by, mx, my);
      this.drawVideo(g, bx, by);
      this.drawProgress(g, bx, by, mx, my);
      this.drawFooter(g, bx, by);

      for (Renderable renderable : this.renderables) {
         renderable.render(g, mx, my, pt);
      }
   }

   private void drawHeader(GuiGraphics g, int bx, int by, int mx, int my) {
      BlackGoldUi.drawHeader(g, this.font, this.getTitle(), bx, by, this.boxWidth(), 28);
      int cx = bx + this.boxWidth() - 14 - 8;
      int cy = by + 7;
      this.closeHovered = mx >= cx && mx <= cx + 14 && my >= cy && my <= cy + 14;
      g.drawCenteredString(this.font, Component.literal("✕"), cx + 7, cy + 4, this.closeHovered ? -2840509 : -6252408);
      g.drawString(
         this.font, Component.literal(BlackGoldUi.ellipsize(this.font, this.payload.title(), this.boxWidth() - 54)), bx + 16, by + 34, -6252408, false
      );
   }

   private void drawVideo(GuiGraphics g, int bx, int by) {
      String sessionId = WhitelistPreviewPacket.sessionId(this.payload.previewId(), this.payload.elapsedMillis());
      int x = this.videoX(bx);
      int y = this.videoY(by);
      g.fillGradient(x - 2, y - 2, x + this.videoWidth() + 2, y + this.videoHeight() + 2, -9744622, -9744622);
      g.fillGradient(x, y, x + this.videoWidth(), y + this.videoHeight(), -16448251, -15724528);
      if (!this.hasVideo()) {
         g.drawCenteredString(this.font, Component.literal("纯音频预览"), x + this.videoWidth() / 2, y + this.videoHeight() / 2 - 14, -2840509);
         g.drawCenteredString(this.font, Component.literal("此条目仅播放音频"), x + this.videoWidth() / 2, y + this.videoHeight() / 2 + 2, -10463160);
      } else {
         VideoBillboardPreview.pumpPreviewFrame(sessionId);
         VideoBillboardState.ProjectorFrameSnapshot frame = VideoBillboardPreview.currentPreviewFrame(sessionId);
         if (frame.hasFrame() && !frame.yuv() && frame.rgbaTexture() != null) {
            PortGuiTextures.blitRegion(g, frame.rgbaTexture(), x, y, x + this.videoWidth(), y + this.videoHeight(), 0.0F, 1.0F, 0.0F, 1.0F);
            if (VideoBillboardPreview.hasNetworkFailure(sessionId)) {
               g.drawCenteredString(this.font, Component.literal("点击画面重新连接"), x + this.videoWidth() / 2, y + this.videoHeight() - 20, -11930);
            }
         } else if (this.videoResolveFailed) {
            g.drawCenteredString(
               this.font,
               Component.literal(this.videoResolveNetworkFailure ? "视频网络连接失败" : "视频信息解析失败"),
               x + this.videoWidth() / 2,
               y + this.videoHeight() / 2 - 14,
               -30088
            );
            g.drawCenteredString(this.font, Component.literal("点击画面重新连接"), x + this.videoWidth() / 2, y + this.videoHeight() / 2 + 4, -11930);
         } else {
            String text = frame.hasFrame() && frame.yuv() ? "正在准备视频画面..." : "正在加载视频画面...";
            g.drawCenteredString(this.font, Component.literal(text), x + this.videoWidth() / 2, y + this.videoHeight() / 2 - 6, -10463160);
         }
      }
   }

   private void drawProgress(GuiGraphics g, int bx, int by, int mx, int my) {
      int x = this.progressX(bx);
      int y = this.progressY(by);
      int w = this.progressW();
      boolean hovered = mx >= x && mx <= x + w && my >= y - 5 && my <= y + 8 + 5;
      g.fillGradient(x, y, x + w, y + 8, -14208956, -14208956);
      float renderedProgress = this.scrubbing ? this.previewProgress : this.displayProgress();
      int filled = Math.round(w * clamp01(renderedProgress));
      g.fillGradient(x, y, x + filled, y + 8, -2840509, -2840509);
      int knob = x + filled;
      int radius = !hovered && !this.scrubbing ? 4 : 5;
      g.fillGradient(knob - radius, y + 4 - radius, knob + radius, y + 4 + radius, -1522581, -1522581);
      String totalText = this.totalMillis() > 0L ? timeText(this.totalMillis()) : "--:--";
      g.drawString(this.font, Component.literal(timeText(this.displayMillis()) + " / " + totalText), x, y + 14, -6252408, false);
   }

   private void drawFooter(GuiGraphics g, int bx, int by) {
      String footer;
      if (this.deletionPending) {
         footer = "正在等待服务器确认删除……";
      } else if (!this.deleteStatus.isBlank()) {
         footer = this.deleteStatus;
      } else {
         footer = this.promptingDelete
            ? "正在删除 " + this.deleteTargetId + "；请输入必填备注"
            : (!this.previewRequestStatus.isBlank() ? this.previewRequestStatus : "拖动进度条，或用左右键调整播放位置");
      }

      g.drawString(
         this.font, Component.literal(BlackGoldUi.ellipsize(this.font, footer, this.boxWidth() - 32)), bx + 16, by + this.boxHeight() - 20, -10463160, false
      );
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int bx = this.boxX();
      int by = this.boxY();
      int cx = bx + this.boxWidth() - 14 - 8;
      int cy = by + 7;
      if (mouseX >= cx && mouseX <= cx + 14 && mouseY >= cy && mouseY <= cy + 14) {
         this.onClose();
         return true;
      } else if (this.promptingDelete) {
         return super.mouseClicked(mouseX, mouseY, button);
      } else if (this.pendingPreviewRequestId > 0L) {
         return super.mouseClicked(mouseX, mouseY, button);
      } else {
         String sessionId = WhitelistPreviewPacket.sessionId(this.payload.previewId(), this.payload.elapsedMillis());
         if (this.hitVideo(bx, by, mouseX, mouseY) && this.videoResolveFailed) {
            this.videoResolveFailed = false;
            this.videoResolveNetworkFailure = false;
            this.resolvingVideoKey = "";
            this.startPreviewVideo(this.payload, sessionId);
            return true;
         } else if (this.hitVideo(bx, by, mouseX, mouseY) && VideoBillboardPreview.retryNetworkFailure(sessionId)) {
            return true;
         } else if (this.hitProgress(bx, by, mouseX, mouseY)) {
            this.scrubbing = true;
            this.updateScrub(bx, mouseX);
            return true;
         } else {
            return super.mouseClicked(mouseX, mouseY, button);
         }
      }
   }

   private boolean hitVideo(int bx, int by, double mouseX, double mouseY) {
      int x = this.videoX(bx);
      int y = this.videoY(by);
      return mouseX >= x && mouseX <= x + this.videoWidth() && mouseY >= y && mouseY <= y + this.videoHeight();
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.scrubbing) {
         this.updateScrub(this.boxX(), mouseX);
         return true;
      } else {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.scrubbing) {
         this.scrubbing = false;
         this.requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK, this.payload.rawUrl(), this.currentMillis());
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (!this.promptingDelete) {
         if (this.pendingPreviewRequestId > 0L) {
            if (keyCode == 256) {
               this.onClose();
               return true;
            } else {
               return super.keyPressed(keyCode, scanCode, modifiers);
            }
         } else {
            switch (keyCode) {
               case 32:
                  this.togglePause();
                  break;
               case 262:
                  this.seekBy(5000L);
                  break;
               case 263:
                  this.seekBy(-5000L);
                  break;
               case 264:
                  this.previewSibling(1);
                  break;
               case 265:
                  this.previewSibling(-1);
                  break;
               default:
                  return super.keyPressed(keyCode, scanCode, modifiers);
            }

            return true;
         }
      } else if (this.deletionPending) {
         return keyCode == 256 || super.keyPressed(keyCode, scanCode, modifiers);
      } else if (keyCode == 256) {
         this.cancelDelete();
         return true;
      } else if ((keyCode == 257 || keyCode == 335) && this.getFocused() == this.deleteNoteField) {
         this.confirmDelete();
         return true;
      } else {
         return super.keyPressed(keyCode, scanCode, modifiers);
      }
   }

   private void startPlayback(WhitelistPreviewPacket packet) {
      if (packet != null) {
         if (!this.locallyPaused && packet.playing()) {
            ClientMediaAudioRouting.registerLocalPrivateSource(packet.previewId());
            MP4ClientMediaSync.handleSync(this.toLocalAudioSync(packet));
            if (hasVideo(packet)) {
               String sessionId = WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis());
               this.startPreviewVideo(packet, sessionId);
            }
         }
      }
   }

   private void startPreviewVideo(WhitelistPreviewPacket packet, String sessionId) {
      this.videoResolveFailed = false;
      this.videoResolveNetworkFailure = false;
      if (BiliVideoStreamResolver.isStoredVideoSelection(packet.videoUrl())) {
         this.resolveAndStartBiliPreviewVideo(packet, sessionId);
      } else {
         VideoBillboardPreview.startRgbaPreviewAt(
            packet.videoUrl(),
            packet.videoWidth(),
            packet.videoHeight(),
            packet.fps(),
            packet.codecId(),
            sessionId,
            packet.elapsedMillis(),
            this.totalMillis(),
            true,
            null,
            packet.previewId()
         );
      }
   }

   private void resolveAndStartBiliPreviewVideo(WhitelistPreviewPacket packet, String sessionId) {
      String key = sessionId + ":" + packet.videoUrl();
      if (!key.equals(this.resolvingVideoKey)) {
         this.resolvingVideoKey = key;
         MediaIoExecutor.<BiliVideoStreamResolver.ResolvedVideoStream>supply(() -> {
               try {
                  return BiliVideoStreamResolver.resolve(packet.videoUrl(), 32);
               } catch (Exception var2x) {
                  throw new IllegalStateException(var2x);
               }
            })
            .whenComplete(
               (stream, error) -> Minecraft.getInstance()
                  .execute(
                     () -> {
                        if (Minecraft.getInstance().screen == this
                           && this.payload != null
                           && packet.previewId().equals(this.payload.previewId())
                           && sessionId.equals(WhitelistPreviewPacket.sessionId(this.payload.previewId(), this.payload.elapsedMillis()))) {
                           this.resolvingVideoKey = "";
                           if (error == null && stream != null) {
                              VideoBillboardPreview.startRgbaPreviewAt(
                                 stream.url(),
                                 stream.sourceWidth(),
                                 stream.sourceHeight(),
                                 stream.fps(),
                                 stream.codecId(),
                                 sessionId,
                                 packet.elapsedMillis(),
                                 this.totalMillis(),
                                 true,
                                 null,
                                 packet.previewId()
                              );
                           } else {
                              this.videoResolveFailed = true;
                              this.videoResolveNetworkFailure = error != null && MediaNetworkFailureClassifier.isNetworkFailure(error);
                           }
                        }
                     }
                  )
            );
      }
   }

   private void returnToReviewMenu() {
      WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
      WhitelistReviewPacket.Entry current = this.currentEntry();
      WhitelistReviewScreen.resumeFromPreview(last, current == null ? "" : current.id());
   }

   private MP4PlaybackSyncPacket toLocalAudioSync(WhitelistPreviewPacket packet) {
      int playerEntityId = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getId() : -1;
      return new MP4PlaybackSyncPacket(
         packet.previewId(),
         packet.previewId(),
         0,
         playerEntityId,
         0.0,
         0.0,
         0.0,
         packet.playing(),
         0,
         packet.audioUrl(),
         packet.rawUrl(),
         packet.title(),
         packet.durationSeconds(),
         850,
         WhitelistPreviewPacket.sessionId(packet.previewId(), packet.elapsedMillis()),
         packet.elapsedMillis(),
         false
      );
   }

   private void stopCurrentPlayback() {
      UUID previewId = this.payload != null ? this.payload.previewId() : null;
      if (previewId != null) {
         String sessionId = WhitelistPreviewPacket.sessionId(previewId, this.payload.elapsedMillis());
         MP4ClientMediaSync.handleSync(WhitelistPreviewPacket.stopAudio(previewId));
         ClientMediaAudioRouting.unregisterLocalPrivateSource(previewId);
         VideoBillboardPreview.stopIfSession(sessionId);
      }
   }

   private float liveProgress() {
      long duration = this.timelineDurationMillis();
      long elapsed = this.displayMillis();
      return duration > 0L && elapsed >= 0L ? clamp01((float)elapsed / (float)duration) : -1.0F;
   }

   private float displayProgress() {
      long duration = this.timelineDurationMillis();
      return duration <= 0L ? this.previewProgress : clamp01((float)this.displayMillis() / (float)duration);
   }

   private long currentMillis() {
      return Math.round(this.previewProgress * (float)this.totalMillis());
   }

   private long displayMillis() {
      if (this.locallyPaused && this.pausedAtMillis >= 0L) {
         return this.pausedAtMillis;
      } else {
         String sessionId = WhitelistPreviewPacket.sessionId(this.payload.previewId(), this.payload.elapsedMillis());
         return this.timelineView(sessionId).mediaMillis();
      }
   }

   private long timelineDurationMillis() {
      String sessionId = WhitelistPreviewPacket.sessionId(this.payload.previewId(), this.payload.elapsedMillis());
      long total = this.timelineView(sessionId).totalMillis();
      return total > 0L ? total : this.totalMillis();
   }

   private ClientMediaTimelineView timelineView(String sessionId) {
      return ClientMediaTimelineView.forMediaOwner(this.payload.previewId(), sessionId, this.payload.elapsedMillis(), this.totalMillis());
   }

   private long totalMillis() {
      long packetDuration = Math.max(0L, (long)this.payload.durationSeconds()) * 1000L;
      return packetDuration > 0L ? packetDuration : Math.max(0L, this.probedDurationMillis);
   }

   private void beginDurationProbe(WhitelistPreviewPacket packet) {
      if (packet != null && !hasVideo(packet) && packet.durationSeconds() <= 0) {
         String probeUrl = packet.audioUrl() != null && !packet.audioUrl().isBlank() ? packet.audioUrl() : packet.rawUrl();
         if (probeUrl != null && !probeUrl.isBlank()) {
            String key = packet.previewId() + ":" + packet.elapsedMillis() + ":" + probeUrl;
            if (!key.equals(this.durationProbeKey)) {
               this.cancelDurationProbe();
               this.durationProbeKey = key;
               CancellableTaskFuture<OptionalLong> task = AudioDurationProbe.probeMillisAsync(probeUrl);
               this.durationProbeTask = task;
               task.whenComplete((duration, error) -> Minecraft.getInstance().execute(() -> {
                  if (this.durationProbeTask == task) {
                     this.durationProbeTask = null;
                     if (error == null && duration != null && duration.isPresent()) {
                        this.applyProbedDuration(packet.previewId(), key, duration);
                     }
                  }
               }));
            }
         }
      }
   }

   private void cancelDurationProbe() {
      CancellableTaskFuture<OptionalLong> task = this.durationProbeTask;
      this.durationProbeTask = null;
      this.durationProbeKey = "";
      if (task != null) {
         task.cancel(true);
      }
   }

   private void applyProbedDuration(UUID previewId, String key, OptionalLong duration) {
      if (this.payload != null && this.payload.previewId().equals(previewId) && key.equals(this.durationProbeKey)) {
         long millis = duration.orElse(0L);
         if (millis > 0L) {
            this.probedDurationMillis = millis;
            if (!this.scrubbing) {
               this.previewProgress = this.progressForMillis(this.displayMillis());
            }
         }
      }
   }

   private boolean hasVideo() {
      return hasVideo(this.payload);
   }

   private static boolean hasVideo(WhitelistPreviewPacket packet) {
      return packet != null && packet.videoUrl() != null && !packet.videoUrl().isBlank();
   }

   private void updateScrub(int bx, double mouseX) {
      this.previewProgress = clamp01((float)((mouseX - this.progressX(bx)) / this.progressW()));
   }

   private boolean hitProgress(int bx, int by, double mouseX, double mouseY) {
      if (this.totalMillis() <= 0L) {
         return false;
      } else {
         int x = this.progressX(bx);
         int y = this.progressY(by);
         return mouseX >= x && mouseX <= x + this.progressW() && mouseY >= y - 6 && mouseY <= y + 8 + 8;
      }
   }

   private int boxWidth() {
      return Math.min(460, Math.max(300, this.width - 16));
   }

   private int boxHeight() {
      return Math.min(364, Math.max(220, this.height - 16));
   }

   private int boxX() {
      return (this.width - this.boxWidth()) / 2;
   }

   private int boxY() {
      return (this.height - this.boxHeight()) / 2;
   }

   private int videoWidth() {
      int maxWidth = Math.min(408, Math.max(128, this.boxWidth() - 32));
      int maxHeight = Math.min(230, Math.max(72, this.boxHeight() - 134));
      return Math.max(128, Math.min(maxWidth, maxHeight * 16 / 9));
   }

   private int videoHeight() {
      int maxHeight = Math.min(230, Math.max(72, this.boxHeight() - 134));
      return Math.max(72, Math.min(maxHeight, this.videoWidth() * 9 / 16));
   }

   private int videoX(int bx) {
      return bx + (this.boxWidth() - this.videoWidth()) / 2;
   }

   private int videoY(int by) {
      return by + 52;
   }

   private int progressX(int bx) {
      return this.videoX(bx);
   }

   private int progressY(int by) {
      return this.videoY(by) + this.videoHeight() + 10;
   }

   private int controlY(int by) {
      return this.progressY(by) + 26;
   }

   private int progressW() {
      return this.videoWidth();
   }

   private static float progressFrom(WhitelistPreviewPacket payload) {
      long total = Math.max(0L, (long)payload.durationSeconds()) * 1000L;
      return total <= 0L ? 0.0F : clamp01((float)payload.elapsedMillis() / (float)total);
   }

   private static float clamp01(float value) {
      return Math.max(0.0F, Math.min(1.0F, value));
   }

   private static String timeText(long millis) {
      long seconds = Math.max(0L, millis / 1000L);
      return seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L);
   }

   private void togglePause() {
      if (this.locallyPaused) {
         this.locallyPaused = false;
         long resumeMillis = Math.max(0L, this.pausedAtMillis >= 0L ? this.pausedAtMillis : this.displayMillis());
         this.previewProgress = this.progressForMillis(resumeMillis);
         this.requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK, this.payload.rawUrl(), resumeMillis);
         this.rebuildButtons();
      } else {
         this.clearPendingPreviewResponse();
         this.pausedAtMillis = this.displayMillis();
         this.previewProgress = this.progressForMillis(this.pausedAtMillis);
         this.locallyPaused = true;
         this.stopCurrentPlayback();
         this.rebuildButtons();
      }
   }

   private void previewSibling(int direction) {
      String id = this.siblingId(direction);
      if (id != null) {
         this.cancelDurationProbe();
         this.stopCurrentPlayback();
         this.locallyPaused = false;
         this.pausedAtMillis = -1L;
         this.requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW, id, 0L);
      }
   }

   private void seekBy(long deltaMillis) {
      long total = this.totalMillis();
      if (total > 0L) {
         long target = Math.max(0L, Math.min(total, this.displayMillis() + deltaMillis));
         this.previewProgress = this.progressForMillis(target);
         this.requestPreviewResponse(WhitelistReviewActionPacket.Action.PREVIEW_SEEK, this.payload.rawUrl(), target);
      }
   }

   private void requestPreviewResponse(WhitelistReviewActionPacket.Action action, String target, long targetMillis) {
      this.pendingPreviewTarget = target == null ? "" : target;
      this.pendingPreviewRequestId = WhitelistReviewActionPacket.nextClientRequestId();
      this.previewRequestStartedAt = System.currentTimeMillis();
      this.previewRequestStatus = action == WhitelistReviewActionPacket.Action.PREVIEW ? "正在切换预览……" : "正在调整播放位置……";
      this.rebuildButtons();
      PacketDistributor.sendToServer(new WhitelistReviewActionPacket(action, target, targetMillis, this.pendingPreviewRequestId), new CustomPacketPayload[0]);
   }

   private boolean acceptPreviewResponse(WhitelistPreviewPacket next) {
      if (next != null
         && !this.pendingPreviewTarget.isBlank()
         && this.pendingPreviewRequestId > 0L
         && this.pendingPreviewRequestId == next.requestId()
         && WhitelistReviewSelection.matchesPreview(this.pendingPreviewTarget, next.rawUrl())) {
         this.clearPendingPreviewResponse();
         this.previewRequestStatus = "";
         return true;
      } else {
         return false;
      }
   }

   private void clearPendingPreviewResponse() {
      this.pendingPreviewTarget = "";
      this.pendingPreviewRequestId = 0L;
      this.previewRequestStartedAt = 0L;
      this.previewRequestStatus = "";
   }

   private void requestDelete() {
      WhitelistReviewPacket.Entry entry = this.currentEntry();
      if (entry != null) {
         this.clearPendingPreviewResponse();
         this.promptingDelete = true;
         this.deleteTargetId = entry.id();
         this.deleteExpectedAddedAt = entry.addedAt();
         this.deleteNoteDraft = "";
         this.deletionRequestId = 0L;
         this.deleteStatus = "";
         this.rebuildButtons();
      }
   }

   private void cancelDelete() {
      if (!this.deletionPending) {
         WhitelistReviewScreen.cancelPreparedPreviewRemoval();
         this.clearDeletePromptState();
         this.deleteStatus = "";
         this.rebuildButtons();
      }
   }

   private void confirmDelete() {
      WhitelistReviewPacket.Entry entry = this.entryById(this.deleteTargetId);
      if (entry != null && this.matchesCurrentEntry(entry) && Objects.equals(entry.addedAt(), this.deleteExpectedAddedAt)) {
         String note = this.deleteNoteField == null ? "" : this.deleteNoteField.getValue().trim();
         this.deleteNoteDraft = note;
         if (!note.isBlank() && !this.deletionPending) {
            String targetId = entry.id();
            String expectedAddedAt = this.deleteExpectedAddedAt;
            this.deletionPending = true;
            this.deletionRequestStartedAt = System.currentTimeMillis();
            if (this.deletionRequestId <= 0L) {
               this.deletionRequestId = WhitelistReviewActionPacket.nextClientRequestId();
            }

            this.deleteStatus = "";
            WhitelistReviewScreen.preparePreviewRemoval(targetId);
            this.rebuildButtons();
            WhitelistReviewPacket review = WhitelistReviewScreen.lastPayload();
            int entryOffset = review == null ? 0 : review.entryOffset();
            int removalOffset = review == null ? 0 : review.removalOffset();
            PacketDistributor.sendToServer(
               new WhitelistReviewActionPacket(
                  WhitelistReviewActionPacket.Action.REMOVE, targetId, 0L, note, expectedAddedAt, entryOffset, removalOffset, this.deletionRequestId
               ),
               new CustomPacketPayload[0]
            );
         }
      } else {
         this.cancelDelete();
      }
   }

   private boolean canSubmitDelete() {
      if (!this.deletionPending && this.deleteNoteDraft != null && !this.deleteNoteDraft.trim().isEmpty()) {
         WhitelistReviewPacket.Entry entry = this.entryById(this.deleteTargetId);
         return entry != null && this.matchesCurrentEntry(entry) && Objects.equals(entry.addedAt(), this.deleteExpectedAddedAt);
      } else {
         return false;
      }
   }

   void handleMutationResult(WhitelistReviewMutationResultPacket result) {
      if (result != null) {
         if (result.mutation() != WhitelistReviewMutationResultPacket.Mutation.PREVIEW
            && result.mutation() != WhitelistReviewMutationResultPacket.Mutation.PREVIEW_SEEK) {
            if (this.deletionPending
               && result.mutation() == WhitelistReviewMutationResultPacket.Mutation.REMOVE
               && Objects.equals(this.deleteTargetId, result.targetId())
               && this.deletionRequestId == result.requestId()) {
               this.deletionPending = false;
               this.deletionRequestStartedAt = 0L;
               this.deletionRequestId = 0L;
               this.deleteStatus = result.message();
               if (!result.successful()) {
                  WhitelistReviewScreen.cancelPreparedPreviewRemoval();
                  this.rebuildButtons();
               } else {
                  this.clearDeletePromptState();
                  this.cancelDurationProbe();
                  this.stopCurrentPlayback();
                  WhitelistReviewScreen.resumeFromPreview(WhitelistReviewScreen.lastPayload(), "");
               }
            }
         } else {
            if (this.pendingPreviewRequestId > 0L
               && this.pendingPreviewRequestId == result.requestId()
               && Objects.equals(this.pendingPreviewTarget, result.targetId())) {
               this.clearPendingPreviewResponse();
               this.previewRequestStatus = result.message();
               this.rebuildButtons();
            }
         }
      }
   }

   private void clearDeletePromptState() {
      this.promptingDelete = false;
      this.deletionPending = false;
      this.deletionRequestId = 0L;
      this.deleteNoteField = null;
      this.deleteSubmitButton = null;
      this.deleteNoteDraft = "";
      this.deleteTargetId = "";
      this.deleteExpectedAddedAt = "";
   }

   private WhitelistReviewPacket.Entry entryById(String id) {
      WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
      return id != null && !id.isBlank() && last != null && last.entries() != null
         ? last.entries().stream().filter(entry -> id.equals(entry.id())).findFirst().orElse(null)
         : null;
   }

   private String siblingId(int direction) {
      WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
      if (last != null && last.entries() != null && !last.entries().isEmpty() && this.payload != null) {
         int current = -1;

         for (int i = 0; i < last.entries().size(); i++) {
            if (this.matchesCurrentEntry(last.entries().get(i))) {
               current = i;
               break;
            }
         }

         int next = current + direction;
         return current >= 0 && next >= 0 && next < last.entries().size() ? last.entries().get(next).id() : null;
      } else {
         return null;
      }
   }

   private WhitelistReviewPacket.Entry currentEntry() {
      WhitelistReviewPacket last = WhitelistReviewScreen.lastPayload();
      if (last != null && last.entries() != null && this.payload != null) {
         for (WhitelistReviewPacket.Entry entry : last.entries()) {
            if (this.matchesCurrentEntry(entry)) {
               return entry;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private boolean matchesCurrentEntry(WhitelistReviewPacket.Entry entry) {
      return entry != null && this.payload != null && WhitelistReviewSelection.matchesPreview(entry.id(), this.payload.rawUrl());
   }

   private float progressForMillis(long millis) {
      long total = this.totalMillis();
      return total <= 0L ? 0.0F : clamp01((float)millis / (float)total);
   }
}
