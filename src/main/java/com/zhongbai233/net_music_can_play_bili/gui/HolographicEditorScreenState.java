package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraController;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.transaction.DragTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

abstract class HolographicEditorScreenState extends Screen {
   protected static final int GOLD = -2840509;
   protected static final int GOLD_DIM = -9744622;
   protected static final int BG_HEADER = -14935012;
   protected static final int TEXT_SECONDARY = -6252408;
   protected static final int TEXT_DIM = -10463160;
   protected static final float DEFAULT_PREVIEW_SCALE = 38.0F;
   protected static final double MIN_CAMERA_SCALE = 1.0E-4;
   protected static final double MAX_CAMERA_SCALE = 1000000.0;
   protected static final float EDITOR_FAR_PLANE = 1000000.0F;
   protected static final float PLAYER_HEAD_RELATIVE_YAW = 0.0F;
   protected static final float DEFAULT_PREVIEW_YAW = 180.0F;
   protected static final float DEFAULT_PREVIEW_PITCH = 35.0F;
   protected static final double ORBIT_FOV_DEGREES = 45.0;
   protected static final double ORBIT_DEFAULT_CAMERA_DISTANCE = 6.5;
   protected static final double ORBIT_TARGET_Y = 0.9F;
   protected static final double CONTROL_CONSOLE_INITIAL_FOCUS_RADIUS = 1.25;
   protected static final int ICON_W = 22;
   protected static final int ICON_H = 18;
   protected static final int ICON_GAP = 3;
   protected static final int GIZMO_HIT_RADIUS = 7;
   protected static final double GIZMO_AXIS_WORLD_LEN = 0.72F;
   protected static final int GIZMO_RING_SEGMENTS = 48;
   protected static final double ORBIT_YAW_DEGREES_PER_PIXEL = 0.35;
   protected static final double ORBIT_PITCH_DEGREES_PER_PIXEL = 0.3;
   protected static final double PAN_SENSITIVITY = 0.82;
   protected static final int ORIENTATION_WIDGET_RADIUS = 18;
   protected static final int ORIENTATION_WIDGET_MARGIN_RIGHT = 30;
   protected static final int ORIENTATION_WIDGET_MARGIN_BOTTOM = 40;
   protected static final int CONTROL_LEFT_PANEL_W = 156;
   protected static final int CONTROL_RIGHT_PANEL_W = 226;
   protected static final int CONTROL_PANEL_GAP = 4;
   protected static final Vector3d EDITOR_WORLD_UP = new Vector3d(0.0, 1.0, 0.0);
   protected static final Vector3d PREVIEW_PLAYER_BOUNDS_MIN = new Vector3d(-0.345, -0.03, -0.345);
   protected static final Vector3d PREVIEW_PLAYER_BOUNDS_MAX = new Vector3d(0.345, 1.88, 0.345);
   protected final List<HolographicEditorScreenState.PreviewScreenSpec> screens = new ArrayList<>();
   protected int selectedScreen;
   protected int consoleElementScroll;
   protected final boolean bindEquippedGlasses;
   protected final BlockPos controlConsolePos;
   protected final boolean controlConsoleMode;
   protected final Level controlConsoleLevel;
   protected final Player controlConsolePlayer;
   protected HolographicEditorScreenState.EditTool activeTool = HolographicEditorScreenState.EditTool.MOVE;
   protected GizmoCoordinateSpace coordinateSpace = GizmoCoordinateSpace.LOCAL;
   protected HolographicEditorScreenState.DragMode dragMode = HolographicEditorScreenState.DragMode.NONE;
   protected HolographicEditorScreenState.GizmoHandle activeHandle = HolographicEditorScreenState.GizmoHandle.NONE;
   protected float previewScale = 38.0F;
   protected final EditorCameraController cameraController = new EditorCameraController(
      new EditorCameraController.Settings(0.08, 1.0E-4, 1000000.0, 1.0E-4, 1000000.0, 8.0, 2.0, 0.05, 1.15)
   );
   protected EditorCameraState previewCamera = legacyOrbitCamera(180.0, 35.0, 6.5, new Vector3d(0.0, 0.9F, 0.0));
   protected EditorCameraState modelingCamera = this.previewCamera;
   protected EditorCameraState navigationCamera;
   protected boolean draggingPreview;
   protected int previewDragButton = -1;
   protected boolean firstPersonPreview;
   protected double lastMouseX;
   protected double lastMouseY;
   protected double previewClickX;
   protected double previewClickY;
   protected boolean previewClickHitPlayer;
   protected boolean previewDragStartedWithoutElement;
   protected CameraFrame lastOrbitCameraFrame;
   protected boolean flyForward;
   protected boolean flyBackward;
   protected boolean flyLeft;
   protected boolean flyRight;
   protected boolean flyDown;
   protected boolean flyUp;
   protected boolean flyFast;
   protected HolographicEditorScreenState.GizmoDragSession gizmoDragSession;
   protected final CommandStack<HolographicEditorScreenState.EditorSceneState> editHistory = new CommandStack<>(128);
   protected DragTransaction<HolographicEditorScreenState.EditorSceneState> gizmoTransaction;
   protected boolean showNumericPanel;
   protected boolean showTransformInspector;
   protected EditBox numericDistanceBox;
   protected EditBox numericOffsetXBox;
   protected EditBox numericOffsetYBox;
   protected EditBox numericHeightBox;
   protected EditBox numericAspectBox;
   protected EditBox numericRollBox;
   protected EditBox numericYawBox;
   protected EditBox numericPitchBox;
   protected EditBox numericScaleXBox;
   protected EditBox numericScaleYBox;
   protected EditBox numericScaleZBox;
   protected EditBox numericPivotXBox;
   protected EditBox numericPivotYBox;
   protected EditBox numericPivotZBox;
   protected EditBox numericSkewXByYBox;
   protected EditBox numericSkewYByXBox;
   protected EditBox elementTextBox;
   protected EditBox elementTextScaleBox;
   protected HolographicEditorScreenState.ElementVolumeSlider elementVolumeSlider;
   protected HolographicEditorScreenState.ElementBrightnessSlider elementBrightnessSlider;
   protected EditBox elementMaxDistanceBox;
   protected EditBox elementColorBox;
   protected EditBox elementTranslationColorBox;
   protected EditBox elementBackgroundColorBox;
   protected EditBox elementMaxWidthBox;
   protected EditBox consoleTrustedPlayersBox;
   protected boolean syncingNumericEditBoxes;
   protected ControlConsoleDocument.AccessMode consoleAccessModeDraft;
   protected ControlConsoleDocument consoleAccessRollback;
   protected ControlConsoleDocument consoleDraft;
   protected boolean consoleElementsLoaded;
   protected boolean roamingHistoryPending;
   protected long consoleAutosaveTick;
   protected int consoleSavedFingerprint;
   protected int consolePendingFingerprint;
   protected int consoleObservedFingerprint;
   protected boolean consoleAutosaveFingerprintInitialized;
   protected UUID consolePendingOperation;
   protected boolean consoleSaveConflict;
   protected ControlConsoleDocument consoleConflictAuthoritative;
   protected String consoleSaveStatus = "";
   protected boolean worldRoamingTransitionPending;
   protected boolean transferLeaseToRoaming;
   protected int initialFocusElement = -1;
   protected Vector3d terrainPreviewCenterLocal = new Vector3d(0.0, 0.5, 0.0);

