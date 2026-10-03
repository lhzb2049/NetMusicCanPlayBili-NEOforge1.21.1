package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3d;

public class HolographicScreenConfigTestScreen extends HolographicEditorInputScreen {
   public HolographicScreenConfigTestScreen() {
      this(false, null);
   }

   public HolographicScreenConfigTestScreen(boolean bindEquippedGlasses) {
      this(bindEquippedGlasses, null);
   }

   private HolographicScreenConfigTestScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(bindEquippedGlasses, controlConsolePos);
      if (bindEquippedGlasses) {
         this.loadEquippedGlassesConfig();
      }
   }

   public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos) {
      HolographicScreenConfigTestScreen screen = new HolographicScreenConfigTestScreen(false, Objects.requireNonNull(pos, "pos"));
      screen.selectedScreen = -1;
      screen.initialFocusElement = -2;
      return screen;
   }

   public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos, int selectedElement) {
      HolographicScreenConfigTestScreen screen = forControlConsole(pos);
      screen.selectedScreen = Math.max(0, selectedElement);
      screen.initialFocusElement = screen.selectedScreen;
      return screen;
   }

   public static HolographicScreenConfigTestScreen forControlConsole(BlockPos pos, List<ControlConsoleRoamingSession.RoamingElement> elements) {
      HolographicScreenConfigTestScreen screen = forControlConsole(pos);
      screen.restoreRoamingElements(elements);
      return screen;
   }

   public static HolographicScreenConfigTestScreen forControlConsole(
      BlockPos pos, int selectedElement, List<ControlConsoleRoamingSession.RoamingElement> elements
   ) {
      HolographicScreenConfigTestScreen screen = forControlConsole(pos);
      screen.restoreRoamingElements(elements);
      screen.selectedScreen = Math.max(0, Math.min(screen.screens.size() - 1, selectedElement));
      screen.initialFocusElement = screen.selectedScreen;
      return screen;
   }

   @Override
   protected void addNumericPanelWidgets() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      int px = this.numericPanelX();
      int py = this.numericPanelY();
      int lblW = 28;
      int boxW = 38;
      int rstW = 14;
      int rowH = 16;
      int cellW = lblW + boxW + rstW + 4;
      int row1y = py + 20;
      int row2y = row1y + rowH + 4;
      this.numericDistanceBox = this.addNumericRow(px + 6, row1y, lblW, boxW, rstW, rowH, "前后", screen.distance, 0.8F, 5.0F, v -> screen.distance = v, 2.2F);
      this.numericOffsetXBox = this.addNumericRow(
         px + 6 + cellW, row1y, lblW, boxW, rstW, rowH, "左右", screen.offsetX, -2.0F, 2.0F, v -> screen.offsetX = v, 0.0F
      );
      this.numericOffsetYBox = this.addNumericRow(
         px + 6 + cellW * 2, row1y, lblW, boxW, rstW, rowH, "上下", screen.offsetY, -1.2F, 1.6F, v -> screen.offsetY = v, 0.05F
      );
      int pbtnW = 30;
      int pbtnGap = 3;
      int presetX1 = px + 6 + cellW * 3 + 6;
      this.addRenderableWidget(new BlackGoldButton(presetX1, row1y, pbtnW, rowH, Component.literal("右窗"), btn -> {
         this.edit("右窗预设", this::presetRight);
         this.syncNumericEditBoxes();
      }, -2840509));
      this.addRenderableWidget(new BlackGoldButton(presetX1 + pbtnW + pbtnGap, row1y, pbtnW, rowH, Component.literal("左窗"), btn -> {
         this.edit("左窗预设", this::presetLeft);
         this.syncNumericEditBoxes();
      }, -2840509));
      this.addRenderableWidget(new BlackGoldButton(presetX1 + (pbtnW + pbtnGap) * 2, row1y, pbtnW, rowH, Component.literal("影院"), btn -> {
         this.edit("影院预设", this::presetCinema);
         this.syncNumericEditBoxes();
      }, -2840509));
      this.numericHeightBox = this.addNumericRow(px + 6, row2y, lblW, boxW, rstW, rowH, "高度", screen.height, 0.25F, 2.2F, v -> screen.height = v, 0.75F);
      this.numericAspectBox = this.addNumericRow(
         px + 6 + cellW, row2y, lblW, boxW, rstW, rowH, "比例", screen.aspect, 1.0F, 2.4F, v -> screen.aspect = v, 1.7777778F
      );
      this.numericRollBox = this.addNumericRow(px + 6 + cellW * 2, row2y, lblW, boxW, rstW, rowH, "倾角", screen.roll, -90.0F, 90.0F, v -> screen.roll = v, 0.0F);
      this.addRenderableWidget(
         new BlackGoldButton(
            presetX1,
            row2y,
            pbtnW,
            rowH,
            Component.literal("正面"),
            btn -> this.setPreviewCamera(this.cameraController.standardView(this.previewCamera, StandardCameraView.FRONT, EDITOR_WORLD_UP)),
            -2840509
         )
      );
      this.addRenderableWidget(
         new BlackGoldButton(
            presetX1 + pbtnW + pbtnGap,
            row2y,
            pbtnW,
            rowH,
            Component.literal("侧面"),
            btn -> this.setPreviewCamera(this.cameraController.standardView(this.previewCamera, StandardCameraView.LEFT, EDITOR_WORLD_UP)),
            -2840509
         )
      );
      this.addRenderableWidget(new BlackGoldButton(presetX1 + (pbtnW + pbtnGap) * 2, row2y, pbtnW, rowH, Component.literal("重置"), btn -> {
         this.edit("重置元素", this::resetDefaults);
         this.syncNumericEditBoxes();
      }, -2840509));
   }

   private EditBox addNumericRow(
      int px, int y, int labelW, int boxW, int rstW, int rowH, String label, float value, float min, float max, Consumer<Float> onApply, float defaultVal
   ) {
      EditBox box = new EditBox(this.font, px + labelW + 2, y, boxW, rowH, Component.literal(label));
      box.setValue(fmt(value));
      box.setResponder(text -> {
         if (!this.syncingNumericEditBoxes) {
            try {
               float parsed = Float.parseFloat(text.trim());
               if (this.controlConsoleMode) {
                  boolean positive = "高度".equals(label) || "比例".equals(label);
                  if (Float.isFinite(parsed) && (!positive || parsed > 0.0F)) {
                     this.edit("设置" + label, () -> onApply.accept(parsed));
                  }
               } else {
                  float clamped = HolographicScreenSettings.clamp(parsed, min, max);
                  this.edit("设置" + label, () -> onApply.accept(clamped));
               }
            } catch (NumberFormatException var8x) {
            }
         }
      });
      this.addRenderableWidget(box);
      int rstX = px + labelW + boxW + 6;
      this.addRenderableWidget(new BlackGoldButton(rstX, y, rstW, rowH, Component.literal("↺"), btn -> {
         this.edit("重置" + label, () -> onApply.accept(defaultVal));
         box.setValue(fmt(defaultVal));
      }, -2840509));
      return box;
   }

   @Override
   protected void syncNumericEditBoxes() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.selectedScreenOrNull();
      if (screen != null) {
         this.syncingNumericEditBoxes = true;

         try {
            if (this.numericDistanceBox != null) {
               this.numericDistanceBox.setValue(fmt(screen.distance));
            }

            if (this.numericOffsetXBox != null) {
               this.numericOffsetXBox.setValue(fmt(screen.offsetX));
            }

            if (this.numericOffsetYBox != null) {
               this.numericOffsetYBox.setValue(fmt(screen.offsetY));
            }

            if (this.numericHeightBox != null) {
               this.numericHeightBox.setValue(fmt(screen.height));
            }

            if (this.numericAspectBox != null) {
               this.numericAspectBox.setValue(fmt(screen.aspect));
            }

            if (this.numericYawBox != null) {
               this.numericYawBox.setValue(fmt(screen.yaw));
            }

            if (this.numericPitchBox != null) {
               this.numericPitchBox.setValue(fmt(screen.pitch));
            }

            if (this.numericRollBox != null) {
               this.numericRollBox.setValue(fmt(screen.roll));
            }

            if (this.numericScaleXBox != null) {
               this.numericScaleXBox.setValue(fmt(screen.scaleX));
            }

            if (this.numericScaleYBox != null) {
               this.numericScaleYBox.setValue(fmt(screen.scaleY));
            }

            if (this.numericScaleZBox != null) {
               this.numericScaleZBox.setValue(fmt(screen.scaleZ));
            }

            if (this.numericPivotXBox != null) {
               this.numericPivotXBox.setValue(fmt(screen.pivotX));
            }

            if (this.numericPivotYBox != null) {
               this.numericPivotYBox.setValue(fmt(screen.pivotY));
            }

            if (this.numericPivotZBox != null) {
               this.numericPivotZBox.setValue(fmt(screen.pivotZ));
            }

            if (this.numericSkewXByYBox != null) {
               this.numericSkewXByYBox.setValue(fmt(screen.skewXByY));
            }

            if (this.numericSkewYByXBox != null) {
               this.numericSkewYByXBox.setValue(fmt(screen.skewYByX));
            }
         } finally {
            this.syncingNumericEditBoxes = false;
         }
      }
   }

   private int numericPanelX() {
      return 8;
   }

   private int numericPanelY() {
      return 24;
   }

   private int numericPanelH() {
      return this.showNumericPanel ? 60 : 0;
   }

   @Override
   protected void drawNumericPanel(GuiGraphics g, int mouseX, int mouseY) {
      int px = this.numericPanelX();
      int py = this.numericPanelY();
      int pw = this.width - 16;
      int ph = this.numericPanelH();
      g.fillGradient(px - 2, py - 2, px + pw + 2, py + ph + 2, 819243075, 819243075);
      g.fillGradient(px, py, px + pw, py + ph, -536344051, -535751912);
      g.fillGradient(px, py, px + pw, py + 18, -14935012, -14935012);
      g.fillGradient(px + 6, py + 17, px + pw - 6, py + 18, -9744622, -9744622);
      g.drawString(this.font, Component.literal("屏幕属性"), px + 8, py + 5, -2840509);
      g.drawString(this.font, Component.literal("数值 / 预设"), px + pw - 68, py + 5, -10463160);
      if (this.showNumericPanel) {
         int lblW = 28;
         int boxW = 38;
         int rstW = 14;
         int cellW = lblW + boxW + rstW + 4;
         int row1y = py + 20;
         int row2y = row1y + 20;
         String[] labels1 = new String[]{"前后", "左右", "上下"};
         String[] labels2 = new String[]{"高度", "比例", "倾角"};

         for (int i = 0; i < 3; i++) {
            g.drawString(this.font, Component.literal(labels1[i]), px + 6 + cellW * i, row1y + 5, -6252408);
            g.drawString(this.font, Component.literal(labels2[i]), px + 6 + cellW * i, row2y + 5, -6252408);
         }

         int presetX1 = px + 6 + cellW * 3 + 6;
         g.drawString(this.font, Component.literal("预设"), presetX1, row1y - 3, -10463160);
      }
   }

   @Override
   protected int previewX() {
      return this.controlConsoleMode ? 160 : 0;
   }

   @Override
   protected int previewY() {
      return this.showNumericPanel ? this.numericPanelY() + this.numericPanelH() + 4 : 0;
   }

   @Override
   protected int previewW() {
      return this.controlConsoleMode ? Math.max(1, this.width - 156 - 226 - 8) : this.width;
   }

   @Override
   protected int previewH() {
      return Math.max(1, this.height - this.previewY());
   }

   @Override
   protected CameraFrame orbitCameraFrameFor(int x, int y, int w, int h) {
      EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
      return this.lastOrbitCameraFrame != null && this.lastOrbitCameraFrame.viewport().equals(viewport)
         ? this.lastOrbitCameraFrame
         : this.createOrbitCameraFrame(x, y, w, h);
   }

   @Override
   protected CameraFrame createOrbitCameraFrame(int x, int y, int w, int h) {
      EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
      return new CameraFrame(CameraMatrices.create(this.previewCamera, viewport), viewport, this.previewCamera.mode());
   }

   @Override
   protected CameraFrame createFirstPersonCameraFrame(int x, int y, int w, int h) {
      EditorViewport viewport = new EditorViewport(x, y, Math.max(1, w), Math.max(1, h));
      float fov = ((Integer)Minecraft.getInstance().options.fov().get()).intValue();
      Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(fov), (float)viewport.aspectRatio(), 0.05F, 100.0F);
      Matrix4f view = new Matrix4f().translate(0.0F, 0.0F, -0.001F).scale(1.0F, -1.0F, -1.0F).translate(0.0F, -1.62F, 0.0F);
      return new CameraFrame(CameraMatrices.from(view, projection), viewport, EditorCameraMode.FIRST_PERSON);
   }

   @Override
   protected void setPreviewCamera(EditorCameraState camera) {
      this.previewCamera = Objects.requireNonNull(camera, "camera");
      if (this.navigationMode()) {
         this.navigationCamera = this.previewCamera;
      } else {
         this.modelingCamera = this.previewCamera;
      }

      this.lastOrbitCameraFrame = null;
   }

   @Override
   protected void focusSelectedScreen() {
      HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
      if (selected == null) {
         this.setPreviewCamera(
            this.cameraController.focus(this.previewCamera, new Vector3d(0.0, 0.5, 0.0), 1.0, this.currentPreviewViewport(), EDITOR_WORLD_UP)
         );
      } else {
         double halfHeight = selected.height * 0.5;
         double halfWidth = halfHeight * selected.aspect;
         double radius = Math.hypot(halfWidth, halfHeight);
         this.setPreviewCamera(
            this.cameraController
               .focus(
                  this.previewCamera,
                  new Vector3d(selected.offsetX, 1.55 + selected.offsetY, selected.distance),
                  radius,
                  this.currentPreviewViewport(),
                  EDITOR_WORLD_UP
               )
         );
         this.syncLegacyPreviewScale();
      }
   }

   @Override
   protected void focusControlConsoleCenter() {
      this.setPreviewCamera(this.cameraController.focus(this.previewCamera, new Vector3d(0.0, 0.5, 0.0), 1.25, this.currentPreviewViewport(), EDITOR_WORLD_UP));
      this.syncLegacyPreviewScale();
   }

   @Override
   protected void switchProjection(EditorCameraMode targetMode) {
      this.setPreviewCamera(this.cameraController.switchProjection(this.previewCamera, targetMode));
      this.syncLegacyPreviewScale();
   }

   @Override
   protected EditorViewport currentPreviewViewport() {
      return new EditorViewport(this.previewX() + 1, this.previewY() + 26, Math.max(1, this.previewW() - 2), Math.max(1, this.previewH() - 54));
   }

   @Override
   protected void syncLegacyPreviewScale() {
      double distance = Math.max(1.0E-4, this.previewCamera.position().distance(this.previewCamera.focus()));
      double scale = 247.0 / distance;
      this.previewScale = (float)Math.min(Float.MAX_VALUE, Math.max(Float.MIN_NORMAL, scale));
   }
}
