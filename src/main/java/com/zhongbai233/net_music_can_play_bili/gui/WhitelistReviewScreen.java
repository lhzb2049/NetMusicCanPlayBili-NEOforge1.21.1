package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.gui.core.WhitelistReviewSelection;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewActionPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewMutationResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

public class WhitelistReviewScreen extends Screen {
   private static WhitelistReviewPacket lastPayload;
   private static WhitelistReviewScreen suspendedForPreview;
   private static final int PREFERRED_BOX_W = 420;
   private static final int PREFERRED_BOX_H = 360;
   private static final int MIN_BOX_W = 300;
   private static final int MIN_BOX_H = 220;
   private static final int HEADER_H = 28;
   private static final int CLOSE_SIZE = 14;
   private static final int ROW_TOP = 70;
   private static final int ROW_HEIGHT = 22;
   private static final int MAX_VISIBLE_ROWS = 7;
   private static final int MIN_DETAIL_HEIGHT = 60;
   private static final int SCROLLBAR_WIDTH = 5;
   private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 18;
   private static final long REQUEST_TIMEOUT_MILLIS = 10000L;
   private List<WhitelistReviewPacket.Entry> entries;
   private List<WhitelistReviewPacket.RemovalRecord> removalRecords;
   private int selectedIndex;
   private String selectedRemovalId = "";
   private int scrollOffset;
   private boolean closeHovered;
   private boolean draggingScrollbar;
   private double scrollbarDragOffsetY;
   private boolean historyTab;
   private WhitelistReviewScreen.PromptMode promptMode = WhitelistReviewScreen.PromptMode.NONE;
   private String promptTargetId = "";
   private String promptExpectedAddedAt = "";
   private String promptDraft = "";
   private String preparedRemovalTargetId = "";
   private String preferredSelectionAfterRemoval = "";
   private String interactionStatus = "";
   private WhitelistReviewScreen.PromptMode pendingMode = WhitelistReviewScreen.PromptMode.NONE;
   private String pendingTargetId = "";
   private long pendingRequestId;
   private String pendingPreviewTargetId = "";
   private long pendingPreviewRequestId;
   private long previewRequestStartedAt;
   private boolean pageRequestPending;
   private long pageRequestId;
   private int pageRequestDirection;
   private long requestStartedAt;
   private EditBox promptField;
   private BlackGoldButton promptSubmitButton;

   public WhitelistReviewScreen(WhitelistReviewPacket payload) {
      this(payload, "");
   }

   public WhitelistReviewScreen(WhitelistReviewPacket payload, String selectedId) {
      super(Component.literal("白名单统一审核"));
      lastPayload = payload;
      this.entries = safeEntries(payload);
      this.removalRecords = safeRemovalRecords(payload);
      this.selectedIndex = this.indexOf(selectedId);
      if (this.selectedIndex < 0 && !this.entries.isEmpty()) {
         this.selectedIndex = 0;
      }

      this.ensureSelectedVisible();
   }

   public static WhitelistReviewPacket lastPayload() {
      return lastPayload;
   }

   public static void open(WhitelistReviewPacket payload) {
      suspendedForPreview = null;
      Minecraft.getInstance().setScreen(new WhitelistReviewScreen(payload));
   }