   protected HolographicEditorScreenState(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(Component.literal(controlConsolePos != null ? "中控台场景建模" : (bindEquippedGlasses ? "全息眼镜配置" : "全息屏幕配置测试")));
      this.bindEquippedGlasses = bindEquippedGlasses;
      this.controlConsolePos = controlConsolePos != null ? controlConsolePos.immutable() : null;
      this.controlConsoleMode = controlConsolePos != null;
      Minecraft minecraft = Minecraft.getInstance();
      this.controlConsoleLevel = this.controlConsoleMode ? minecraft.level : null;
      this.controlConsolePlayer = this.controlConsoleMode ? minecraft.player : null;
      if (!this.controlConsoleMode) {
         this.screens.add(HolographicEditorScreenState.PreviewScreenSpec.defaults());
      }
   }

   protected abstract void editSelected(String var1, Consumer<HolographicEditorScreenState.PreviewScreenSpec> var2);

   protected static int documentFingerprint(ControlConsoleDocument document) {
      return Objects.hash(document.displayName(), document.hardRangeX(), document.hardRangeY(), document.hardRangeZ(), document.elements());
   }

   protected static float saturatedPositiveFloat(double value) {
      return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float)Math.max(0.0, value);
   }

   protected static HolographicEditorScreenState.ScreenSnapshot snapshot(HolographicEditorScreenState.PreviewScreenSpec screen) {
      return new HolographicEditorScreenState.ScreenSnapshot(
         screen.elementId,
         screen.type,
         screen.name,
         screen.distance,
         screen.offsetX,
         screen.offsetY,
         screen.height,
         screen.aspect,
         screen.yaw,
         screen.pitch,
         screen.roll,
         screen.scaleX,
         screen.scaleY,
         screen.scaleZ,
         screen.pivotX,
         screen.pivotY,
         screen.pivotZ,
         screen.skewXByY,
         screen.skewYByX,
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
         screen.brightness
      );
   }

   protected static HolographicEditorScreenState.ScreenSnapshot snapshot(ControlConsoleElement element) {
      HolographicEditorScreenState.ElementType type = switch (element.type()) {
         case SCREEN -> HolographicEditorScreenState.ElementType.SCREEN;
         case SUBTITLE -> HolographicEditorScreenState.ElementType.SUBTITLE;
         case AUDIO -> HolographicEditorScreenState.ElementType.AUDIO;
      };
      return new HolographicEditorScreenState.ScreenSnapshot(
         element.elementId(),
         type,
         element.name(),
         element.distance(),
         element.offsetX(),
         element.offsetY(),
         element.height(),
         element.aspect(),
         element.yaw(),
         element.pitch(),
         element.roll(),
         element.scaleX(),
         element.scaleY(),
         element.scaleZ(),
         element.pivotX(),
         element.pivotY(),
         element.pivotZ(),
         element.skewXByY(),
         element.skewYByX(),
         element.contentMode(),
         element.text(),
         element.followLyrics(),
         element.showTranslation(),
         element.textScale(),
         element.color(),
         element.volume(),
         element.channelIndex(),
         element.maxDistance(),
         element.autoMixJoc(),
         element.translationColor(),
         element.backgroundColor(),
         element.alignment(),
         element.maxWidth(),
         element.wrap(),
         element.enabled(),
         element.locked(),
         element.brightness()
      );
   }

   protected static EditorCameraState legacyOrbitCamera(double yawDegrees, double pitchDegrees, double distance, Vector3d focus) {
      Matrix4f view = new Matrix4f()
         .translate(0.0F, 0.0F, (float)(-distance))
         .rotateX((float)Math.toRadians(pitchDegrees))
         .rotateY((float)Math.toRadians(yawDegrees))
         .translate((float)(-focus.x), (float)(-focus.y), (float)(-focus.z));
      Matrix4f cameraWorld = view.invert(new Matrix4f());
      Vector3f position = cameraWorld.getTranslation(new Vector3f());
      Quaternionf orientation = cameraWorld.getNormalizedRotation(new Quaternionf());
      return new EditorCameraState(EditorCameraMode.ORBIT, new Vector3d(position), orientation, focus, 45.0F, 4.0F, 0.05F, 1000000.0F);
   }

   protected static String fmt(float value) {
      return Math.abs(value - Math.round(value)) < 0.001F ? Integer.toString(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
   }

   protected HolographicEditorScreenState.PreviewScreenSpec screen() {
      HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
      if (selected == null) {
         throw new IllegalStateException("no control console element is selected");
      } else {
         return selected;
      }
   }

   protected HolographicEditorScreenState.PreviewScreenSpec selectedScreenOrNull() {
      return this.selectedScreen >= 0 && this.selectedScreen < this.screens.size() ? this.screens.get(this.selectedScreen) : null;
   }

   protected String coordinateSpaceLabel() {
      return this.coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本地" : "世界";
   }

   protected static final class ConsoleProperties {
      final String displayName;
      final double hardRangeX;
      final double hardRangeY;
      final double hardRangeZ;

      ConsoleProperties(String displayName, double hardRangeX, double hardRangeY, double hardRangeZ) {
         this.displayName = displayName;
         this.hardRangeX = hardRangeX;
         this.hardRangeY = hardRangeY;
         this.hardRangeZ = hardRangeZ;
      }
   }

   protected static enum DragMode {
      NONE,
      CAMERA,
      PAN,
      GIZMO;
   }

   protected static enum EditTool {
      MOVE,
      ROTATE,
      SCALE;
   }

   protected static final class EditorSceneState {
      final List<HolographicEditorScreenState.ScreenSnapshot> screens;
      final int selectedScreen;
      final HolographicEditorScreenState.ConsoleProperties consoleProperties;

      EditorSceneState(
         List<HolographicEditorScreenState.ScreenSnapshot> screens, int selectedScreen, HolographicEditorScreenState.ConsoleProperties consoleProperties
      ) {
         this.screens = List.copyOf(screens);
         this.selectedScreen = selectedScreen;
         this.consoleProperties = consoleProperties;
      }
   }

   protected final class ElementBrightnessSlider extends AbstractSliderButton {
      protected ElementBrightnessSlider(int x, int y, int width, int height, float brightness) {
         super(x, y, width, height, Component.empty(), Math.clamp(brightness, 0.0F, 1.0F));
         this.updateMessage();
      }

      protected void updateMessage() {
         this.setMessage(Component.literal("亮度：" + Math.round(this.value * 100.0) + "%"));
      }

      protected void applyValue() {
         this.updateMessage();
      }

      public void onRelease(double mouseX, double mouseY) {
         super.onRelease(mouseX, mouseY);
         float brightness = (float)Math.clamp(this.value, 0.0, 1.0);
         HolographicEditorScreenState.this.editSelected("设置屏幕亮度", item -> item.brightness = brightness);
      }
   }

   protected static enum ElementType {
      SCREEN("▣", "屏幕", "Screen"),
      SUBTITLE("T", "字幕", "Subtitle"),
      AUDIO("♪", "音频", "Audio");

      protected final String symbol;
      protected final String displayName;
      protected final String englishName;

      private ElementType(String symbol, String displayName, String englishName) {
         this.symbol = symbol;
         this.displayName = displayName;
         this.englishName = englishName;
      }
   }

   protected final class ElementVolumeSlider extends AbstractSliderButton {
      protected ElementVolumeSlider(int x, int y, int width, int height, float volume) {
         super(x, y, width, height, Component.empty(), Math.clamp(volume, 0.0F, 1.0F));
         this.updateMessage();
      }

      protected void updateMessage() {
         this.setMessage(Component.literal("音量：" + Math.round(this.value * 100.0) + "%"));
      }

      protected void applyValue() {
         this.updateMessage();
      }

      public void onRelease(double mouseX, double mouseY) {
         super.onRelease(mouseX, mouseY);
         float volume = (float)Math.clamp(this.value, 0.0, 1.0);
         HolographicEditorScreenState.this.editSelected("设置音源音量", item -> item.volume = volume);
      }
   }

   protected static final class GizmoDragSession {
      final int screenIndex;
      final HolographicEditorScreenState.EditTool tool;
      final HolographicEditorScreenState.GizmoHandle handle;
      final CameraFrame cameraFrame;
      final HolographicEditorScreenState.ScreenSnapshot start;
      final Vector3d origin;
      final Vector3d localY;
      final Vector3d axis;
      final Vector3d constraintVector;
      final Vector3d startHit;
      final GizmoConstraint constraint;

      GizmoDragSession(
         int screenIndex,
         HolographicEditorScreenState.EditTool tool,
         HolographicEditorScreenState.GizmoHandle handle,
         CameraFrame cameraFrame,
         HolographicEditorScreenState.ScreenSnapshot start,
         Vector3d origin,
         Vector3d localY,
         Vector3d axis,
         GizmoConstraint constraint,
         Vector3d constraintVector,
         Vector3d startHit
      ) {
         this.screenIndex = screenIndex;
         this.tool = tool;
         this.handle = handle;
         this.cameraFrame = cameraFrame;
         this.start = start;
         this.origin = new Vector3d(origin);
         this.localY = new Vector3d(localY);
         this.axis = axis != null ? new Vector3d(axis) : null;
         this.constraint = constraint;
         this.constraintVector = new Vector3d(constraintVector);
         this.startHit = new Vector3d(startHit);
      }
   }

   protected static enum GizmoHandle {
      NONE,
      CENTER,
      X,
      Y,
      Z,
      RING_X,
      RING_Y,
      RING_Z;

      protected boolean isRotationRing() {
         return this == RING_X || this == RING_Y || this == RING_Z;
      }
   }

   protected static final class GizmoPoint {
      final double x;
      final double y;
      final double depth;
      final boolean visible;

      GizmoPoint(double x, double y, double depth, boolean visible) {
         this.x = x;
         this.y = y;
         this.depth = depth;
         this.visible = visible;
      }
   }

   protected static final class GizmoProjection {
      final HolographicEditorScreenState.GizmoPoint center;
      final HolographicEditorScreenState.GizmoPoint xAxis;
      final HolographicEditorScreenState.GizmoPoint yAxis;
      final HolographicEditorScreenState.GizmoPoint zAxis;
      final HolographicEditorScreenState.GizmoPoint[] ringX;
      final HolographicEditorScreenState.GizmoPoint[] ringY;
      final HolographicEditorScreenState.GizmoPoint[] ringZ;

      GizmoProjection(
         HolographicEditorScreenState.GizmoPoint center,
         HolographicEditorScreenState.GizmoPoint xAxis,
         HolographicEditorScreenState.GizmoPoint yAxis,
         HolographicEditorScreenState.GizmoPoint zAxis,
         HolographicEditorScreenState.GizmoPoint[] ringX,
         HolographicEditorScreenState.GizmoPoint[] ringY,
         HolographicEditorScreenState.GizmoPoint[] ringZ
      ) {
         this.center = center;
         this.xAxis = xAxis;
         this.yAxis = yAxis;
         this.zAxis = zAxis;
         this.ringX = ringX;
         this.ringY = ringY;
         this.ringZ = ringZ;
      }
   }

   protected static final class OrientationAxis {
      final double screenX;
      final double screenY;
      final double depth;
      final String label;
      final int color;
      final boolean positive;

      OrientationAxis(double screenX, double screenY, double depth, String label, int color, boolean positive) {
         this.screenX = screenX;
         this.screenY = screenY;
         this.depth = depth;
         this.label = label;
         this.color = color;
         this.positive = positive;
      }
   }

   protected static final class PreviewScreenSpec {
      protected final UUID elementId;
      protected final HolographicEditorScreenState.ElementType type;
      protected final String name;
      protected float distance;
      protected float offsetX;
      protected float offsetY;
      protected float height;
      protected float aspect;
      protected float yaw;
      protected float pitch;
      protected float roll;
      protected float scaleX;
      protected float scaleY;
      protected float scaleZ;
      protected float pivotX;
      protected float pivotY;
      protected float pivotZ;
      protected float skewXByY;
      protected float skewYByX;
      protected String contentMode;
      protected String text;
      protected boolean followLyrics;
      protected boolean showTranslation;
      protected float textScale;
      protected int color;
      protected float volume;
      protected int channelIndex;
      protected float maxDistance;
      protected boolean autoMixJoc;
      protected int translationColor;
      protected int backgroundColor;
      protected ControlConsoleElement.Alignment alignment;
      protected float maxWidth;
      protected boolean wrap;
      protected boolean enabled;
      protected boolean locked;
      protected float brightness;

      protected PreviewScreenSpec(
         HolographicEditorScreenState.ElementType type, String name, float distance, float offsetX, float offsetY, float height, float aspect, float roll
      ) {
         this(UUID.randomUUID(), type, name, distance, offsetX, offsetY, height, aspect, roll);
      }

      protected PreviewScreenSpec(
         UUID elementId,
         HolographicEditorScreenState.ElementType type,
         String name,
         float distance,
         float offsetX,
         float offsetY,
         float height,
         float aspect,
         float roll
      ) {
         this.elementId = Objects.requireNonNull(elementId, "elementId");
         this.type = type;
         this.name = name;
         this.distance = distance;
         this.offsetX = offsetX;
         this.offsetY = offsetY;
         this.height = height;
         this.aspect = aspect;
         this.yaw = 0.0F;
         this.pitch = 0.0F;
         this.roll = roll;
         this.scaleX = 1.0F;
         this.scaleY = 1.0F;
         this.scaleZ = 1.0F;
         this.contentMode = type == HolographicEditorScreenState.ElementType.SCREEN
            ? "SOURCE"
            : (type == HolographicEditorScreenState.ElementType.SUBTITLE ? "LYRICS" : "SOURCE");
         this.text = "";
         this.followLyrics = type == HolographicEditorScreenState.ElementType.SUBTITLE;
         this.showTranslation = true;
         this.textScale = 1.0F;
         this.color = -1;
         this.volume = 1.0F;
         this.channelIndex = 0;
         this.maxDistance = 32.0F;
         this.autoMixJoc = false;
         this.translationColor = -4663041;
         this.backgroundColor = 1073741824;
         this.alignment = ControlConsoleElement.Alignment.CENTER;
         this.maxWidth = 0.0F;
         this.wrap = false;
         this.enabled = true;
         this.brightness = 1.0F;
      }

      protected static HolographicEditorScreenState.PreviewScreenSpec defaults() {
         return defaultsWithName(HolographicEditorScreenState.ElementType.SCREEN, "主屏幕");
      }

      protected static HolographicEditorScreenState.PreviewScreenSpec defaultsWithName(HolographicEditorScreenState.ElementType type, String name) {
         HolographicGlassesItem.ScreenConfig config = HolographicGlassesItem.defaultScreenConfig();
         return new HolographicEditorScreenState.PreviewScreenSpec(
            type, name, config.distance(), config.offsetX(), config.offsetY(), config.height(), config.aspect(), config.roll()
         );
      }

      protected static HolographicEditorScreenState.PreviewScreenSpec fromSnapshot(HolographicEditorScreenState.ScreenSnapshot value) {
         HolographicEditorScreenState.PreviewScreenSpec screen = new HolographicEditorScreenState.PreviewScreenSpec(
            value.elementId, value.type, value.name, value.distance, value.offsetX, value.offsetY, value.height, value.aspect, value.roll
         );
         screen.yaw = value.yaw;
         screen.pitch = value.pitch;
         screen.scaleX = value.scaleX;
         screen.scaleY = value.scaleY;
         screen.scaleZ = value.scaleZ;
         screen.pivotX = value.pivotX;
         screen.pivotY = value.pivotY;
         screen.pivotZ = value.pivotZ;
         screen.skewXByY = value.skewXByY;
         screen.skewYByX = value.skewYByX;
         screen.contentMode = value.contentMode;
         screen.text = value.text;
         screen.followLyrics = value.followLyrics;
         screen.showTranslation = value.showTranslation;
         screen.textScale = value.textScale;
         screen.color = value.color;
         screen.volume = value.volume;
         screen.channelIndex = value.channelIndex;
         screen.maxDistance = value.maxDistance;
         screen.autoMixJoc = value.autoMixJoc;
         screen.translationColor = value.translationColor;
         screen.backgroundColor = value.backgroundColor;
         screen.alignment = value.alignment;
         screen.maxWidth = value.maxWidth;
         screen.wrap = value.wrap;
         screen.enabled = value.enabled;
         screen.locked = value.locked;
         screen.brightness = value.brightness;
         return screen;
      }

      protected HolographicEditorScreenState.PreviewScreenSpec copyWithName(String copyName) {
         HolographicEditorScreenState.PreviewScreenSpec copy = new HolographicEditorScreenState.PreviewScreenSpec(
            UUID.randomUUID(), this.type, copyName, this.distance, this.offsetX, this.offsetY, this.height, this.aspect, this.roll
         );
         copy.yaw = this.yaw;
         copy.pitch = this.pitch;
         copy.scaleX = this.scaleX;
         copy.scaleY = this.scaleY;
         copy.scaleZ = this.scaleZ;
         copy.pivotX = this.pivotX;
         copy.pivotY = this.pivotY;
         copy.pivotZ = this.pivotZ;
         copy.skewXByY = this.skewXByY;
         copy.skewYByX = this.skewYByX;
         copy.contentMode = this.contentMode;
         copy.text = this.text;
         copy.followLyrics = this.followLyrics;
         copy.showTranslation = this.showTranslation;
         copy.textScale = this.textScale;
         copy.color = this.color;
         copy.volume = this.volume;
         copy.channelIndex = this.channelIndex;
         copy.maxDistance = this.maxDistance;
         copy.autoMixJoc = this.autoMixJoc;
         copy.translationColor = this.translationColor;
         copy.backgroundColor = this.backgroundColor;
         copy.alignment = this.alignment;
         copy.maxWidth = this.maxWidth;
         copy.wrap = this.wrap;
         copy.enabled = this.enabled;
         copy.locked = false;
         copy.brightness = this.brightness;
         return copy;
      }

      protected static HolographicEditorScreenState.PreviewScreenSpec fromBinding(String fallbackName, HolographicGlassesItem.ScreenBinding binding) {
         String sourceName = binding.source() != null ? binding.source().shortName() : fallbackName;
         HolographicGlassesItem.ScreenConfig config = binding.config();
         return new HolographicEditorScreenState.PreviewScreenSpec(
            HolographicEditorScreenState.ElementType.SCREEN,
            fallbackName + " / " + sourceName,
            config.distance(),
            config.offsetX(),
            config.offsetY(),
            config.height(),
            config.aspect(),
            config.roll()
         );
      }

      protected HolographicGlassesItem.ScreenConfig toConfig() {
         return new HolographicGlassesItem.ScreenConfig(this.distance, this.offsetX, this.offsetY, this.height, this.aspect, this.roll);
      }
   }

   protected static final class SceneHit {
      final HolographicEditorScreenState.SceneHitType type;
      final int screenIndex;
      final double distance;

      SceneHit(HolographicEditorScreenState.SceneHitType type, int screenIndex, double distance) {
         this.type = type;
         this.screenIndex = screenIndex;
         this.distance = distance;
      }

      static HolographicEditorScreenState.SceneHit none() {
         return new HolographicEditorScreenState.SceneHit(HolographicEditorScreenState.SceneHitType.NONE, -1, Double.POSITIVE_INFINITY);
      }

      static HolographicEditorScreenState.SceneHit player(double distance) {
         return new HolographicEditorScreenState.SceneHit(HolographicEditorScreenState.SceneHitType.PLAYER, -1, distance);
      }

      static HolographicEditorScreenState.SceneHit screen(int screenIndex, double distance) {
         return new HolographicEditorScreenState.SceneHit(HolographicEditorScreenState.SceneHitType.SCREEN, screenIndex, distance);
      }
   }

   protected static enum SceneHitType {
      NONE,
      PLAYER,
      SCREEN;
   }

   protected static final class ScreenSnapshot {
      final UUID elementId;
      final HolographicEditorScreenState.ElementType type;
      final String name;
      final float distance;
      final float offsetX;
      final float offsetY;
      final float height;
      final float aspect;
      final float yaw;
      final float pitch;
      final float roll;
      final float scaleX;
      final float scaleY;
      final float scaleZ;
      final float pivotX;
      final float pivotY;
      final float pivotZ;
      final float skewXByY;
      final float skewYByX;
      final String contentMode;
      final String text;
      final boolean followLyrics;
      final boolean showTranslation;
      final float textScale;
      final int color;
      final float volume;
      final int channelIndex;
      final float maxDistance;
      final boolean autoMixJoc;
      final int translationColor;
      final int backgroundColor;
      final ControlConsoleElement.Alignment alignment;
      final float maxWidth;
      final boolean wrap;
      final boolean enabled;
      final boolean locked;
      final float brightness;

      ScreenSnapshot(
         UUID elementId,
         HolographicEditorScreenState.ElementType type,
         String name,
         float distance,
         float offsetX,
         float offsetY,
         float height,
         float aspect,
         float yaw,
         float pitch,
         float roll,
         float scaleX,
         float scaleY,
         float scaleZ,
         float pivotX,
         float pivotY,
         float pivotZ,
         float skewXByY,
         float skewYByX,
         String contentMode,
         String text,
         boolean followLyrics,
         boolean showTranslation,
         float textScale,
         int color,
         float volume,
         int channelIndex,
         float maxDistance,
         boolean autoMixJoc,
         int translationColor,
         int backgroundColor,
         ControlConsoleElement.Alignment alignment,
         float maxWidth,
         boolean wrap,
         boolean enabled,
         boolean locked,
         float brightness
      ) {
         this.elementId = elementId;
         this.type = type;
         this.name = name;
         this.distance = distance;
         this.offsetX = offsetX;
         this.offsetY = offsetY;
         this.height = height;
         this.aspect = aspect;
         this.yaw = yaw;
         this.pitch = pitch;
         this.roll = roll;
         this.scaleX = scaleX;
         this.scaleY = scaleY;
         this.scaleZ = scaleZ;
         this.pivotX = pivotX;
         this.pivotY = pivotY;
         this.pivotZ = pivotZ;
         this.skewXByY = skewXByY;
         this.skewYByX = skewYByX;
         this.contentMode = contentMode;
         this.text = text;
         this.followLyrics = followLyrics;
         this.showTranslation = showTranslation;
         this.textScale = textScale;
         this.color = color;
         this.volume = volume;
         this.channelIndex = channelIndex;
         this.maxDistance = maxDistance;
         this.autoMixJoc = autoMixJoc;
         this.translationColor = translationColor;
         this.backgroundColor = backgroundColor;
         this.alignment = alignment;
         this.maxWidth = maxWidth;
         this.wrap = wrap;
         this.enabled = enabled;
         this.locked = locked;
         this.brightness = brightness;
      }
   }
}
