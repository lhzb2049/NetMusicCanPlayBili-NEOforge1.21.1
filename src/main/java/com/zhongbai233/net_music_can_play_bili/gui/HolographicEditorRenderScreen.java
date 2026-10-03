package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderState;
import com.zhongbai233.net_music_can_play_bili.client.renderer.gui.HolographicPreviewPipRenderer;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainHardRangeBounds;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.port.shim.PortGuiFramebufferBlitter;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainBounds;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Vector3f;

abstract class HolographicEditorRenderScreen extends HolographicConsoleInspectorScreen {
   protected HolographicEditorRenderScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(bindEquippedGlasses, controlConsolePos);
   }

   public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      g.fillGradient(0, 0, this.width, this.height, -805306368, -536541947);
   }

   public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      super.render(g, mouseX, mouseY, partialTick);
      if (this.controlConsoleMode) {
         this.drawControlConsolePanels(g, mouseX, mouseY);
      }

      this.drawPreview(g, this.previewX(), this.previewY(), this.previewW(), this.previewH(), mouseX, mouseY);
      this.drawEditorHud(g);
      if (this.showNumericPanel) {
         this.drawNumericPanel(g, mouseX, mouseY);
      }
   }

   protected void drawControlConsolePanels(GuiGraphics g, int mouseX, int mouseY) {
      int rightX = this.width - 226;
      g.fillGradient(0, 0, 156, this.height, -536344051, -535751912);
      g.fillGradient(rightX, 0, this.width, this.height, -536344051, -535751912);
      g.fillGradient(155, 0, 156, this.height, -9744622, -9744622);
      g.fillGradient(rightX, 0, rightX + 1, this.height, -9744622, -9744622);
      g.drawString(this.font, Component.literal("元素层级"), 10, 10, -2840509);
      g.drawString(this.font, Component.literal("场景元素"), 10, 21, -10463160);
      int x = rightX + 12;
      g.drawString(this.font, Component.literal("检查器"), x, 10, -2840509);
      ControlConsoleDocument document = this.currentConsoleDocument();
      HolographicEditorScreenState.PreviewScreenSpec screen = this.selectedScreenOrNull();
      if (screen != null) {
         g.drawString(
            this.font, Component.literal(BlackGoldUi.ellipsize(this.font, screen.type.displayName + " / " + screen.type.englishName, 202)), x, 34, -6252408
         );
         g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, "名称：" + screen.name, 202)), x, 52, -10463160);

         String typeHint = switch (screen.type) {
            case SCREEN -> "媒体画面元素";
            case SUBTITLE -> "字幕覆盖元素";
            case AUDIO -> "空间音频元素";
         };
         g.drawString(this.font, Component.literal(typeHint), x, 68, -10463160);
         g.drawString(this.font, Component.literal("位置 / 尺寸 / 旋转 / 高级变换"), x, 82, -10463160);
         String[][] labels = new String[][]{{"距离", "位置X"}, {"位置Y", "高度"}, {"比例", "Yaw"}, {"Pitch", "Roll"}};

         for (int row = 0; row < labels.length; row++) {
            g.drawString(this.font, Component.literal(labels[row][0]), rightX + 12, 117 + row * 22, -6252408);
            g.drawString(this.font, Component.literal(labels[row][1]), rightX + 122, 117 + row * 22, -6252408);
         }

         if (this.showTransformInspector) {
            String[][] advancedLabels = new String[][]{{"缩放X", "缩放Y"}, {"缩放Z", "枢轴X"}, {"枢轴Y", "枢轴Z"}, {"X←Y", "Y←X"}};

            for (int row = 0; row < advancedLabels.length; row++) {
               g.drawString(this.font, Component.literal(advancedLabels[row][0]), rightX + 12, 211 + row * 22, -6252408);
               g.drawString(this.font, Component.literal(advancedLabels[row][1]), rightX + 122, 211 + row * 22, -6252408);
            }

            g.drawString(
               this.font,
               Component.literal(screen.type == HolographicEditorScreenState.ElementType.AUDIO ? "音源首版忽略 scale / pivot / skew" : "完整仿射变换"),
               x,
               288,
               -10463160
            );
         } else {
            g.drawString(this.font, Component.literal("元素内容"), x, 194, -10463160);
         }
      } else if (document != null) {
         g.drawString(this.font, Component.literal("中控台 / Console"), x, 34, -6252408);
         g.drawString(this.font, Component.literal("名称"), x, 67, -6252408);
         g.drawString(
            this.font, Component.literal(document.hasSourceBinding() ? "媒体源：已绑定" : "媒体源：未绑定"), x, 86, document.hasSourceBinding() ? -9054560 : -10463160
         );
         g.drawString(this.font, Component.literal("硬范围 X / Y / Z"), x, 96, -10463160);
         g.drawString(this.font, Component.literal("访问权限"), x, 138, -10463160);
         g.drawString(this.font, Component.literal("文档 Rev：" + document.revision()), x, 178, -6252408);
         g.drawString(
            this.font, Component.literal("所有者：" + (document.ownerId() != null ? document.ownerId().toString().substring(0, 8) : "未认领")), x, 190, -10463160
         );
         g.drawString(this.font, Component.literal("可信玩家 UUID（逗号分隔）"), x, 204, -10463160);
      }

      if (!this.consoleSaveStatus.isEmpty()) {
         int statusColor = this.consoleSaveConflict ? -30091 : -6252408;
         g.drawString(
            this.font, Component.literal(BlackGoldUi.ellipsize(this.font, this.consoleSaveStatus, 202)), x, this.consoleSaveConflict ? 268 : 242, statusColor
         );
      }
   }

   protected void drawPreview(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
      g.fillGradient(x, y, x + w, y + h, -16250355, -15658216);
      g.fillGradient(x, y, x + w, y + 1, -9744622, -9744622);
      g.fillGradient(x, y + h - 1, x + w, y + h, -14015726, -14015726);
      String editorTitle = this.controlConsolePos != null ? "中控台场景建模" : "全息屏幕编辑";
      String modeTitle = this.navigationMode() ? "视口导航：WASD移动  Space/C升降  拖动画面观察  点击元素进入建模" : editorTitle + "：右上角选择移动/旋转/缩放工具";
      g.drawString(
         this.font,
         Component.literal(BlackGoldUi.ellipsize(this.font, this.firstPersonPreview ? "第一人称预览：点击画面返回" : modeTitle, Math.max(0, w - 20))),
         x + 10,
         y + 8,
         -6252408
      );
      if (!this.firstPersonPreview && this.controlConsolePos != null) {
         g.drawString(
            this.font, Component.literal(BlackGoldUi.ellipsize(this.font, this.controlConsoleSourceLabel(), Math.max(0, w - 20))), x + 10, y + 17, -10463160
         );
      }

      int pipX = x + 1;
      int pipY = y + 26;
      int pipW = w - 2;
      int pipH = h - 54;
      CameraFrame cameraFrame = this.firstPersonPreview
         ? this.createFirstPersonCameraFrame(pipX, pipY, pipW, pipH)
         : this.createOrbitCameraFrame(pipX, pipY, pipW, pipH);
      HolographicEditorScreenState.SceneHit sceneHit = this.firstPersonPreview
         ? HolographicEditorScreenState.SceneHit.none()
         : this.sceneHitAt(mouseX, mouseY, cameraFrame);
      boolean playerHovered = sceneHit.type == HolographicEditorScreenState.SceneHitType.PLAYER;
      this.submitPipPreview(g, pipX, pipY, pipW, pipH, mouseX, mouseY, playerHovered, cameraFrame);
      if (playerHovered && !this.controlConsoleMode) {
         g.drawCenteredString(this.font, Component.literal("点击进入第一人称"), x + w / 2, y + 31, -4196353);
      }

      if (this.firstPersonPreview) {
         this.drawFirstPersonCrosshair(g, pipX, pipY, pipW, pipH);
      } else {
         this.drawOrientationWidget(g, pipX + pipW - 30, pipY + pipH - 40);
      }
   }

   protected void drawEditorHud(GuiGraphics g) {
      int buttonCount = this.controlConsoleMode ? 6 : 5;
      int barW = 25 * buttonCount - 3 + 6;
      int barX = this.width - barW - 4;
      g.fillGradient(barX, 2, barX + barW + 2, 24, -804779507, -804187368);
      outline(g, barX, 2, barW, 22, -2142902273);
      int activeIndex = this.activeTool.ordinal();
      int ax = barX + 2 + activeIndex * 25;
      if (!this.navigationMode()) {
         outline(g, ax - 1, 3, 24, 20, -12195841);
      }

      HolographicEditorScreenState.PreviewScreenSpec screen = this.selectedScreenOrNull();
      String var10000;
      if (this.navigationMode()) {
         var10000 = "漫游：WASD 移动 · Space/C 升降 · Shift 加速 · V 世界漫游";
      } else {
         switch (this.activeTool) {
            case MOVE:
               var10000 = "移动（" + this.coordinateSpaceLabel() + "）：拖动轴 · 空白处旋转视角 · 右键平移视角";
               break;
            case ROTATE:
               var10000 = "旋转（" + this.coordinateSpaceLabel() + "）：拖动圆环 · 空白处旋转视角 · 右键平移视角";
               break;
            case SCALE:
               var10000 = "缩放（" + this.coordinateSpaceLabel() + "）：拖动轴 · 空白处旋转视角 · 右键平移视角";
               break;
            default:
               throw new MatchException(null, null);
         }
      }

      String toolTip = var10000;
      int hudX = this.controlConsoleMode ? this.previewX() + 10 : 10;
      int hudWidth = this.controlConsoleMode ? Math.max(0, this.previewW() - 20) : Math.max(0, this.width - 20);
      g.drawString(this.font, Component.literal(BlackGoldUi.ellipsize(this.font, toolTip, hudWidth)), hudX, this.height - 34, -6252408);
      String projection = this.previewCamera.mode() == EditorCameraMode.ORTHOGRAPHIC ? "正交" : "透视";
      String selectionInfo = this.navigationMode()
         ? "场景漫游"
         : (
            screen == null
               ? "中控台"
               : screen.type.displayName
                  + " · "
                  + screen.name
                  + " · X "
                  + fmt(screen.offsetX)
                  + " · Y "
                  + fmt(screen.offsetY)
                  + " · 距离 "
                  + fmt(screen.distance)
                  + " · 高 "
                  + fmt(screen.height)
         );
      g.drawString(
         this.font,
         Component.literal(BlackGoldUi.ellipsize(this.font, projection + " · F 聚焦 · 1-6 视图 · O/P 投影  |  " + selectionInfo, hudWidth)),
         hudX,
         this.height - 18,
         -10463160
      );
   }

   protected String controlConsoleSourceLabel() {
      ControlConsoleDocument document = this.controlConsoleDocument();
      return document != null && document.hasSourceBinding()
         ? "媒体源：" + document.sourceDimension() + "  " + document.sourceX() + ", " + document.sourceY() + ", " + document.sourceZ()
         : "媒体源：未绑定（拿中控台右键唱片机/直播机后再放置）";
   }

   @Override
   protected ControlConsoleDocument controlConsoleDocument() {
      if (this.controlConsolePos != null && this.minecraft != null && this.minecraft.level != null) {
         return this.minecraft.level.getBlockEntity(this.controlConsolePos) instanceof ControlConsoleBlockEntity console ? console.document() : null;
      } else {
         return null;
      }
   }

   protected void drawFirstPersonCrosshair(GuiGraphics g, int x, int y, int w, int h) {
      int cx = x + w / 2;
      int cy = y + h / 2;
      int arm = 7;
      int gap = 3;
      int shadow = -1073741824;
      int line = -385875969;
      int accent = -12195841;
      g.fillGradient(cx - arm - 1, cy - 1, cx - gap + 1, cy + 2, shadow, shadow);
      g.fillGradient(cx + gap - 1, cy - 1, cx + arm + 2, cy + 2, shadow, shadow);
      g.fillGradient(cx - 1, cy - arm - 1, cx + 2, cy - gap + 1, shadow, shadow);
      g.fillGradient(cx - 1, cy + gap - 1, cx + 2, cy + arm + 2, shadow, shadow);
      g.fillGradient(cx - arm, cy, cx - gap, cy + 1, line, line);
      g.fillGradient(cx + gap, cy, cx + arm + 1, cy + 1, line, line);
      g.fillGradient(cx, cy - arm, cx + 1, cy - gap, line, line);
      g.fillGradient(cx, cy + gap, cx + 1, cy + arm + 1, line, line);
      g.fillGradient(cx, cy, cx + 1, cy + 1, accent, accent);
   }

   protected void drawOrientationWidget(GuiGraphics g, int centerX, int centerY) {
      int panelRadius = 25;
      drawDisc(g, centerX, centerY, panelRadius, -1606793640);
      drawDisc(g, centerX, centerY, panelRadius - 1, -804581868);
      Matrix4f view = CameraMatrices.create(this.previewCamera, new EditorViewport(0, 0, 1, 1)).view();
      List<HolographicEditorScreenState.OrientationAxis> axes = new ArrayList<>(6);
      addOrientationAxis(axes, view, new Vector3f(1.0F, 0.0F, 0.0F), "X", -2932158, true);
      addOrientationAxis(axes, view, new Vector3f(-1.0F, 0.0F, 0.0F), "", -2932158, false);
      addOrientationAxis(axes, view, new Vector3f(0.0F, 1.0F, 0.0F), "Y", -12663717, true);
      addOrientationAxis(axes, view, new Vector3f(0.0F, -1.0F, 0.0F), "", -12663717, false);
      addOrientationAxis(axes, view, new Vector3f(0.0F, 0.0F, 1.0F), "Z", -12881708, true);
      addOrientationAxis(axes, view, new Vector3f(0.0F, 0.0F, -1.0F), "", -12881708, false);
      axes.sort(Comparator.comparingDouble(axisx -> axisx.depth));

      for (HolographicEditorScreenState.OrientationAxis axis : axes) {
         if (axis.depth < 0.0) {
            this.drawOrientationAxis(g, centerX, centerY, axis, false);
         }
      }

      drawDisc(g, centerX, centerY, 3, -14341322);

      for (HolographicEditorScreenState.OrientationAxis axisx : axes) {
         if (axisx.depth >= 0.0) {
            this.drawOrientationAxis(g, centerX, centerY, axisx, true);
         }
      }
   }

   protected void drawOrientationAxis(GuiGraphics g, int centerX, int centerY, HolographicEditorScreenState.OrientationAxis axis, boolean front) {
      int endX = centerX + (int)Math.round(axis.screenX * 18.0);
      int endY = centerY + (int)Math.round(axis.screenY * 18.0);
      int color = front ? axis.color : dimColor(axis.color);
      drawLine(g, centerX, centerY, endX, endY, dimColor(color));
      drawDisc(g, endX, endY, front ? 5 : 3, color);
      if (!axis.label.isEmpty()) {
         int labelColor = front ? -15723752 : -10063744;
         g.drawCenteredString(this.font, Component.literal(axis.label), endX, endY - 3, labelColor);
      }
   }

   protected static void addOrientationAxis(
      List<HolographicEditorScreenState.OrientationAxis> axes, Matrix4f view, Vector3f worldAxis, String label, int color, boolean positive
   ) {
      Vector3f cameraAxis = view.transformDirection(new Vector3f(worldAxis)).normalize();
      axes.add(new HolographicEditorScreenState.OrientationAxis(cameraAxis.x, -cameraAxis.y, cameraAxis.z, label, color, positive));
   }

   protected static void drawLine(GuiGraphics g, int startX, int startY, int endX, int endY, int color) {
      int x = startX;
      int y = startY;
      int dx = Math.abs(endX - startX);
      int sx = startX < endX ? 1 : -1;
      int dy = -Math.abs(endY - startY);
      int sy = startY < endY ? 1 : -1;
      int error = dx + dy;

      while (true) {
         g.fillGradient(x, y, x + 1, y + 1, color, color);
         if (x == endX && y == endY) {
            return;
         }

         int doubled = error * 2;
         if (doubled >= dy) {
            error += dy;
            x += sx;
         }

         if (doubled <= dx) {
            error += dx;
            y += sy;
         }
      }
   }

   protected static void drawDisc(GuiGraphics g, int centerX, int centerY, int radius, int color) {
      for (int y = -radius; y <= radius; y++) {
         int halfWidth = (int)Math.floor(Math.sqrt(radius * radius - y * y));
         g.fillGradient(centerX - halfWidth, centerY + y, centerX + halfWidth + 1, centerY + y + 1, color, color);
      }
   }

   protected static int dimColor(int color) {
      return color & 0xFF000000 | (color >>> 16 & 0xFF) / 2 << 16 | (color >>> 8 & 0xFF) / 2 << 8 | (color & 0xFF) / 2;
   }

   protected double orbitSensitivityScale() {
      double visibleScale;
      if (this.previewCamera.mode() == EditorCameraMode.ORTHOGRAPHIC) {
         double defaultOrthoScale = 6.5 * Math.tan(Math.toRadians(45.0) * 0.5);
         visibleScale = this.previewCamera.orthoScale() / defaultOrthoScale;
      } else {
         visibleScale = this.previewCamera.position().distance(this.previewCamera.focus()) / 6.5;
      }

      double sensitivity = Math.sqrt(Math.max(1.0E-4, visibleScale));
      return Double.isFinite(sensitivity) ? sensitivity : 1.0;
   }

   protected void submitPipPreview(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY, boolean playerHovered, CameraFrame cameraFrame) {
      Minecraft minecraft = Minecraft.getInstance();
      Player player = minecraft.player;
      if (player != null && w > 0 && h > 0) {
         float[] distances = new float[this.screens.size()];
         float[] offsetXs = new float[this.screens.size()];
         float[] offsetYs = new float[this.screens.size()];
         float[] heights = new float[this.screens.size()];
         float[] aspects = new float[this.screens.size()];
         float[] yaws = new float[this.screens.size()];
         float[] pitches = new float[this.screens.size()];
         float[] rolls = new float[this.screens.size()];
         float[] scaleXs = new float[this.screens.size()];
         float[] scaleYs = new float[this.screens.size()];
         float[] scaleZs = new float[this.screens.size()];
         float[] pivotXs = new float[this.screens.size()];
         float[] pivotYs = new float[this.screens.size()];
         float[] pivotZs = new float[this.screens.size()];
         float[] skewXByYs = new float[this.screens.size()];
         float[] skewYByXs = new float[this.screens.size()];
         int[] elementTypes = new int[this.screens.size()];

         for (int i = 0; i < this.screens.size(); i++) {
            HolographicEditorScreenState.PreviewScreenSpec spec = this.screens.get(i);
            distances[i] = spec.distance;
            offsetXs[i] = spec.offsetX;
            offsetYs[i] = spec.offsetY;
            heights[i] = spec.height;
            aspects[i] = spec.aspect;
            yaws[i] = spec.yaw;
            pitches[i] = spec.pitch;
            rolls[i] = spec.roll;
            scaleXs[i] = spec.scaleX;
            scaleYs[i] = spec.scaleY;
            scaleZs[i] = spec.scaleZ;
            pivotXs[i] = spec.pivotX;
            pivotYs[i] = spec.pivotY;
            pivotZs[i] = spec.pivotZ;
            skewXByYs[i] = spec.skewXByY;
            skewYByXs[i] = spec.skewYByX;
            elementTypes[i] = spec.type.ordinal();
         }

         float fov = ((Integer)minecraft.options.fov().get()).intValue();
         float pipScale = Math.min(w, h) * (this.previewScale / 200.0F);
         this.lastOrbitCameraFrame = cameraFrame;
         int hoveredHandle = !this.firstPersonPreview && this.selectedElementEditable()
            ? this.gizmoHandleAt(mouseX, mouseY, x, y, w, h, cameraFrame).ordinal()
            : HolographicEditorScreenState.GizmoHandle.NONE.ordinal();
         int selectedHandle = this.dragMode == HolographicEditorScreenState.DragMode.GIZMO ? this.activeHandle.ordinal() : hoveredHandle;
         ControlConsoleDocument terrainDocument = this.currentConsoleDocument();
         if (this.controlConsoleMode && minecraft.level != null && this.controlConsolePos != null && terrainDocument != null) {
            TerrainBounds terrainBounds = TerrainHardRangeBounds.around(
               this.controlConsolePos.getX(),
               this.controlConsolePos.getY(),
               this.controlConsolePos.getZ(),
               terrainDocument.hardRangeX(),
               terrainDocument.hardRangeY(),
               terrainDocument.hardRangeZ(),
               minecraft.level.getMinBuildHeight(),
               minecraft.level.getMaxBuildHeight()
            );
            TerrainPreviewManager.update(minecraft.level, this.controlConsolePos, terrainBounds, this.terrainPreviewCenterLocal);
         }

         int gizmoType = this.selectedScreenOrNull() == null ? 0 : this.selectedScreenOrNull().type.ordinal();
         int encodedGizmoTool = this.activeTool.ordinal() | gizmoType << 8;
         HolographicPreviewPipRenderState pipState = new HolographicPreviewPipRenderState(
            player,
            new Vector3f(0.0F, 0.0F, 0.0F),
            1.0F,
            180.0F,
            35.0F,
            this.firstPersonPreview,
            fov,
            playerHovered && !this.controlConsoleMode,
            this.selectedScreen,
            distances,
            offsetXs,
            offsetYs,
            heights,
            aspects,
            yaws,
            pitches,
            rolls,
            elementTypes,
            scaleXs,
            scaleYs,
            scaleZs,
            pivotXs,
            pivotYs,
            pivotZs,
            skewXByYs,
            skewYByXs,
            encodedGizmoTool,
            selectedHandle,
            this.coordinateSpace == GizmoCoordinateSpace.LOCAL,
            this.controlConsoleMode,
            this.controlConsoleMode && this.controlConsolePos != null,
            this.controlConsolePos != null ? this.controlConsolePos.getX() : 0,
            this.controlConsolePos != null ? this.controlConsolePos.getY() : 0,
            this.controlConsolePos != null ? this.controlConsolePos.getZ() : 0,
            terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeX()) : 0.0F,
            terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeY()) : 0.0F,
            terrainDocument != null ? saturatedPositiveFloat(terrainDocument.hardRangeZ()) : 0.0F,
            TerrainPreviewManager.frame(),
            cameraFrame,
            x,
            y,
            x + w,
            y + h,
            pipScale
         );
         HolographicPreviewPipRenderer pip = HolographicPreviewPipRenderer.acquire();
         pip.render(pipState);
         PortGuiFramebufferBlitter.blit(g, pip.colorTextureId(), x, y, w, h);
         outline(g, x, y, w, h, 1615194111);
      }
   }

   protected static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
      g.fill(x, y, x + w, y + 1, color);
      g.fill(x, y + h - 1, x + w, y + h, color);
      g.fill(x, y, x + 1, y + h, color);
      g.fill(x + w - 1, y, x + w, y + h, color);
   }

   protected abstract int previewX();

   protected abstract int previewY();

   protected abstract int previewW();

   protected abstract int previewH();

   protected abstract void drawNumericPanel(GuiGraphics var1, int var2, int var3);

   protected abstract CameraFrame createFirstPersonCameraFrame(int var1, int var2, int var3, int var4);

   protected abstract CameraFrame createOrbitCameraFrame(int var1, int var2, int var3, int var4);

   protected abstract HolographicEditorScreenState.SceneHit sceneHitAt(double var1, double var3, CameraFrame var5);

   protected abstract HolographicEditorScreenState.GizmoHandle gizmoHandleAt(double var1, double var3, int var5, int var6, int var7, int var8, CameraFrame var9);
}