   public static void openOrUpdate(WhitelistReviewPacket payload) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen instanceof WhitelistReviewScreen screen) {
         suspendedForPreview = null;
         screen.update(payload);
      } else if (payload != null && payload.openScreen()) {
         WhitelistReviewScreen target = suspendedForPreview;
         suspendedForPreview = null;
         if (target == null) {
            target = new WhitelistReviewScreen(payload);
         } else {
            target.update(payload);
         }

         minecraft.setScreen(target);
      } else if (minecraft.screen instanceof WhitelistPreviewScreen && suspendedForPreview != null) {
         suspendedForPreview.update(payload);
      }
   }

   public static void routeMutationResult(WhitelistReviewMutationResultPacket result) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen instanceof WhitelistReviewScreen screen) {
         screen.handleMutationResult(result);
      } else if (minecraft.screen instanceof WhitelistPreviewScreen preview) {
         preview.handleMutationResult(result);
      }
   }

   public static void resumeFromPreview(WhitelistReviewPacket payload, String selectedId) {
      Minecraft minecraft = Minecraft.getInstance();
      WhitelistReviewScreen screen = suspendedForPreview;
      suspendedForPreview = null;
      if (screen == null) {
         minecraft.setScreen(payload == null ? null : new WhitelistReviewScreen(payload, selectedId));
      } else {
         if (payload != null && payload != lastPayload) {
            screen.update(payload);
         }

         screen.selectEntryById(selectedId);
         minecraft.setScreen(screen);
      }
   }

   public static void preparePreviewRemoval(String removedId) {
      if (suspendedForPreview != null) {
         suspendedForPreview.prepareRemovalSelection(removedId);
      }
   }

   public static void cancelPreparedPreviewRemoval() {
      if (suspendedForPreview != null) {
         suspendedForPreview.clearPreparedRemoval();
      }
   }

   public void update(WhitelistReviewPacket payload) {
      int previousSelectedIndex = this.selectedIndex;
      int previousScrollOffset = this.scrollOffset;
      int previousPageOffset = this.currentPageOffset();
      String selectedId = this.selectedEntry() != null ? this.selectedEntry().id() : "";
      String selectedRecordId = this.selectedRemovalRecord() != null ? this.selectedRemovalRecord().recordId() : this.selectedRemovalId;
      lastPayload = payload;
      this.entries = safeEntries(payload);
      int promptedIndex = this.indexOf(this.promptTargetId);
      if (this.pendingMode == WhitelistReviewScreen.PromptMode.NONE
         && this.promptMode != WhitelistReviewScreen.PromptMode.NONE
         && (promptedIndex < 0 || !Objects.equals(this.entries.get(promptedIndex).addedAt(), this.promptExpectedAddedAt))) {
         this.clearPrompt();
      }

      this.removalRecords = safeRemovalRecords(payload);
      List<String> ids = this.historyTab
         ? this.removalRecords.stream().map(record -> record.recordId()).toList()
         : this.entries.stream().map(entry -> entry.id()).toList();
      String desiredId = this.historyTab ? selectedRecordId : selectedId;
      boolean preparedRemovalApplied = !this.historyTab && !this.preparedRemovalTargetId.isBlank() && this.indexOf(this.preparedRemovalTargetId) < 0;
      if (preparedRemovalApplied && !this.preferredSelectionAfterRemoval.isBlank()) {
         desiredId = this.preferredSelectionAfterRemoval;
      }

      int fallbackIndex = previousSelectedIndex;
      int restoredScrollOffset = previousScrollOffset;
      if (this.pageRequestPending && this.pageRequestDirection != 0) {
         desiredId = "";
         fallbackIndex = 0;
         restoredScrollOffset = 0;
      } else if (this.currentPageOffset() < previousPageOffset && WhitelistReviewSelection.indexOf(ids, desiredId) < 0) {
         fallbackIndex = ids.size() - 1;
      }

      WhitelistReviewSelection.RefreshState restored = WhitelistReviewSelection.resolveAfterRefresh(
         ids, desiredId, fallbackIndex, restoredScrollOffset, this.visibleRows()
      );
      this.selectedIndex = restored.selectedIndex();
      this.scrollOffset = restored.scrollOffset();
      this.selectedRemovalId = this.historyTab && this.selectedIndex >= 0 ? this.removalRecords.get(this.selectedIndex).recordId() : selectedRecordId;
      this.pageRequestPending = false;
      this.pageRequestId = 0L;
      this.pageRequestDirection = 0;
      this.requestStartedAt = this.pendingMode == WhitelistReviewScreen.PromptMode.NONE ? 0L : this.requestStartedAt;
      if (preparedRemovalApplied) {
         this.clearPreparedRemoval();
      }

      this.rebuildButtons();
   }

   protected void init() {
      this.rebuildButtons();
   }

   private void rebuildButtons() {
      if (this.promptField != null) {
         this.promptDraft = this.promptField.getValue();
      }

      this.clearWidgets();
      this.promptField = null;
      this.promptSubmitButton = null;
      int bx = this.boxX();
      int by = this.boxY();
      int footerGap = 4;
      int footerButtonWidth = Math.max(44, (this.boxWidth() - 28 - footerGap * 4) / 5);
      int footerX = bx + 14;
      int footerY = by + this.boxHeight() - 28;
      boolean navigationEnabled = this.promptMode == WhitelistReviewScreen.PromptMode.NONE
         && this.pendingMode == WhitelistReviewScreen.PromptMode.NONE
         && !this.pageRequestPending
         && this.pendingPreviewTargetId.isBlank();
      BlackGoldButton activeTab = new BlackGoldButton(
         bx + 14,
         by + 34,
         58,
         20,
         Component.literal("活动 (" + this.totalEntries() + ")"),
         button -> this.selectTab(false),
         this.historyTab ? -9744622 : -2840509
      );
      activeTab.active = navigationEnabled;
      this.addRenderableWidget(activeTab);
      BlackGoldButton historyTabButton = new BlackGoldButton(
         bx + 78,
         by + 34,
         70,
         20,
         Component.literal("历史 (" + this.totalRemovals() + ")"),
         button -> this.selectTab(true),
         this.historyTab ? -2840509 : -9744622
      );
      historyTabButton.active = navigationEnabled;
      this.addRenderableWidget(historyTabButton);
      BlackGoldButton previousPage = new BlackGoldButton(bx + 154, by + 34, 52, 20, Component.literal("上一页"), button -> this.changePage(-1), -9744622);
      previousPage.active = navigationEnabled && this.currentPageOffset() > 0;
      this.addRenderableWidget(previousPage);
      BlackGoldButton nextPage = new BlackGoldButton(bx + 212, by + 34, 52, 20, Component.literal("下一页"), button -> this.changePage(1), -9744622);
      nextPage.active = navigationEnabled && this.currentPageOffset() + this.currentPageSize() < this.currentTotal();
      this.addRenderableWidget(nextPage);
      BlackGoldButton refresh = new BlackGoldButton(
         footerX, footerY, footerButtonWidth, 20, Component.literal("刷新"), button -> this.refreshCurrentPage(), -2840509
      );
      refresh.active = navigationEnabled;
      this.addRenderableWidget(refresh);
      footerX += footerButtonWidth + footerGap;
      BlackGoldButton export = new BlackGoldButton(
         footerX, footerY, footerButtonWidth, 20, Component.literal("导出"), button -> this.request(WhitelistReviewActionPacket.Action.EXPORT, ""), -2840509
      );
      export.active = navigationEnabled;
      this.addRenderableWidget(export);
      footerX += footerButtonWidth + footerGap;
      BlackGoldButton preview = new BlackGoldButton(
         footerX, footerY, footerButtonWidth, 20, Component.literal("查看"), button -> this.previewSelected(), -2840509
      );
      preview.active = navigationEnabled && previewable(this.selectedEntry());
      this.addRenderableWidget(preview);
      footerX += footerButtonWidth + footerGap;
      BlackGoldButton comment = new BlackGoldButton(
         footerX, footerY, footerButtonWidth, 20, Component.literal("评论"), button -> this.openPrompt(WhitelistReviewScreen.PromptMode.COMMENT), -2840509
      );
      comment.active = navigationEnabled && !this.historyTab && this.selectedEntry() != null;
      this.addRenderableWidget(comment);
      footerX += footerButtonWidth + footerGap;
      BlackGoldButton remove = new BlackGoldButton(footerX, footerY, footerButtonWidth, 20, Component.literal("移除"), button -> this.removeSelected(), -38037);
      remove.active = navigationEnabled && !this.historyTab && this.selectedEntry() != null;
      this.addRenderableWidget(remove);
      if (this.promptMode != WhitelistReviewScreen.PromptMode.NONE) {
         int promptY = by + this.boxHeight() - 56;
         int cancelWidth = 44;
         int submitWidth = 52;
         int promptWidth = this.boxWidth() - 28 - cancelWidth - submitWidth - 12;
         Component fieldLabel = Component.literal(this.promptMode == WhitelistReviewScreen.PromptMode.REMOVE ? "移除备注" : "审核评论");
         this.promptField = new EditBox(this.font, bx + 14, promptY, promptWidth, 20, fieldLabel);
         this.promptField.setMaxLength(256);
         this.promptField.setValue(this.promptDraft);
         this.promptField.setHint(Component.literal(this.promptMode == WhitelistReviewScreen.PromptMode.REMOVE ? "移除备注（必填）" : "审核评论（必填）"));
         this.promptField.setEditable(this.pendingMode == WhitelistReviewScreen.PromptMode.NONE);
         this.addRenderableWidget(this.promptField);
         this.promptSubmitButton = new BlackGoldButton(
            bx + this.boxWidth() - 14 - cancelWidth - 6 - submitWidth,
            promptY,
            submitWidth,
            20,
            Component.literal(this.pendingMode == WhitelistReviewScreen.PromptMode.NONE ? "提交" : "处理中"),
            button -> this.submitPrompt(),
            -2840509
         );
         this.promptSubmitButton.active = this.canSubmitPrompt();
         this.addRenderableWidget(this.promptSubmitButton);
         BlackGoldButton cancel = new BlackGoldButton(
            bx + this.boxWidth() - 14 - cancelWidth, promptY, cancelWidth, 20, Component.literal("取消"), button -> this.cancelPrompt(), -9744622
         );
         cancel.active = this.pendingMode == WhitelistReviewScreen.PromptMode.NONE;
         this.addRenderableWidget(cancel);
         this.promptField.setResponder(value -> {
            this.promptDraft = value;
            this.interactionStatus = "";
            if (this.pendingMode == WhitelistReviewScreen.PromptMode.NONE) {
               this.pendingRequestId = 0L;
            }

            if (this.promptSubmitButton != null) {
               this.promptSubmitButton.active = this.canSubmitPrompt();
            }
         });
         if (this.pendingMode == WhitelistReviewScreen.PromptMode.NONE) {
            this.setInitialFocus(this.promptField);
         }
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      if (this.pendingMode == WhitelistReviewScreen.PromptMode.NONE && !this.pageRequestPending) {
         if (this.promptMode != WhitelistReviewScreen.PromptMode.NONE) {
            this.cancelPrompt();
         } else {
            suspendedForPreview = null;
            this.pendingPreviewTargetId = "";
            this.pendingPreviewRequestId = 0L;
            this.previewRequestStartedAt = 0L;
            super.onClose();
         }
      }
   }

   public void tick() {
      super.tick();
      long now = System.currentTimeMillis();
      boolean stateChanged = false;
      if (this.requestStartedAt > 0L && now - this.requestStartedAt >= 10000L) {
         if (this.pendingMode != WhitelistReviewScreen.PromptMode.NONE) {
            this.pendingMode = WhitelistReviewScreen.PromptMode.NONE;
            this.pendingTargetId = "";
            this.clearPreparedRemoval();
            this.interactionStatus = "未收到服务器确认，输入已保留，可重试";
         } else if (this.pageRequestPending) {
            this.interactionStatus = "刷新超时，仍显示原页面，可重试";
         }

         this.pageRequestPending = false;
         this.pageRequestId = 0L;
         this.pageRequestDirection = 0;
         this.requestStartedAt = 0L;
         stateChanged = true;
      }

      if (!this.pendingPreviewTargetId.isBlank() && now - this.previewRequestStartedAt >= 10000L) {
         this.pendingPreviewTargetId = "";
         this.pendingPreviewRequestId = 0L;
         this.previewRequestStartedAt = 0L;
         if (suspendedForPreview == this) {
            suspendedForPreview = null;
         }

         this.interactionStatus = "预览准备超时，可重新尝试";
         stateChanged = true;
      }

      if (stateChanged) {
         this.rebuildButtons();
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
      this.drawList(g, bx, by, mx, my);
      this.drawDetails(g, bx, by);

      for (Renderable renderable : this.renderables) {
         renderable.render(g, mx, my, pt);
      }
   }

   private void drawHeader(GuiGraphics g, int bx, int by, int mx, int my) {
      BlackGoldUi.drawHeader(g, this.font, this.getTitle(), bx, by, this.boxWidth(), 28);
      g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, this.pageStatus(), this.boxWidth() - 28)), bx + 14, by + 58, -6252408, false);
      int cx = bx + this.boxWidth() - 14 - 8;
      int cy = by + 7;
      this.closeHovered = mx >= cx && mx <= cx + 14 && my >= cy && my <= cy + 14;
      g.drawCenteredString(this.font, Component.literal("✕"), cx + 7, cy + 4, this.closeHovered ? -2840509 : -6252408);
   }

   private void drawList(GuiGraphics g, int bx, int by, int mx, int my) {
      int itemCount = this.historyTab ? this.removalRecords.size() : this.entries.size();
      if (itemCount == 0) {
         g.drawCenteredString(this.font, Component.literal(this.historyTab ? "暂无移除历史" : "当前白名单为空"), bx + this.boxWidth() / 2, by + 70 + 8, -10463160);
      } else {
         int rows = Math.min(this.visibleRows(), itemCount - this.scrollOffset);

         for (int i = 0; i < rows; i++) {
            int index = this.scrollOffset + i;
            String id;
            String byName;
            if (this.historyTab) {
               WhitelistReviewPacket.RemovalRecord record = this.removalRecords.get(index);
               id = record.entry() == null ? "" : record.entry().id();
               byName = record.removedByName();
            } else {
               WhitelistReviewPacket.Entry entry = this.entries.get(index);
               id = entry.id();
               byName = entry.addedByName();
            }

            int x = bx + 14;
            int y = by + 70 + i * 22;
            boolean selected = index == this.selectedIndex;
            boolean hovered = mx >= x && mx <= x + this.boxWidth() - 28 && my >= y && my <= y + 22 - 2;
            int bg = selected ? -14271649 : (hovered ? -14671848 : -15395563);
            g.fillGradient(x, y, x + this.boxWidth() - 28, y + 22 - 2, bg, bg);
            g.fillGradient(x, y, x + 2, y + 22 - 2, selected ? -2840509 : -13421773, selected ? -2840509 : -13421773);
            String prefix = selected ? "▶ " : "  ";
            String label = prefix + (this.historyTab ? "[移除] " : "[活动] ") + id;
            g.drawString(
               this.font,
               Component.literal(BlackGoldUi.ellipsize(this.font, label, this.boxWidth() - 142)),
               x + 6,
               y + 6,
               selected ? -2840509 : -2041656,
               false
            );
            int authorX = bx + this.boxWidth() - 116;
            g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, byName, 90)), authorX, y + 6, -6252408, false);
         }

         if (itemCount > this.visibleRows()) {
            this.drawScrollbar(g, bx, by, mx, my);
         }
      }
   }

   private void drawScrollbar(GuiGraphics g, int bx, int by, int mx, int my) {
      int trackX = this.scrollbarX(bx);
      int trackY = this.scrollbarY(by);
      int trackHeight = this.scrollbarHeight();
      int thumbY = this.scrollbarThumbY(by);
      int thumbHeight = this.scrollbarThumbHeight();
      boolean hovered = mx >= trackX && mx <= trackX + 5 && my >= thumbY && my <= thumbY + thumbHeight;
      g.fillGradient(trackX, trackY, trackX + 5, trackY + trackHeight, -14408668, -14408668);
      int thumbColor = !this.draggingScrollbar && !hovered ? -9744622 : -2840509;
      g.fillGradient(trackX, thumbY, trackX + 5, thumbY + thumbHeight, thumbColor, thumbColor);
   }

   private void drawDetails(GuiGraphics g, int bx, int by) {
      WhitelistReviewPacket.Entry entry = this.selectedEntry();
      int x = bx + 14;
      int y = by + this.detailTop();
      int textWidth = this.boxWidth() - 44;
      g.fillGradient(x, y, bx + this.boxWidth() - 14, this.detailBottom(by), -15658735, -15658735);
      if (this.historyTab) {
         WhitelistReviewPacket.RemovalRecord record = this.selectedRemovalRecord();
         if (record == null) {
            g.drawString(this.font, Component.literal("选择一条历史记录查看详情"), x + 8, y + 10, -10463160, false);
         } else {
            WhitelistReviewPacket.Entry removedEntry = record.entry();
            g.drawString(
               this.font,
               Component.literal(BlackGoldUi.ellipsize(this.font, "已移除：" + (removedEntry == null ? "" : removedEntry.id()), textWidth)),
               x + 8,
               y + 8,
               -30088,
               false
            );
            g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, "备注：" + record.note(), textWidth)), x + 8, y + 22, -2041656, false);
            g.drawString(
               this.font,
               Component.literal(BlackGoldUi.ellipsize(this.font, "操作者：" + emptyAs(record.removedByName(), "未知") + "  时间：" + record.removedAt(), textWidth)),
               x + 8,
               y + 36,
               -6252408,
               false
            );
            if (removedEntry != null && removedEntry.comments() != null && !removedEntry.comments().isEmpty() && this.detailBottom(by) - y >= 60) {
               WhitelistReviewPacket.Comment latest = removedEntry.comments().get(removedEntry.comments().size() - 1);
               g.drawString(
                  this.font,
                  Component.literal(BlackGoldUi.ellipsize(this.font, "删除前评论：" + latest.authorName() + "：" + latest.text(), textWidth)),
                  x + 8,
                  y + 50,
                  -10463160,
                  false
               );
            }
         }
      } else if (entry == null) {
         g.drawString(this.font, Component.literal("选择一个条目查看详情"), x + 8, y + 10, -10463160, false);
      } else {
         g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, "资源：" + entry.id(), textWidth)), x + 8, y + 8, -2840509, false);
         g.drawString(
            this.font,
            Component.literal(BlackGoldUi.ellipsize(this.font, "添加者：" + emptyAs(entry.addedByName(), "未知") + "  时间：" + entry.addedAt(), textWidth)),
            x + 8,
            y + 21,
            -6252408,
            false
         );
         List<WhitelistReviewPacket.Comment> comments = entry.comments() == null ? List.of() : entry.comments();
         if (!comments.isEmpty() && this.detailBottom(by) - y < 84) {
            WhitelistReviewPacket.Comment latest = comments.get(comments.size() - 1);
            g.drawString(
               this.font,
               Component.literal(BlackGoldUi.ellipsize(this.font, "最新评论：" + latest.authorName() + "：" + latest.text(), textWidth)),
               x + 8,
               y + 34,
               -10463160,
               false
            );
         } else {
            g.drawString(
               this.font,
               Component.literal(BlackGoldUi.ellipsize(this.font, "原始输入：" + emptyAs(entry.originalInput(), entry.id()), textWidth)),
               x + 8,
               y + 34,
               -10463160,
               false
            );
            int line = 48;
            int availableCommentHeight = this.detailBottom(by) - (y + 61);
            int maxComments = availableCommentHeight < 9 ? 0 : Math.min(2, 1 + (availableCommentHeight - 9) / 13);
            if (!comments.isEmpty() && maxComments > 0) {
               g.drawString(this.font, Component.literal("最近评论："), x + 8, y + line, -2840509, false);
               line += 13;
               int start = Math.max(0, comments.size() - maxComments);

               for (int i = start; i < comments.size(); i++) {
                  WhitelistReviewPacket.Comment comment = comments.get(i);
                  g.drawString(
                     this.font,
                     Component.literal(BlackGoldUi.ellipsize(this.font, comment.authorName() + "：" + comment.text(), textWidth)),
                     x + 8,
                     y + line,
                     -6252408,
                     false
                  );
                  line += 13;
               }
            }
         }
      }
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int bx = this.boxX();
      int by = this.boxY();
      int cx = bx + this.boxWidth() - 14 - 8;
      int cy = by + 7;
      if (mouseX >= cx && mouseX <= cx + 14 && mouseY >= cy && mouseY <= cy + 14) {
         this.onClose();
         return true;
      } else if (this.promptMode == WhitelistReviewScreen.PromptMode.NONE
         && this.pendingMode == WhitelistReviewScreen.PromptMode.NONE
         && !this.pageRequestPending
         && this.pendingPreviewTargetId.isBlank()) {
         int itemCount = this.historyTab ? this.removalRecords.size() : this.entries.size();
         if (button == 0 && itemCount > this.visibleRows() && this.inScrollbar(mouseX, mouseY, bx, by)) {
            int thumbY = this.scrollbarThumbY(by);
            int thumbHeight = this.scrollbarThumbHeight();
            if (!(mouseY < thumbY) && !(mouseY > thumbY + thumbHeight)) {
               this.scrollbarDragOffsetY = mouseY - thumbY;
            } else {
               this.scrollbarDragOffsetY = thumbHeight / 2.0;
               this.scrollToScrollbarPosition(mouseY, by);
            }

            this.draggingScrollbar = true;
            return true;
         } else {
            int listX = bx + 14;
            int listY = by + 70;
            if (mouseX >= listX && mouseX <= listX + this.boxWidth() - 28 && mouseY >= listY && mouseY < listY + this.visibleRows() * 22) {
               int row = ((int)mouseY - listY) / 22;
               int index = this.scrollOffset + row;
               if (index >= 0 && index < itemCount) {
                  this.selectedIndex = index;
                  if (this.historyTab) {
                     this.selectedRemovalId = this.removalRecords.get(index).recordId();
                  }

                  this.rebuildButtons();
                  return true;
               }
            }

            return super.mouseClicked(mouseX, mouseY, button);
         }
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.draggingScrollbar && button == 0) {
         this.scrollToScrollbarPosition(mouseY, this.boxY());
         return true;
      } else {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (!this.pendingPreviewTargetId.isBlank()) {
         if (keyCode == 256) {
            this.onClose();
            return true;
         } else {
            return super.keyPressed(keyCode, scanCode, modifiers);
         }
      } else if (this.pendingMode == WhitelistReviewScreen.PromptMode.NONE && !this.pageRequestPending) {
         if (this.promptMode == WhitelistReviewScreen.PromptMode.NONE) {
            switch (keyCode) {
               case 257:
               case 335:
                  this.previewSelected();
                  break;
               case 261:
                  this.removeSelected();
                  break;
               case 264:
                  this.moveSelection(1);
                  break;
               case 265:
                  this.moveSelection(-1);
                  break;
               case 266:
                  this.moveSelection(-this.visibleRows());
                  break;
               case 267:
                  this.moveSelection(this.visibleRows());
                  break;
               case 268:
                  this.selectIndex(0);
                  break;
               case 269:
                  this.selectIndex((this.historyTab ? this.removalRecords.size() : this.entries.size()) - 1);
                  break;
               default:
                  return super.keyPressed(keyCode, scanCode, modifiers);
            }

            return true;
         } else if (keyCode == 256) {
            this.cancelPrompt();
            return true;
         } else if ((keyCode == 257 || keyCode == 335) && this.getFocused() == this.promptField) {
            this.submitPrompt();
            return true;
         } else {
            return super.keyPressed(keyCode, scanCode, modifiers);
         }
      } else {
         return keyCode == 256 || super.keyPressed(keyCode, scanCode, modifiers);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.draggingScrollbar && button == 0) {
         this.draggingScrollbar = false;
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.promptMode == WhitelistReviewScreen.PromptMode.NONE
         && this.pendingMode == WhitelistReviewScreen.PromptMode.NONE
         && !this.pageRequestPending
         && this.pendingPreviewTargetId.isBlank()) {
         int itemCount = this.historyTab ? this.removalRecords.size() : this.entries.size();
         if (itemCount > this.visibleRows() && scrollY != 0.0 && this.inList(mouseX, mouseY)) {
            int previousOffset = this.scrollOffset;
            this.scrollOffset += scrollY < 0.0 ? 1 : -1;
            this.clampScrollOffset();
            return this.scrollOffset != previousOffset || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
         } else {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
         }
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   private void removeSelected() {
      WhitelistReviewPacket.Entry entry = this.selectedEntry();
      if (entry != null) {
         this.openPrompt(WhitelistReviewScreen.PromptMode.REMOVE);
      }
   }

   private void previewSelected() {
      WhitelistReviewPacket.Entry entry = this.selectedEntry();
      if (entry != null) {
         if (!previewable(entry)) {
            this.interactionStatus = "直播条目暂不支持审核预览";
            this.rebuildButtons();
         } else {
            this.draggingScrollbar = false;
            suspendedForPreview = this;
            this.pendingPreviewTargetId = entry.id();
            this.pendingPreviewRequestId = WhitelistReviewActionPacket.nextClientRequestId();
            this.previewRequestStartedAt = System.currentTimeMillis();
            this.interactionStatus = "";
            this.rebuildButtons();
            PacketDistributor.sendToServer(
               new WhitelistReviewActionPacket(WhitelistReviewActionPacket.Action.PREVIEW, entry.id(), 0L, this.pendingPreviewRequestId),
               new CustomPacketPayload[0]
            );
         }
      }
   }

   private void request(WhitelistReviewActionPacket.Action action, String value) {
      PacketDistributor.sendToServer(new WhitelistReviewActionPacket(action, value), new CustomPacketPayload[0]);
   }

   boolean acceptPreviewResponse(WhitelistPreviewPacket payload) {
      if (payload != null
         && !this.pendingPreviewTargetId.isBlank()
         && this.pendingPreviewRequestId > 0L
         && this.pendingPreviewRequestId == payload.requestId()
         && WhitelistReviewSelection.matchesPreview(this.pendingPreviewTargetId, payload.rawUrl())) {
         this.pendingPreviewTargetId = "";
         this.pendingPreviewRequestId = 0L;
         this.previewRequestStartedAt = 0L;
         this.interactionStatus = "";
         return true;
      } else {
         return false;
      }
   }

   private void refreshCurrentPage() {
      this.beginPageRequest(0, this.entryOffset(), this.removalOffset());
   }

   private void changePage(int direction) {
      int nextEntryOffset = this.entryOffset();
      int nextRemovalOffset = this.removalOffset();
      if (this.historyTab) {
         nextRemovalOffset = WhitelistReviewSelection.clampPageOffset(nextRemovalOffset + direction * 32, this.totalRemovals(), 32);
      } else {
         nextEntryOffset = WhitelistReviewSelection.clampPageOffset(nextEntryOffset + direction * 64, this.totalEntries(), 64);
      }

      if (nextEntryOffset != this.entryOffset() || nextRemovalOffset != this.removalOffset()) {
         this.beginPageRequest(direction, nextEntryOffset, nextRemovalOffset);
      }
   }

   private void beginPageRequest(int direction, int nextEntryOffset, int nextRemovalOffset) {
      this.pageRequestPending = true;
      this.pageRequestId = WhitelistReviewActionPacket.nextClientRequestId();
      this.pageRequestDirection = Integer.compare(direction, 0);
      this.requestStartedAt = System.currentTimeMillis();
      this.interactionStatus = "";
      this.rebuildButtons();
      this.requestPage(nextEntryOffset, nextRemovalOffset, this.pageRequestId);
   }

   private void requestPage(int nextEntryOffset, int nextRemovalOffset, long requestId) {
      PacketDistributor.sendToServer(
         new WhitelistReviewActionPacket(
            WhitelistReviewActionPacket.Action.REFRESH, "", 0L, "", "", Math.max(0, nextEntryOffset), Math.max(0, nextRemovalOffset), requestId
         ),
         new CustomPacketPayload[0]
      );
   }

   private void selectTab(boolean history) {
      if (this.historyTab != history) {
         this.historyTab = history;
         this.selectedIndex = 0;
         this.scrollOffset = 0;
         this.interactionStatus = "";
         if (history) {
            this.selectedRemovalId = this.selectedRemovalRecord() == null ? "" : this.selectedRemovalRecord().recordId();
         }

         this.rebuildButtons();
      }
   }

   private void cancelPrompt() {
      this.clearPrompt();
      this.rebuildButtons();
   }

   private void openPrompt(WhitelistReviewScreen.PromptMode mode) {
      WhitelistReviewPacket.Entry entry = this.selectedEntry();
      if (entry != null && this.pendingMode == WhitelistReviewScreen.PromptMode.NONE && !this.pageRequestPending) {
         this.promptMode = mode;
         this.promptTargetId = entry.id();
         this.promptExpectedAddedAt = entry.addedAt();
         this.promptDraft = "";
         this.pendingRequestId = 0L;
         this.interactionStatus = "";
         this.promptField = null;
         this.ensureSelectedVisible();
         this.rebuildButtons();
      }
   }

   private void submitPrompt() {
      String text = this.promptField == null ? "" : this.promptField.getValue().trim();
      this.promptDraft = text;
      if (this.canSubmitPrompt()) {
         WhitelistReviewActionPacket.Action action = this.promptMode == WhitelistReviewScreen.PromptMode.REMOVE
            ? WhitelistReviewActionPacket.Action.REMOVE
            : WhitelistReviewActionPacket.Action.COMMENT;
         this.pendingMode = this.promptMode;
         this.pendingTargetId = this.promptTargetId;
         if (this.pendingRequestId <= 0L) {
            this.pendingRequestId = WhitelistReviewActionPacket.nextClientRequestId();
         }

         if (this.pendingMode == WhitelistReviewScreen.PromptMode.REMOVE) {
            this.prepareRemovalSelection(this.promptTargetId);
         }

         this.requestStartedAt = System.currentTimeMillis();
         this.interactionStatus = "";
         this.rebuildButtons();
         PacketDistributor.sendToServer(
            new WhitelistReviewActionPacket(
               action, this.promptTargetId, 0L, text, this.promptExpectedAddedAt, this.entryOffset(), this.removalOffset(), this.pendingRequestId
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   private boolean canSubmitPrompt() {
      if (this.promptMode != WhitelistReviewScreen.PromptMode.NONE
         && this.pendingMode == WhitelistReviewScreen.PromptMode.NONE
         && this.promptDraft != null
         && !this.promptDraft.trim().isEmpty()) {
         int targetIndex = this.indexOf(this.promptTargetId);
         return targetIndex >= 0 && Objects.equals(this.entries.get(targetIndex).addedAt(), this.promptExpectedAddedAt);
      } else {
         return false;
      }
   }

   private void handleMutationResult(WhitelistReviewMutationResultPacket result) {
      if (result != null) {
         if (result.mutation() == WhitelistReviewMutationResultPacket.Mutation.REFRESH) {
            if (this.pageRequestPending && this.pageRequestId > 0L && this.pageRequestId == result.requestId()) {
               this.pageRequestPending = false;
               this.pageRequestId = 0L;
               this.pageRequestDirection = 0;
               this.requestStartedAt = 0L;
               this.interactionStatus = result.message();
               this.rebuildButtons();
            }
         } else if (result.mutation() == WhitelistReviewMutationResultPacket.Mutation.PREVIEW) {
            if (!this.pendingPreviewTargetId.isBlank()
               && this.pendingPreviewRequestId > 0L
               && this.pendingPreviewRequestId == result.requestId()
               && Objects.equals(this.pendingPreviewTargetId, result.targetId())) {
               this.pendingPreviewTargetId = "";
               this.pendingPreviewRequestId = 0L;
               this.previewRequestStartedAt = 0L;
               if (suspendedForPreview == this) {
                  suspendedForPreview = null;
               }

               this.interactionStatus = result.message();
               this.rebuildButtons();
            }
         } else if (this.pendingMode != WhitelistReviewScreen.PromptMode.NONE
            && Objects.equals(this.pendingTargetId, result.targetId())
            && this.pendingRequestId == result.requestId()) {
            WhitelistReviewMutationResultPacket.Mutation expected = this.pendingMode == WhitelistReviewScreen.PromptMode.REMOVE
               ? WhitelistReviewMutationResultPacket.Mutation.REMOVE
               : WhitelistReviewMutationResultPacket.Mutation.COMMENT;
            if (result.mutation() == expected) {
               this.pendingMode = WhitelistReviewScreen.PromptMode.NONE;
               this.pendingTargetId = "";
               this.pendingRequestId = 0L;
               this.requestStartedAt = 0L;
               if (result.successful()) {
                  this.clearPrompt();
               } else {
                  this.clearPreparedRemoval();
               }

               this.interactionStatus = result.message();
               this.rebuildButtons();
            }
         }
      }
   }

   private void prepareRemovalSelection(String removedId) {
      this.preparedRemovalTargetId = removedId == null ? "" : removedId;
      this.preferredSelectionAfterRemoval = WhitelistReviewSelection.adjacentIdAfterRemoval(
         this.entries.stream().map(entry -> entry.id()).toList(), this.preparedRemovalTargetId
      );
   }

   private void clearPreparedRemoval() {
      this.preparedRemovalTargetId = "";
      this.preferredSelectionAfterRemoval = "";
   }

   private void selectEntryById(String selectedId) {
      if (selectedId != null && !selectedId.isBlank()) {
         int index = this.indexOf(selectedId);
         if (index >= 0) {
            this.historyTab = false;
            this.selectedIndex = index;
            this.ensureSelectedVisible();
         }
      }
   }

   private void moveSelection(int delta) {
      int itemCount = this.historyTab ? this.removalRecords.size() : this.entries.size();
      if (itemCount != 0) {
         int start = this.selectedIndex < 0 ? 0 : this.selectedIndex;
         this.selectIndex(Math.max(0, Math.min(itemCount - 1, start + delta)));
      }
   }

   private void selectIndex(int index) {
      int itemCount = this.historyTab ? this.removalRecords.size() : this.entries.size();
      if (index >= 0 && index < itemCount) {
         this.selectedIndex = index;
         if (this.historyTab) {
            this.selectedRemovalId = this.removalRecords.get(index).recordId();
         }

         this.ensureSelectedVisible();
         this.interactionStatus = "";
         this.rebuildButtons();
      }
   }

   private void clearPrompt() {
      this.promptMode = WhitelistReviewScreen.PromptMode.NONE;
      this.promptTargetId = "";
      this.promptExpectedAddedAt = "";
      this.promptDraft = "";
      this.pendingRequestId = 0L;
      this.promptField = null;
   }

   private WhitelistReviewPacket.Entry selectedEntry() {
      if (this.historyTab) {
         return null;
      } else {
         return this.selectedIndex >= 0 && this.selectedIndex < this.entries.size() ? this.entries.get(this.selectedIndex) : null;
      }
   }

   private WhitelistReviewPacket.RemovalRecord selectedRemovalRecord() {
      return this.selectedIndex >= 0 && this.selectedIndex < this.removalRecords.size() ? this.removalRecords.get(this.selectedIndex) : null;
   }

   private int indexOf(String id) {
      return WhitelistReviewSelection.indexOf(this.entries.stream().map(entry -> entry.id()).toList(), id);
   }

   private void clampScrollOffset() {
      int max = Math.max(0, (this.historyTab ? this.removalRecords.size() : this.entries.size()) - this.visibleRows());
      this.scrollOffset = Math.max(0, Math.min(max, this.scrollOffset));
   }

   private void ensureSelectedVisible() {
      if (this.selectedIndex >= 0) {
         if (this.selectedIndex < this.scrollOffset) {
            this.scrollOffset = this.selectedIndex;
         } else if (this.selectedIndex >= this.scrollOffset + this.visibleRows()) {
            this.scrollOffset = Math.max(0, this.selectedIndex - this.visibleRows() + 1);
         }
      }
   }

   private void scrollToScrollbarPosition(double mouseY, int by) {
      int maxScroll = Math.max(0, (this.historyTab ? this.removalRecords.size() : this.entries.size()) - this.visibleRows());
      int travel = this.scrollbarHeight() - this.scrollbarThumbHeight();
      if (maxScroll > 0 && travel > 0) {
         double thumbTop = mouseY - this.scrollbarDragOffsetY;
         double progress = (thumbTop - this.scrollbarY(by)) / travel;
         this.scrollOffset = (int)Math.round(progress * maxScroll);
         this.clampScrollOffset();
      } else {
         this.scrollOffset = 0;
      }
   }

   private boolean inList(double mouseX, double mouseY) {
      int bx = this.boxX();
      int by = this.boxY();
      return mouseX >= bx + 14 && mouseX <= bx + this.boxWidth() - 14 && mouseY >= by + 70 && mouseY < by + 70 + this.visibleRows() * 22;
   }

   private boolean inScrollbar(double mouseX, double mouseY, int bx, int by) {
      int x = this.scrollbarX(bx);
      int y = this.scrollbarY(by);
      return mouseX >= x && mouseX <= x + 5 && mouseY >= y && mouseY <= y + this.scrollbarHeight();
   }

   private int scrollbarX(int bx) {
      return bx + this.boxWidth() - 20;
   }

   private int scrollbarY(int by) {
      return by + 70;
   }

   private int scrollbarHeight() {
      return this.visibleRows() * 22 - 2;
   }

   private int scrollbarThumbHeight() {
      return Math.max(
         18, this.scrollbarHeight() * this.visibleRows() / Math.max(this.visibleRows(), this.historyTab ? this.removalRecords.size() : this.entries.size())
      );
   }

   private int scrollbarThumbY(int by) {
      int maxScroll = Math.max(0, (this.historyTab ? this.removalRecords.size() : this.entries.size()) - this.visibleRows());
      int travel = this.scrollbarHeight() - this.scrollbarThumbHeight();
      return this.scrollbarY(by) + (maxScroll == 0 ? 0 : Math.round((float)travel * this.scrollOffset / maxScroll));
   }

   private int boxX() {
      return (this.width - this.boxWidth()) / 2;
   }

   private int boxY() {
      return (this.height - this.boxHeight()) / 2;
   }

   private int boxWidth() {
      return Math.min(420, Math.max(300, this.width - 16));
   }

   private int boxHeight() {
      return Math.min(360, Math.max(220, this.height - 16));
   }

   private int visibleRows() {
      int detailBottomReserve = this.promptMode == WhitelistReviewScreen.PromptMode.NONE ? 36 : 62;
      int available = this.boxHeight() - 70 - 10 - 60 - detailBottomReserve;
      return Math.max(1, Math.min(7, available / 22));
   }

   private int detailTop() {
      return 70 + this.visibleRows() * 22 + 10;
   }

   private int detailBottom(int by) {
      return by + this.boxHeight() - (this.promptMode == WhitelistReviewScreen.PromptMode.NONE ? 36 : 62);
   }

   private int entryOffset() {
      return lastPayload == null ? 0 : Math.max(0, lastPayload.entryOffset());
   }

   private int removalOffset() {
      return lastPayload == null ? 0 : Math.max(0, lastPayload.removalOffset());
   }

   private int totalEntries() {
      return lastPayload == null ? this.entries.size() : Math.max(this.entries.size(), lastPayload.totalEntries());
   }

   private int totalRemovals() {
      return lastPayload == null ? this.removalRecords.size() : Math.max(this.removalRecords.size(), lastPayload.totalRemovalRecords());
   }

   private int currentPageOffset() {
      return this.historyTab ? this.removalOffset() : this.entryOffset();
   }

   private int currentPageSize() {
      return this.historyTab ? this.removalRecords.size() : this.entries.size();
   }

   private int currentTotal() {
      return this.historyTab ? this.totalRemovals() : this.totalEntries();
   }

   private String pageStatus() {
      int total = this.currentTotal();
      int start = total <= 0 ? 0 : Math.min(total, this.currentPageOffset() + 1);
      int end = total <= 0 ? 0 : Math.min(total, this.currentPageOffset() + this.currentPageSize());
      String status = (this.historyTab ? "历史 " : "活动 ") + start + "-" + end + "/" + total;
      if (this.pendingMode == WhitelistReviewScreen.PromptMode.REMOVE) {
         return status + " · 正在移除…";
      } else if (this.pendingMode == WhitelistReviewScreen.PromptMode.COMMENT) {
         return status + " · 正在提交评论…";
      } else if (!this.pendingPreviewTargetId.isBlank()) {
         return status + " · 正在准备预览…";
      } else if (this.pageRequestPending) {
         return status + " · 正在刷新…";
      } else {
         return this.interactionStatus.isBlank() ? status : status + " · " + this.interactionStatus;
      }
   }

   private static List<WhitelistReviewPacket.Entry> safeEntries(WhitelistReviewPacket payload) {
      return payload != null && payload.entries() != null ? payload.entries() : List.of();
   }

   private static List<WhitelistReviewPacket.RemovalRecord> safeRemovalRecords(WhitelistReviewPacket payload) {
      return payload != null && payload.removalRecords() != null ? payload.removalRecords() : List.of();
   }

   private static String emptyAs(String value, String fallback) {
      return value != null && !value.isBlank() ? value : fallback;
   }

   private static boolean previewable(WhitelistReviewPacket.Entry entry) {
      return entry != null && !"live".equalsIgnoreCase(entry.type());
   }

   private static enum PromptMode {
      NONE,
      REMOVE,
      COMMENT;
   }
}
