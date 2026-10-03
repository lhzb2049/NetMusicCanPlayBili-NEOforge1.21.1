package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleMediaSettings;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.LiveSubtitleMetadata;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleAccessPacket;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

abstract class HolographicConsoleInspectorScreen extends HolographicEditorLifecycleScreen {
   protected HolographicConsoleInspectorScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(bindEquippedGlasses, controlConsolePos);
   }

   @Override
   protected void applyInitialElementFocus() {
      int target = this.initialFocusElement;
      this.initialFocusElement = -1;
      if (this.controlConsoleMode) {
         if (target == -2) {
            this.terrainPreviewCenterLocal = new Vector3d(0.0, 0.5, 0.0);
            this.focusControlConsoleCenter();
            this.syncNumericEditBoxes();
         } else if (target >= 0 && target < this.screens.size()) {
            this.selectElement(target);
            HolographicEditorScreenState.PreviewScreenSpec centered = this.screens.get(target);
            this.terrainPreviewCenterLocal = new Vector3d(centered.offsetX, 1.55 + centered.offsetY, centered.distance);
            this.focusSelectedScreen();
            this.syncNumericEditBoxes();
         }
      }
   }

   @Override
   protected void addControlConsoleInspectorWidgets() {
      if (this.selectedScreen >= 0) {
         int panelX = this.width - 226;
         int leftX = panelX + 62;
         int rightX = panelX + 164;
         int y = 112;
         int boxW = 54;
         this.numericDistanceBox = this.addUnboundedInspectorBox(
            leftX, y, boxW, "距离", this.screen().distance, false, v -> this.editSelected("设置距离", selected -> selected.distance = v)
         );
         this.numericOffsetXBox = this.addUnboundedInspectorBox(
            rightX, y, boxW, "位置X", this.screen().offsetX, false, v -> this.editSelected("设置位置 X", selected -> selected.offsetX = v)
         );
         this.numericOffsetYBox = this.addUnboundedInspectorBox(
            leftX, y + 22, boxW, "位置Y", this.screen().offsetY, false, v -> this.editSelected("设置位置 Y", selected -> selected.offsetY = v)
         );
         this.numericHeightBox = this.addUnboundedInspectorBox(
            rightX, y + 22, boxW, "高度", this.screen().height, true, v -> this.editSelected("设置高度", selected -> selected.height = v)
         );
         this.numericAspectBox = this.addUnboundedInspectorBox(
            leftX, y + 44, boxW, "比例", this.screen().aspect, true, v -> this.editSelected("设置宽高比", selected -> selected.aspect = v)
         );
         this.numericYawBox = this.addUnboundedInspectorBox(
            rightX, y + 44, boxW, "Yaw", this.screen().yaw, false, v -> this.editSelected("设置 Yaw", selected -> selected.yaw = v)
         );
         this.numericPitchBox = this.addUnboundedInspectorBox(
            leftX, y + 66, boxW, "Pitch", this.screen().pitch, false, v -> this.editSelected("设置 Pitch", selected -> selected.pitch = v)
         );
         this.numericRollBox = this.addUnboundedInspectorBox(
            rightX, y + 66, boxW, "Roll", this.screen().roll, false, v -> this.editSelected("设置 Roll", selected -> selected.roll = v)
         );
         boolean editable = this.selectedElementEditable();

         for (EditBox box : List.of(
            this.numericDistanceBox,
            this.numericOffsetXBox,
            this.numericOffsetYBox,
            this.numericHeightBox,
            this.numericAspectBox,
            this.numericYawBox,
            this.numericPitchBox,
            this.numericRollBox
         )) {
            box.active = editable;
         }

         HolographicEditorScreenState.PreviewScreenSpec selected = this.screen();
         this.addRenderableWidget(
            new BlackGoldButton(panelX + 12, y - 18, 58, 18, Component.literal(this.showTransformInspector ? "查看内容" : "高级变换"), button -> {
               this.showTransformInspector = !this.showTransformInspector;
               this.init();
            }, -9744622)
         );
         this.addRenderableWidget(
            new BlackGoldButton(
               panelX + 78, y - 18, 132, 18, Component.literal(selected.locked ? "\ud83d\udd12 已锁定（点击解锁）" : "\ud83d\udd13 未锁定（点击锁定）"), button -> {
                  this.edit("切换元素锁定", () -> selected.locked = !selected.locked);
                  this.init();
               }, -9744622
            )
         );
         if (this.showTransformInspector) {
            this.numericScaleXBox = this.addInspectorBox(
               leftX, y + 94, 0, boxW, "缩放X", this.screen().scaleX, 0.05F, 16.0F, v -> this.editSelected("设置缩放 X", item -> item.scaleX = v)
            );
            this.numericScaleYBox = this.addInspectorBox(
               rightX, y + 94, 0, boxW, "缩放Y", this.screen().scaleY, 0.05F, 16.0F, v -> this.editSelected("设置缩放 Y", item -> item.scaleY = v)
            );
            this.numericScaleZBox = this.addInspectorBox(
               leftX, y + 116, 0, boxW, "缩放Z", this.screen().scaleZ, 0.05F, 16.0F, v -> this.editSelected("设置缩放 Z", item -> item.scaleZ = v)
            );
            this.numericPivotXBox = this.addUnboundedInspectorBox(
               rightX, y + 116, boxW, "枢轴X", this.screen().pivotX, false, v -> this.editSelected("设置枢轴 X", item -> item.pivotX = v)
            );
            this.numericPivotYBox = this.addUnboundedInspectorBox(
               leftX, y + 138, boxW, "枢轴Y", this.screen().pivotY, false, v -> this.editSelected("设置枢轴 Y", item -> item.pivotY = v)
            );
            this.numericPivotZBox = this.addUnboundedInspectorBox(
               rightX, y + 138, boxW, "枢轴Z", this.screen().pivotZ, false, v -> this.editSelected("设置枢轴 Z", item -> item.pivotZ = v)
            );
            this.numericSkewXByYBox = this.addInspectorBox(
               leftX, y + 160, 0, boxW, "X←Y", this.screen().skewXByY, -1.0F, 1.0F, v -> this.editSelected("设置 X←Y 剪切", item -> item.skewXByY = v)
            );
            this.numericSkewYByXBox = this.addInspectorBox(
               rightX, y + 160, 0, boxW, "Y←X", this.screen().skewYByX, -1.0F, 1.0F, v -> this.editSelected("设置 Y←X 剪切", item -> item.skewYByX = v)
            );

            for (EditBox box : List.of(
               this.numericScaleXBox,
               this.numericScaleYBox,
               this.numericScaleZBox,
               this.numericPivotXBox,
               this.numericPivotYBox,
               this.numericPivotZBox,
               this.numericSkewXByYBox,
               this.numericSkewYByXBox
            )) {
               box.active = editable && this.screen().type != HolographicEditorScreenState.ElementType.AUDIO;
            }
         } else {
            this.addElementContentWidgets(panelX + 78, y + 94, this.screen());
         }
      }
   }

   private void addElementContentWidgets(int x, int y, HolographicEditorScreenState.PreviewScreenSpec selected) {
      if (selected.type == HolographicEditorScreenState.ElementType.SUBTITLE) {
         this.elementTextBox = this.addConsoleTextBox(x, y, 132, selected.text, value -> this.editSelected("设置字幕文本", item -> item.text = value));
         this.elementTextBox.active = !selected.locked;
         BlackGoldButton lyricsButton = new BlackGoldButton(x, y + 22, 64, 18, Component.literal(selected.followLyrics ? "歌词：开" : "歌词：关"), button -> {
            this.editSelected("切换歌词跟随", item -> item.followLyrics = !item.followLyrics);
            this.init();
         }, -9744622);
         lyricsButton.active = !selected.locked;
         this.addRenderableWidget(lyricsButton);
         BlackGoldButton translationButton = new BlackGoldButton(
            x + 68, y + 22, 64, 18, Component.literal(selected.showTranslation ? "翻译：开" : "翻译：关"), button -> {
               this.editSelected("切换字幕翻译", item -> item.showTranslation = !item.showTranslation);
               this.init();
            }, -9744622
         );
         translationButton.active = !selected.locked;
         this.addRenderableWidget(translationButton);
         BlackGoldButton modeButton = new BlackGoldButton(x, y + 66, 64, 18, Component.literal(subtitleModeLabel(selected.contentMode)), button -> {
            this.editSelected("切换字幕模式", HolographicConsoleInspectorScreen::cycleSubtitleMode);
            this.init();
         }, -9744622);
         BlackGoldButton trackButton = new BlackGoldButton(x + 68, y + 66, 64, 18, Component.literal(subtitleTrackLabel(selected.contentMode)), button -> {
            this.editSelected("切换字幕轨道", item -> {
               if (SubtitleLayout.isScrollingMode(item.contentMode)) {
                  item.contentMode = SubtitleLayout.toggleScrollingTrack(item.contentMode);
                  item.followLyrics = true;
               }
            });
            this.init();
         }, -9744622);
         modeButton.active = !selected.locked;
         trackButton.active = !selected.locked && SubtitleLayout.isScrollingMode(selected.contentMode);
         this.addRenderableWidget(modeButton);
         this.addRenderableWidget(trackButton);
         this.elementTextScaleBox = this.addUnboundedInspectorBox(
            x, y + 44, 54, "字号", selected.textScale, true, value -> this.editSelected("设置字幕字号", item -> item.textScale = value)
         );
         this.elementTextScaleBox.active = !selected.locked;
         this.elementColorBox = this.addColorBox(x, y + 88, 64, selected.color, value -> this.editSelected("设置字幕颜色", item -> item.color = value));
         this.elementTranslationColorBox = this.addColorBox(
            x + 68, y + 88, 64, selected.translationColor, value -> this.editSelected("设置翻译颜色", item -> item.translationColor = value)
         );
         this.elementBackgroundColorBox = this.addColorBox(
            x, y + 110, 64, selected.backgroundColor, value -> this.editSelected("设置字幕背景", item -> item.backgroundColor = value)
         );
         this.elementMaxWidthBox = this.addUnboundedInspectorBox(
            x + 68, y + 110, 64, "宽度", selected.maxWidth, false, value -> this.editSelected("设置字幕宽度", item -> {
               if (value >= 0.0F) {
                  item.maxWidth = value;
               }
            })
         );
         BlackGoldButton alignmentButton = new BlackGoldButton(x, y + 132, 64, 18, Component.literal(alignmentLabel(selected.alignment)), button -> {
            this.editSelected("切换字幕对齐", item -> item.alignment = nextAlignment(item.alignment));
            this.init();
         }, -9744622);
         BlackGoldButton wrapButton = new BlackGoldButton(x + 68, y + 132, 64, 18, Component.literal(selected.wrap ? "换行：开" : "换行：关"), button -> {
            this.editSelected("切换字幕换行", item -> item.wrap = !item.wrap);
            this.init();
         }, -9744622);
         alignmentButton.active = wrapButton.active = !selected.locked;
         this.elementColorBox.active = this.elementTranslationColorBox.active = this.elementBackgroundColorBox.active = this.elementMaxWidthBox.active = !selected.locked;
         this.addRenderableWidget(alignmentButton);
         this.addRenderableWidget(wrapButton);
      } else if (selected.type == HolographicEditorScreenState.ElementType.AUDIO) {
         this.elementVolumeSlider = new HolographicEditorScreenState.ElementVolumeSlider(x, y, 132, 18, selected.volume);
         this.elementVolumeSlider.active = !selected.locked;
         this.addRenderableWidget(this.elementVolumeSlider);
         BlackGoldButton channelButton = new BlackGoldButton(
            x, y + 22, 64, 18, Component.literal("声道：" + ControlConsoleMediaSettings.audioChannelLabel(selected.channelIndex)), button -> {
               this.editSelected("切换音源声道", item -> item.channelIndex = ControlConsoleMediaSettings.nextAudioChannel(item.channelIndex));
               this.init();
            }, -9744622
         );
         this.elementMaxDistanceBox = this.addUnboundedInspectorBox(
            x + 68, y + 22, 64, "距离", selected.maxDistance, true, value -> this.editSelected("设置音源距离", item -> item.maxDistance = value)
         );
         this.elementMaxDistanceBox.active = channelButton.active = !selected.locked;
         this.addRenderableWidget(channelButton);
         BlackGoldButton audioEnabledButton = new BlackGoldButton(x, y + 44, 64, 18, Component.literal(selected.enabled ? "音源：开" : "音源：关"), button -> {
            this.editSelected("切换音源", item -> item.enabled = !item.enabled);
            this.init();
         }, -9744622);
         audioEnabledButton.active = !selected.locked;
         this.addRenderableWidget(audioEnabledButton);
         BlackGoldButton autoMixButton = new BlackGoldButton(x + 68, y + 44, 64, 18, Component.literal(selected.autoMixJoc ? "自动混合：开" : "自动混合：关"), button -> {
            this.editSelected("切换自动混合", item -> item.autoMixJoc = !item.autoMixJoc);
            this.init();
         }, -9744622);
         autoMixButton.active = !selected.locked;
         this.addRenderableWidget(autoMixButton);
      } else {
         BlackGoldButton sourceButton = new BlackGoldButton(x, y, 64, 18, Component.literal("视频：绑定源"), button -> {
            this.editSelected("设置屏幕来源", item -> item.contentMode = "SOURCE");
            this.init();
         }, -9744622);
         sourceButton.active = !selected.locked;
         this.addRenderableWidget(sourceButton);
         BlackGoldButton screenEnabledButton = new BlackGoldButton(x + 68, y, 64, 18, Component.literal(selected.enabled ? "屏幕：开" : "屏幕：关"), button -> {
            this.editSelected("切换屏幕", item -> item.enabled = !item.enabled);
            this.init();
         }, -9744622);
         screenEnabledButton.active = !selected.locked;
         this.addRenderableWidget(screenEnabledButton);
         BlackGoldButton qualityButton = new BlackGoldButton(
            x, y + 22, 132, 18, Component.literal("画质：" + ControlConsoleMediaSettings.videoQualityLabel(selected.channelIndex)), button -> {
               this.editSelected("切换屏幕画质", item -> item.channelIndex = ControlConsoleMediaSettings.nextVideoQualityIndex(item.channelIndex));
               this.init();
            }, -9744622
         );
         qualityButton.active = !selected.locked;
         this.addRenderableWidget(qualityButton);
         this.elementBrightnessSlider = new HolographicEditorScreenState.ElementBrightnessSlider(x, y + 44, 132, 18, selected.brightness);
         this.elementBrightnessSlider.active = !selected.locked;
         this.addRenderableWidget(this.elementBrightnessSlider);
      }
   }

   private EditBox addColorBox(int x, int y, int width, int value, IntConsumer responder) {
      EditBox box = new EditBox(this.font, x, y, width, 18, Component.literal("ARGB"));
      box.setValue(String.format(Locale.ROOT, "%08X", value));
      box.setResponder(text -> {
         String normalized = text.trim().replaceFirst("^(?i)#|0x", "");
         if (normalized.length() == 8) {
            try {
               responder.accept((int)Long.parseLong(normalized, 16));
            } catch (NumberFormatException var4x) {
            }
         }
      });
      this.addRenderableWidget(box);
      return box;
   }

   private static String alignmentLabel(ControlConsoleElement.Alignment alignment) {
      return switch (alignment) {
         case LEFT -> "对齐：左";
         case CENTER -> "对齐：中";
         case RIGHT -> "对齐：右";
      };
   }

   private static ControlConsoleElement.Alignment nextAlignment(ControlConsoleElement.Alignment alignment) {
      return switch (alignment) {
         case LEFT -> ControlConsoleElement.Alignment.CENTER;
         case CENTER -> ControlConsoleElement.Alignment.RIGHT;
         case RIGHT -> ControlConsoleElement.Alignment.LEFT;
      };
   }

   private static String subtitleModeLabel(String mode) {
      return switch (mode) {
         case "FIXED" -> "模式：固定";
         case "SCROLL_MAIN", "SCROLL_TRANSLATION" -> "模式：滚动";
         case "AI_SUBTITLE" -> "模式：AI字幕";
         case "LIVE_TITLE" -> "模式：直播标题";
         case "LIVE_ROOM" -> "模式：房间信息";
         case "LIVE_STATUS" -> "模式：直播状态";
         default -> "模式：静态";
      };
   }

   private static String subtitleTrackLabel(String mode) {
      return switch (mode) {
         case "SCROLL_TRANSLATION" -> "轨道：翻译";
         case "SCROLL_MAIN" -> "轨道：主歌词";
         default -> "轨道：--";
      };
   }

   private static void cycleSubtitleMode(HolographicEditorScreenState.PreviewScreenSpec selected) {
      selected.contentMode = SubtitleLayout.nextDisplayMode(selected.contentMode);
      if (!"FIXED".equals(selected.contentMode) && !LiveSubtitleMetadata.isLiveMode(selected.contentMode)) {
         selected.followLyrics = true;
      } else {
         selected.followLyrics = false;
      }
   }

   @Override
   protected void addControlConsoleDocumentWidgets() {
      if (this.selectedScreen < 0) {
         ControlConsoleDocument document = this.currentConsoleDocument();
         if (document != null) {
            int panelX = this.width - 226;
            int x = panelX + 78;
            this.addConsoleTextBox(x, 62, 132, document.displayName(), text -> {
               String name = text.trim();
               if (!name.isEmpty() && name.length() <= 64) {
                  this.updateConsoleDraft(name, this.consoleDraft.hardRangeX(), this.consoleDraft.hardRangeY(), this.consoleDraft.hardRangeZ());
               }
            });
            int rangeX = panelX + 12;
            this.addConsoleRangeBox(
               rangeX,
               108,
               "X",
               document.hardRangeX(),
               value -> this.updateConsoleDraft(this.consoleDraft.displayName(), value, this.consoleDraft.hardRangeY(), this.consoleDraft.hardRangeZ())
            );
            this.addConsoleRangeBox(
               rangeX + 62,
               108,
               "Y",
               document.hardRangeY(),
               value -> this.updateConsoleDraft(this.consoleDraft.displayName(), this.consoleDraft.hardRangeX(), value, this.consoleDraft.hardRangeZ())
            );
            this.addConsoleRangeBox(
               rangeX + 124,
               108,
               "Z",
               document.hardRangeZ(),
               value -> this.updateConsoleDraft(this.consoleDraft.displayName(), this.consoleDraft.hardRangeX(), this.consoleDraft.hardRangeY(), value)
            );
            this.consoleAccessModeDraft = this.consoleAccessModeDraft != null ? this.consoleAccessModeDraft : document.accessMode();
            this.addRenderableWidget(new BlackGoldButton(rangeX, 152, 94, 20, Component.literal(accessModeLabel(this.consoleAccessModeDraft)), button -> {
               this.consoleAccessModeDraft = nextAccessMode(this.consoleAccessModeDraft);
               button.setMessage(Component.literal(accessModeLabel(this.consoleAccessModeDraft)));
            }, -2840509));
            this.consoleTrustedPlayersBox = this.addConsoleTextBox(
               rangeX, 216, 202, document.trustedPlayerIds().stream().map(id -> id.toString()).sorted().collect(Collectors.joining(",")), ignored -> {}
            );
            this.addRenderableWidget(
               new BlackGoldButton(rangeX + 102, 152, 100, 20, Component.literal("应用权限"), button -> this.sendConsoleAccessUpdate(), -2840509)
            );
            if (this.consoleSaveConflict) {
               this.addRenderableWidget(
                  new BlackGoldButton(rangeX, 242, 132, 20, Component.literal("重新加载服务器版"), button -> this.reloadAuthoritativeConsoleDocument(), -2840509)
               );
            }
         }
      }
   }

   protected void sendConsoleAccessUpdate() {
      if (this.controlConsolePos != null
         && this.consoleDraft != null
         && this.consolePendingOperation == null
         && this.consoleTrustedPlayersBox != null
         && this.consoleAccessModeDraft != null) {
         Set<UUID> trusted;
         try {
            trusted = parseTrustedPlayerIds(this.consoleTrustedPlayersBox.getValue());
         } catch (IllegalArgumentException var4) {
            this.consoleSaveStatus = "可信玩家 UUID 格式无效";
            return;
         }

         UUID operationId = UUID.randomUUID();
         this.consoleAccessRollback = this.consoleDraft;
         this.consoleDraft = new ControlConsoleDocument(
            this.consoleDraft.schemaVersion(),
            this.consoleDraft.consoleId(),
            this.consoleDraft.revision(),
            this.consoleDraft.ownerId(),
            this.consoleAccessModeDraft,
            trusted,
            this.consoleDraft.displayName(),
            this.consoleDraft.sourceDimension(),
            this.consoleDraft.sourceKind(),
            this.consoleDraft.sourceX(),
            this.consoleDraft.sourceY(),
            this.consoleDraft.sourceZ(),
            this.consoleDraft.hardRangeX(),
            this.consoleDraft.hardRangeY(),
            this.consoleDraft.hardRangeZ(),
            this.consoleElementsSnapshot()
         );
         this.consolePendingOperation = operationId;
         this.consolePendingFingerprint = this.consoleDraftFingerprint(this.consoleDraft);
         this.consoleSaveStatus = "正在保存权限…";
         UUID leaseId = ControlConsoleClient.leaseId(this.controlConsolePos);
         if (leaseId == null) {
            this.restoreAccessRollback();
            this.consolePendingOperation = null;
            this.consoleSaveStatus = "编辑租约不可用";
         } else {
            PacketDistributor.sendToServer(
               new ControlConsoleAccessPacket(this.controlConsolePos, leaseId, operationId, this.consoleDraft.revision(), this.consoleAccessModeDraft, trusted),
               new CustomPacketPayload[0]
            );
         }
      }
   }

   protected static Set<UUID> parseTrustedPlayerIds(String text) {
      LinkedHashSet<UUID> result = new LinkedHashSet<>();

      for (String value : text.trim().split("[,;\\s]+")) {
         if (!value.isBlank()) {
            result.add(UUID.fromString(value));
         }
      }

      if (result.size() > 256) {
         throw new IllegalArgumentException("too many trusted players");
      } else {
         return Set.copyOf(result);
      }
   }

   protected static ControlConsoleDocument.AccessMode nextAccessMode(ControlConsoleDocument.AccessMode mode) {
      return switch (mode) {
         case OWNER_ONLY -> ControlConsoleDocument.AccessMode.TRUSTED;
         case TRUSTED -> ControlConsoleDocument.AccessMode.PUBLIC_EDIT;
         case PUBLIC_EDIT -> ControlConsoleDocument.AccessMode.OWNER_ONLY;
      };
   }

   protected static String accessModeLabel(ControlConsoleDocument.AccessMode mode) {
      return switch (mode) {
         case OWNER_ONLY -> "仅所有者";
         case TRUSTED -> "可信玩家";
         case PUBLIC_EDIT -> "公开编辑";
      };
   }

   protected EditBox addConsoleTextBox(int x, int y, int boxWidth, String value, Consumer<String> responder) {
      EditBox box = new EditBox(this.font, x, y, boxWidth, 18, Component.literal("中控台名称"));
      box.setValue(value);
      box.setResponder(responder);
      this.addRenderableWidget(box);
      return box;
   }

   protected EditBox addConsoleRangeBox(int x, int y, String axis, double value, Consumer<Double> responder) {
      EditBox box = new EditBox(this.font, x, y, 54, 18, Component.literal("范围" + axis));
      box.setValue(fmt((float)value));
      box.setResponder(text -> {
         if (!this.syncingNumericEditBoxes) {
            try {
               double parsed = Double.parseDouble(text.trim());
               if (Double.isFinite(parsed) && parsed > 0.0) {
                  responder.accept(parsed);
               }
            } catch (NumberFormatException var5) {
            }
         }
      });
      this.addRenderableWidget(box);
      return box;
   }

   protected EditBox addUnboundedInspectorBox(int x, int y, int boxW, String label, float value, boolean positive, Consumer<Float> onApply) {
      EditBox box = new EditBox(this.font, x, y, boxW, 18, Component.literal(label));
      box.setValue(fmt(value));
      box.setResponder(text -> {
         if (!this.syncingNumericEditBoxes) {
            try {
               float parsed = Float.parseFloat(text.trim());
               if (Float.isFinite(parsed) && (!positive || parsed > 0.0F)) {
                  onApply.accept(parsed);
               }
            } catch (NumberFormatException var5x) {
            }
         }
      });
      this.addRenderableWidget(box);
      return box;
   }

   @Override
   protected ControlConsoleDocument currentConsoleDocument() {
      ControlConsoleDocument document = this.controlConsoleDocument();
      if (this.consoleDraft == null && document != null) {
         this.consoleSavedFingerprint = documentFingerprint(document);
         this.consoleObservedFingerprint = this.consoleSavedFingerprint;
         this.consoleAutosaveFingerprintInitialized = true;
         this.consoleDraft = document.withInitialScreenIfPristine();
         if (!this.consoleElementsLoaded) {
            this.loadConsoleElements(this.consoleDraft);
         }

         if (this.roamingHistoryPending) {
            this.roamingHistoryPending = false;
            HolographicEditorScreenState.ConsoleProperties properties = new HolographicEditorScreenState.ConsoleProperties(
               this.consoleDraft.displayName(), this.consoleDraft.hardRangeX(), this.consoleDraft.hardRangeY(), this.consoleDraft.hardRangeZ()
            );
            HolographicEditorScreenState.EditorSceneState before = new HolographicEditorScreenState.EditorSceneState(
               this.consoleDraft.elements().stream().map(HolographicEditorScreenState::snapshot).toList(), -1, properties
            );
            HolographicEditorScreenState.EditorSceneState after = this.snapshotScene();
            if (!before.equals(after)) {
               this.editHistory.execute(before, new StateReplacementCommand<>(before, after, "世界漫游编辑"));
            }
         }
      }

      return this.consoleDraft != null ? this.consoleDraft : document;
   }

   protected static int documentFingerprint(ControlConsoleDocument document) {
      return Objects.hash(document.displayName(), document.hardRangeX(), document.hardRangeY(), document.hardRangeZ(), document.elements());
   }

   @Override
   protected void ensureConsoleDocumentLoaded() {
      this.currentConsoleDocument();
   }

   @Override
   protected void loadConsoleElements(ControlConsoleDocument document) {
      this.screens.clear();

      for (ControlConsoleElement element : document.elements()) {
         HolographicEditorScreenState.ElementType type = switch (element.type()) {
            case SCREEN -> HolographicEditorScreenState.ElementType.SCREEN;
            case SUBTITLE -> HolographicEditorScreenState.ElementType.SUBTITLE;
            case AUDIO -> HolographicEditorScreenState.ElementType.AUDIO;
         };
         HolographicEditorScreenState.PreviewScreenSpec restored = new HolographicEditorScreenState.PreviewScreenSpec(
            element.elementId(),
            type,
            element.name(),
            element.distance(),
            element.offsetX(),
            element.offsetY(),
            element.height(),
            element.aspect(),
            element.roll()
         );
         restored.yaw = element.yaw();
         restored.pitch = element.pitch();
         restored.contentMode = element.contentMode();
         restored.text = element.text();
         restored.followLyrics = element.followLyrics();
         restored.showTranslation = element.showTranslation();
         restored.textScale = element.textScale();
         restored.color = element.color();
         restored.volume = element.volume();
         restored.channelIndex = element.channelIndex();
         restored.maxDistance = element.maxDistance();
         restored.autoMixJoc = element.autoMixJoc();
         restored.translationColor = element.translationColor();
         restored.backgroundColor = element.backgroundColor();
         restored.alignment = element.alignment();
         restored.maxWidth = element.maxWidth();
         restored.wrap = element.wrap();
         restored.enabled = element.enabled();
         restored.locked = element.locked();
         restored.scaleX = element.scaleX();
         restored.scaleY = element.scaleY();
         restored.scaleZ = element.scaleZ();
         restored.pivotX = element.pivotX();
         restored.pivotY = element.pivotY();
         restored.pivotZ = element.pivotZ();
         restored.skewXByY = element.skewXByY();
         restored.skewYByX = element.skewYByX();
         restored.brightness = element.brightness();
         this.screens.add(restored);
      }

      this.consoleElementsLoaded = true;
      this.selectedScreen = !this.screens.isEmpty() && this.selectedScreen >= 0 ? Math.min(this.selectedScreen, this.screens.size() - 1) : -1;
   }

   protected void updateConsoleDraft(String name, double rangeX, double rangeY, double rangeZ) {
      ControlConsoleDocument base = this.currentConsoleDocument();
      if (base != null) {
         try {
            this.edit(
               "设置中控台属性",
               () -> this.consoleDraft = new ControlConsoleDocument(
                  base.schemaVersion(),
                  base.consoleId(),
                  base.revision(),
                  base.ownerId(),
                  base.accessMode(),
                  base.trustedPlayerIds(),
                  name,
                  base.sourceDimension(),
                  base.sourceKind(),
                  base.sourceX(),
                  base.sourceY(),
                  base.sourceZ(),
                  rangeX,
                  rangeY,
                  rangeZ,
                  this.consoleElementsSnapshot()
               )
            );
         } catch (IllegalArgumentException var10) {
         }
      }
   }

   @Override
   protected List<ControlConsoleElement> consoleElementsSnapshot() {
      List<ControlConsoleElement> elements = new ArrayList<>(this.screens.size());

      for (HolographicEditorScreenState.PreviewScreenSpec screen : this.screens) {
         ControlConsoleElement.Type type = switch (screen.type) {
            case SCREEN -> ControlConsoleElement.Type.SCREEN;
            case SUBTITLE -> ControlConsoleElement.Type.SUBTITLE;
            case AUDIO -> ControlConsoleElement.Type.AUDIO;
         };
         elements.add(
            new ControlConsoleElement(
               screen.elementId,
               type,
               screen.name,
               screen.distance,
               screen.offsetX,
               screen.offsetY,
               screen.height,
               screen.aspect,
               screen.yaw,
               screen.pitch,
               screen.roll,
               screen.contentMode,
               screen.text,
               screen.followLyrics,
               screen.showTranslation,
               screen.textScale,
               screen.color,
               screen.volume,
               screen.channelIndex,
               screen.maxDistance,
               screen.autoMixJoc,
               screen.translationColor,
               screen.backgroundColor,
               screen.alignment,
               screen.maxWidth,
               screen.wrap,
               screen.enabled,
               screen.locked,
               screen.scaleX,
               screen.scaleY,
               screen.scaleZ,
               screen.pivotX,
               screen.pivotY,
               screen.pivotZ,
               screen.skewXByY,
               screen.skewYByX,
               screen.brightness
            )
         );
      }

      return List.copyOf(elements);
   }

   private EditBox addInspectorBox(int x, int y, int labelW, int boxW, String label, float value, float min, float max, Consumer<Float> onApply) {
      EditBox box = new EditBox(this.font, x, y, boxW, 18, Component.literal(label));
      box.setValue(fmt(value));
      box.setResponder(text -> {
         if (!this.syncingNumericEditBoxes) {
            try {
               onApply.accept(HolographicScreenSettings.clamp(Float.parseFloat(text.trim()), min, max));
            } catch (NumberFormatException var6x) {
            }
         }
      });
      this.addRenderableWidget(box);
      return box;
   }

   @Override
   protected void addControlConsoleWidgets() {
      int x = 8;
      int listTop = 34;
      int actionTop = Math.max(listTop + 24, this.height - 60);
      int visibleRows = Math.max(1, (actionTop - listTop - 4) / 24);
      this.ensureSelectedConsoleElementVisible(visibleRows);
      int first = Math.min(this.consoleElementScroll, Math.max(0, this.screens.size() - 1));
      int last = Math.min(this.screens.size(), first + visibleRows);
      int y = listTop;
      int addButtonWidth = 44;
      int addButtonGap = 2;

      for (int i = first; i < last; i++) {
         int index = i;
         this.addRenderableWidget(
            new BlackGoldButton(
               x,
               y,
               140,
               20,
               Component.literal((i == this.selectedScreen ? "◆ " : "  ") + this.screens.get(i).type.symbol + " " + this.screens.get(i).name),
               button -> {
                  this.selectElement(index);
                  this.init();
               },
               -2840509
            )
         );
         y += 24;
      }

      boolean canAdd = this.canAddConsoleElement();
      BlackGoldButton addScreen = new BlackGoldButton(
         x,
         actionTop,
         addButtonWidth,
         20,
         Component.literal("+ 屏幕"),
         button -> this.addConsoleElement(HolographicEditorScreenState.ElementType.SCREEN),
         -2840509
      );
      addScreen.active = canAdd;
      this.addRenderableWidget(addScreen);
      BlackGoldButton addSubtitle = new BlackGoldButton(
         x + addButtonWidth + addButtonGap,
         actionTop,
         addButtonWidth,
         20,
         Component.literal("+ 字幕"),
         button -> this.addConsoleElement(HolographicEditorScreenState.ElementType.SUBTITLE),
         -2840509
      );
      addSubtitle.active = canAdd;
      this.addRenderableWidget(addSubtitle);
      BlackGoldButton addAudio = new BlackGoldButton(
         x + (addButtonWidth + addButtonGap) * 2,
         actionTop,
         addButtonWidth,
         20,
         Component.literal("+ 音频"),
         button -> this.addConsoleElement(HolographicEditorScreenState.ElementType.AUDIO),
         -2840509
      );
      addAudio.active = canAdd;
      this.addRenderableWidget(addAudio);
      BlackGoldButton copy = new BlackGoldButton(x, actionTop + 24, 64, 20, Component.literal("复制"), button -> {
         HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
         if (selected != null && !selected.locked && this.canAddConsoleElement()) {
            this.edit("复制元素", () -> {
               HolographicEditorScreenState.PreviewScreenSpec duplicate = selected.copyWithName(this.nextElementName(selected.type));
               this.screens.add(duplicate);
               this.selectElement(this.screens.size() - 1);
            });
            this.init();
         }
      }, -9744622);
      HolographicEditorScreenState.PreviewScreenSpec selectedForCopy = this.selectedScreenOrNull();
      copy.active = selectedForCopy != null && !selectedForCopy.locked && this.canAddConsoleElement();
      this.addRenderableWidget(copy);
      BlackGoldButton delete = new BlackGoldButton(
         x + 68, actionTop + 24, 64, 20, Component.literal("删除"), button -> this.removeSelectedConsoleElement(), -3129280
      );
      delete.active = selectedForCopy != null && !selectedForCopy.locked;
      this.addRenderableWidget(delete);
   }

   protected void ensureSelectedConsoleElementVisible(int visibleRows) {
      int maxScroll = Math.max(0, this.screens.size() - visibleRows);
      this.consoleElementScroll = Math.clamp((long)this.consoleElementScroll, 0, maxScroll);
      if (this.selectedScreen >= 0 && this.selectedScreen < this.consoleElementScroll) {
         this.consoleElementScroll = this.selectedScreen;
      } else if (this.selectedScreen >= this.consoleElementScroll + visibleRows) {
         this.consoleElementScroll = Math.min(maxScroll, this.selectedScreen - visibleRows + 1);
      }
   }

   protected void addConsoleElement(HolographicEditorScreenState.ElementType type) {
      if (this.canAddConsoleElement()) {
         this.edit("添加" + type.displayName, () -> {
            this.screens.add(HolographicEditorScreenState.PreviewScreenSpec.defaultsWithName(type, this.nextElementName(type)));
            this.selectElement(this.screens.size() - 1);
         });
         this.init();
      }
   }

   protected void removeSelectedConsoleElement() {
      HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
      if (selected != null && !selected.locked) {
         this.edit("删除元素", () -> {
            int removedIndex = this.selectedScreen;
            this.screens.remove(removedIndex);
            this.selectedScreen = this.screens.isEmpty() ? -1 : Math.min(removedIndex, this.screens.size() - 1);
            this.consoleElementScroll = Math.min(this.consoleElementScroll, Math.max(0, this.screens.size() - 1));
         });
         this.clearFlyKeys();
         this.init();
      }
   }

   protected boolean canAddConsoleElement() {
      return !this.controlConsoleMode ? true : this.screens.size() < 4096;
   }

   protected String nextElementName(HolographicEditorScreenState.ElementType type) {
      long count = this.screens.stream().filter(element -> element.type == type).count() + 1L;
      return type.displayName + " " + count;
   }

   @Override
   protected void clearNumericPanelRefs() {
      this.numericDistanceBox = null;
      this.numericOffsetXBox = null;
      this.numericOffsetYBox = null;
      this.numericHeightBox = null;
      this.numericAspectBox = null;
      this.numericRollBox = null;
      this.numericYawBox = null;
      this.numericPitchBox = null;
      this.numericScaleXBox = null;
      this.numericScaleYBox = null;
      this.numericScaleZBox = null;
      this.numericPivotXBox = null;
      this.numericPivotYBox = null;
      this.numericPivotZBox = null;
      this.numericSkewXByYBox = null;
      this.numericSkewYByXBox = null;
      this.elementColorBox = null;
      this.elementTranslationColorBox = null;
      this.elementBackgroundColorBox = null;
      this.elementMaxWidthBox = null;
      this.consoleTrustedPlayersBox = null;
   }
}
