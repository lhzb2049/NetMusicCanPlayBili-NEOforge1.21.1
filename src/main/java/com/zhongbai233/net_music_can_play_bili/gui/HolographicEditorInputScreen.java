package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import com.zhongbai233.scene_editor.core.camera.EditorCameraMode;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.camera.EditorMouseDragPolicy;
import com.zhongbai233.scene_editor.core.camera.StandardCameraView;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.scene_editor.core.gizmo.GizmoConstraint;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import com.zhongbai233.scene_editor.core.gizmo.GizmoDragMath;
import com.zhongbai233.scene_editor.core.gizmo.GizmoTransformMath;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.projection.EditorProjection;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.projection.PickingRay;
import com.zhongbai233.scene_editor.core.projection.ProjectedPoint;
import com.zhongbai233.scene_editor.core.projection.RayRectangleIntersection;
import com.zhongbai233.scene_editor.core.selection.BlankClickSelectionPolicy;
import com.zhongbai233.scene_editor.core.transaction.DragTransaction;
import com.zhongbai233.scene_editor.minecraft.input.MinecraftEditorInput;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

abstract class HolographicEditorInputScreen extends HolographicEditorRenderScreen {
   protected HolographicEditorInputScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(bindEquippedGlasses, controlConsolePos);
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.isCloseButton(mouseX, mouseY)) {
         this.onClose();
         return true;
      } else if (super.mouseClicked(mouseX, mouseY, button)) {
         return true;
      } else if ((button == 0 || button == 1) && this.inPreview(mouseX, mouseY)) {
         int pipX = this.previewX() + 1;
         int pipY = this.previewY() + 26;
         int pipW = this.previewW() - 2;
         int pipH = this.previewH() - 54;
         CameraFrame cameraFrame = this.orbitCameraFrameFor(pipX, pipY, pipW, pipH);
         this.activeHandle = !this.firstPersonPreview && button != 1
            ? this.gizmoHandleAt(mouseX, mouseY, pipX, pipY, pipW, pipH, cameraFrame)
            : HolographicEditorScreenState.GizmoHandle.NONE;
         HolographicEditorScreenState.SceneHit sceneHit = this.firstPersonPreview
            ? HolographicEditorScreenState.SceneHit.player(0.0)
            : this.sceneHitAt(mouseX, mouseY, cameraFrame);
         this.previewClickHitPlayer = button == 0
            && this.activeHandle == HolographicEditorScreenState.GizmoHandle.NONE
            && sceneHit.type == HolographicEditorScreenState.SceneHitType.PLAYER
            && !this.controlConsoleMode;
         if (!this.firstPersonPreview
            && button == 0
            && this.activeHandle == HolographicEditorScreenState.GizmoHandle.NONE
            && sceneHit.type == HolographicEditorScreenState.SceneHitType.SCREEN
            && sceneHit.screenIndex >= 0) {
            this.selectElement(sceneHit.screenIndex);
            this.init();
         }

         this.previewDragStartedWithoutElement = this.controlConsoleMode
            && button == 0
            && this.activeHandle == HolographicEditorScreenState.GizmoHandle.NONE
            && sceneHit.type == HolographicEditorScreenState.SceneHitType.NONE;
         this.draggingPreview = true;
         this.previewDragButton = button;

         this.dragMode = switch (EditorMouseDragPolicy.action(
            button, this.firstPersonPreview, this.activeHandle != HolographicEditorScreenState.GizmoHandle.NONE
         )) {
            case ORBIT -> HolographicEditorScreenState.DragMode.CAMERA;
            case PAN -> HolographicEditorScreenState.DragMode.PAN;
            case GIZMO -> HolographicEditorScreenState.DragMode.GIZMO;
         };
         if (this.dragMode == HolographicEditorScreenState.DragMode.GIZMO) {
            this.gizmoDragSession = this.createGizmoDragSession(mouseX, mouseY, cameraFrame, this.activeHandle);
            if (this.gizmoDragSession == null) {
               this.dragMode = HolographicEditorScreenState.DragMode.CAMERA;
               this.activeHandle = HolographicEditorScreenState.GizmoHandle.NONE;
            } else {
               this.gizmoTransaction = new DragTransaction<>(this.snapshotScene(), "拖动场景元素");
            }
         }

         this.previewClickX = mouseX;
         this.previewClickY = mouseY;
         this.lastMouseX = mouseX;
         this.lastMouseY = mouseY;
         return true;
      } else {
         return false;
      }
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.draggingPreview && button == this.previewDragButton) {
         double dx = mouseX - this.lastMouseX;
         double dy = mouseY - this.lastMouseY;
         if (this.dragMode == HolographicEditorScreenState.DragMode.CAMERA) {
            double sensitivity = this.orbitSensitivityScale();
            this.setPreviewCamera(
               this.cameraController
                  .orbit(this.previewCamera, Math.toRadians(dx * 0.35 * sensitivity), Math.toRadians(-dy * 0.3 * sensitivity), EDITOR_WORLD_UP)
            );
         } else if (this.dragMode == HolographicEditorScreenState.DragMode.PAN && !this.firstPersonPreview) {
            this.setPreviewCamera(this.cameraController.panPixels(this.previewCamera, dx * 0.82, dy * 0.82, this.currentPreviewViewport()));
         } else if (this.dragMode == HolographicEditorScreenState.DragMode.GIZMO) {
            this.applyGizmoDrag(mouseX, mouseY);
            this.syncNumericEditBoxes();
         }

         this.lastMouseX = mouseX;
         this.lastMouseY = mouseY;
         return true;
      } else {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.draggingPreview && button == this.previewDragButton) {
         double dx = mouseX - this.previewClickX;
         double dy = mouseY - this.previewClickY;
         if (BlankClickSelectionPolicy.shouldDeselect(this.previewDragStartedWithoutElement, button, this.previewClickX, this.previewClickY, mouseX, mouseY)
            && this.selectedScreen >= 0) {
            this.enterNavigationMode();
            this.init();
         }

         if (button == 0 && this.dragMode != HolographicEditorScreenState.DragMode.GIZMO && dx * dx + dy * dy < 16.0 && this.previewClickHitPlayer) {
            this.firstPersonPreview = !this.firstPersonPreview;
            this.clearFlyKeys();
         }

         if (this.dragMode == HolographicEditorScreenState.DragMode.GIZMO && this.gizmoTransaction != null) {
            HolographicEditorScreenState.EditorSceneState current = this.snapshotScene();
            this.gizmoTransaction.update(ignored -> current);
            this.gizmoTransaction.commit(this.editHistory);
         }

         this.draggingPreview = false;
         this.previewDragButton = -1;
         this.dragMode = HolographicEditorScreenState.DragMode.NONE;
         this.activeHandle = HolographicEditorScreenState.GizmoHandle.NONE;
         this.gizmoDragSession = null;
         this.gizmoTransaction = null;
         this.previewClickHitPlayer = false;
         this.previewDragStartedWithoutElement = false;
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (this.getFocused() instanceof EditBox || this.firstPersonPreview) {
         return super.keyPressed(keyCode, scanCode, modifiers);
      } else if (this.controlConsoleMode && keyCode == 86) {
         this.startWorldRoaming();
         return true;
      } else if (this.setFlyKey(keyCode, true)) {
         return true;
      } else if (hasControlDown() && keyCode == 90) {
         HolographicEditorScreenState.EditorSceneState current = this.currentEditState();
         if (current != null) {
            this.applySceneState(this.editHistory.undo(current));
         }

         return true;
      } else if (hasControlDown() && keyCode == 89) {
         HolographicEditorScreenState.EditorSceneState current = this.currentEditState();
         if (current != null) {
            this.applySceneState(this.editHistory.redo(current));
         }

         return true;
      } else {
         if (!this.navigationMode()) {
            StandardCameraView standardView = MinecraftEditorInput.standardView(keyCode).orElse(null);
            if (standardView != null) {
               this.setPreviewCamera(this.cameraController.standardView(this.previewCamera, standardView, EDITOR_WORLD_UP));
               return true;
            }

            if (keyCode == 70) {
               this.focusSelectedScreen();
               return true;
            }

            if (keyCode == 79) {
               this.switchProjection(EditorCameraMode.ORTHOGRAPHIC);
               return true;
            }

            if (keyCode == 80) {
               this.switchProjection(EditorCameraMode.ORBIT);
               return true;
            }
         }

         return super.keyPressed(keyCode, scanCode, modifiers);
      }
   }

   public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
      return this.setFlyKey(keyCode, false) ? true : super.keyReleased(keyCode, scanCode, modifiers);
   }

   protected boolean setFlyKey(int key, boolean pressed) {
      MinecraftEditorInput.FlyControl control = MinecraftEditorInput.flyControl(key, this.navigationMode()).orElse(null);
      if (control == null) {
         return false;
      } else {
         switch (control) {
            case FORWARD:
               this.flyForward = pressed;
               break;
            case BACKWARD:
               this.flyBackward = pressed;
               break;
            case LEFT:
               this.flyLeft = pressed;
               break;
            case RIGHT:
               this.flyRight = pressed;
               break;
            case DOWN:
               this.flyDown = pressed;
               break;
            case UP:
               this.flyUp = pressed;
               break;
            case FAST:
               this.flyFast = pressed;
         }

         return true;
      }
   }

   @Override
   protected boolean navigationMode() {
      return this.controlConsoleMode && this.selectedScreen < 0 && !this.firstPersonPreview;
   }

   protected void enterNavigationMode() {
      this.clearFlyKeys();
      this.modelingCamera = this.previewCamera;
      this.selectedScreen = -1;
      EditorCameraState target = this.navigationCamera != null ? this.navigationCamera : this.modelingCamera;
      if (target.mode() == EditorCameraMode.ORTHOGRAPHIC) {
         target = this.cameraController.switchProjection(target, EditorCameraMode.ORBIT);
      }

      this.setPreviewCamera(target);
   }

   @Override
   protected void selectElement(int index) {
      if (index >= 0 && index < this.screens.size()) {
         this.clearFlyKeys();
         EditorCameraState cameraBeforeSelection = this.previewCamera;
         if (this.navigationMode()) {
            this.navigationCamera = cameraBeforeSelection;
         }

         this.selectedScreen = index;
         this.setPreviewCamera(cameraBeforeSelection);
      }
   }

   @Override
   protected void clearFlyKeys() {
      this.flyForward = false;
      this.flyBackward = false;
      this.flyLeft = false;
      this.flyRight = false;
      this.flyDown = false;
      this.flyUp = false;
      this.flyFast = false;
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      if (this.controlConsoleMode && mouseX >= 0.0 && mouseX < 156.0 && scrollY != 0.0) {
         int listTop = 34;
         int actionTop = Math.max(listTop + 24, this.height - 60);
         int visibleRows = Math.max(1, (actionTop - listTop - 4) / 24);
         int maxScroll = Math.max(0, this.screens.size() - visibleRows);
         int previous = this.consoleElementScroll;
         this.consoleElementScroll = Math.clamp((long)(this.consoleElementScroll + (scrollY < 0.0 ? 1 : -1)), 0, maxScroll);
         if (this.consoleElementScroll != previous) {
            this.init();
            return true;
         }
      }

      if (this.inPreview(mouseX, mouseY)) {
         this.setPreviewCamera(this.cameraController.dolly(this.previewCamera, scrollY));
         this.syncLegacyPreviewScale();
         return true;
      } else {
         return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
      }
   }

   protected void applyGizmoDrag(double mouseX, double mouseY) {
      HolographicEditorScreenState.GizmoDragSession session = this.gizmoDragSession;
      if (session != null && session.screenIndex >= 0 && session.screenIndex < this.screens.size() && !this.screens.get(session.screenIndex).locked) {
         PickingRay ray = EditorProjection.rayFromScreen(mouseX, mouseY, session.cameraFrame.matrices(), session.cameraFrame.viewport());
         Vector3d currentHit = GizmoDragMath.intersectConstraint(ray, session.origin, session.constraintVector, session.constraint).orElse(null);
         if (currentHit != null) {
            HolographicEditorScreenState.PreviewScreenSpec screen = this.screens.get(session.screenIndex);
            Vector3d worldDelta = new Vector3d(currentHit).sub(session.startHit);
            double axisDelta = session.axis != null ? GizmoDragMath.signedAxisDelta(session.axis, session.startHit, currentHit) : 0.0;
            switch (session.tool) {
               case MOVE:
                  switch (session.handle) {
                     case X:
                        screen.offsetX = finiteOrPrevious(session.start.offsetX + (float)axisDelta, screen.offsetX);
                        return;
                     case Y:
                        screen.offsetY = finiteOrPrevious(session.start.offsetY + (float)axisDelta, screen.offsetY);
                        return;
                     case Z:
                        screen.distance = finiteOrPrevious(session.start.distance + (float)axisDelta, screen.distance);
                        return;
                     case CENTER:
                        screen.offsetX = finiteOrPrevious(session.start.offsetX + (float)worldDelta.x, screen.offsetX);
                        screen.offsetY = finiteOrPrevious(session.start.offsetY + (float)worldDelta.y, screen.offsetY);
                        return;
                     default:
                        return;
                  }
               case ROTATE:
                  if (session.handle.isRotationRing()) {
                     float delta = GizmoDragMath.rotationDeltaDegrees(session.origin, session.axis, session.startHit, currentHit);
                     int axisIndex = session.handle == HolographicEditorScreenState.GizmoHandle.RING_X
                        ? 0
                        : (session.handle == HolographicEditorScreenState.GizmoHandle.RING_Y ? 1 : 2);
                     applyTransform(screen, GizmoTransformMath.rotate(transform(session.start), axisIndex, delta, this.coordinateSpace));
                  }
                  break;
               case SCALE:
                  if (session.handle == HolographicEditorScreenState.GizmoHandle.X) {
                     float factor = 1.0F + (float)axisDelta;
                     this.applyScaledTransform(screen, session.start, 0, factor);
                  } else if (session.handle == HolographicEditorScreenState.GizmoHandle.Y) {
                     float factor = 1.0F + (float)axisDelta;
                     this.applyScaledTransform(screen, session.start, 1, factor);
                  } else if (session.handle == HolographicEditorScreenState.GizmoHandle.Z) {
                     float factor = 1.0F + (float)axisDelta;
                     this.applyScaledTransform(screen, session.start, 2, factor);
                  } else if (session.handle == HolographicEditorScreenState.GizmoHandle.CENTER) {
                     double delta = session.handle == HolographicEditorScreenState.GizmoHandle.CENTER ? worldDelta.dot(session.localY) : axisDelta;
                     float factor = 1.0F + (float)delta;
                     screen.scaleX = boundedScale(session.start.scaleX * factor, screen.scaleX);
                     screen.scaleY = boundedScale(session.start.scaleY * factor, screen.scaleY);
                     screen.scaleZ = boundedScale(session.start.scaleZ * factor, screen.scaleZ);
                  }
            }
         }
      }
   }

   protected static float finiteOrPrevious(float candidate, float previous) {
      return Float.isFinite(candidate) ? candidate : previous;
   }

   protected static float boundedScale(float candidate, float previous) {
      return Float.isFinite(candidate) ? Math.clamp(candidate, 0.05F, 16.0F) : previous;
   }

   protected void applyScaledTransform(
      HolographicEditorScreenState.PreviewScreenSpec screen, HolographicEditorScreenState.ScreenSnapshot start, int axisIndex, float factor
   ) {
      GizmoTransformMath.scale(transform(start), axisIndex, factor, this.coordinateSpace, 0.05F, 16.0F, 1.0F).ifPresent(value -> applyTransform(screen, value));
   }

   protected static EditorTransform transform(HolographicEditorScreenState.ScreenSnapshot value) {
      return EditorTransform.fromEulerDegrees(
         new Vector3f(value.offsetX, value.offsetY, value.distance),
         value.yaw,
         value.pitch,
         value.roll,
         new Vector3f(value.scaleX, value.scaleY, value.scaleZ),
         new Vector3f(value.pivotX, value.pivotY, value.pivotZ),
         value.skewXByY,
         value.skewYByX
      );
   }

   protected static void applyTransform(HolographicEditorScreenState.PreviewScreenSpec screen, EditorTransform value) {
      Vector3f scale = value.scale();
      Vector3f pivot = value.pivot();
      Vector3f euler = value.rotation().getEulerAnglesYXZ(new Vector3f());
      screen.pitch = (float)Math.toDegrees(euler.x);
      screen.yaw = (float)Math.toDegrees(euler.y);
      screen.roll = (float)Math.toDegrees(euler.z);
      screen.scaleX = scale.x;
      screen.scaleY = scale.y;
      screen.scaleZ = scale.z;
      screen.pivotX = pivot.x;
      screen.pivotY = pivot.y;
      screen.pivotZ = pivot.z;
      screen.skewXByY = value.skewXByY();
      screen.skewYByX = value.skewYByX();
   }

   protected static float saturatedPositiveFloat(double value) {
      return value >= Float.MAX_VALUE ? Float.MAX_VALUE : (float)value;
   }

   protected HolographicEditorScreenState.GizmoDragSession createGizmoDragSession(
      double mouseX, double mouseY, CameraFrame cameraFrame, HolographicEditorScreenState.GizmoHandle handle
   ) {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      if (screen.locked) {
         return null;
      } else {
         HolographicEditorScreenState.ScreenSnapshot start = snapshot(screen);
         Vector3d origin = new Vector3d(screen.offsetX + screen.pivotX, 1.55 + screen.offsetY + screen.pivotY, screen.distance + screen.pivotZ);
         Quaternionf rotation = new Quaternionf()
            .rotateYXZ((float)Math.toRadians(screen.yaw), (float)Math.toRadians(screen.pitch), (float)Math.toRadians(screen.roll));
         Vector3d localX = new Vector3d(rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F)));
         Vector3d localY = new Vector3d(rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F)));
         Vector3d localZ = new Vector3d(rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F)));
         boolean worldSpace = this.coordinateSpace == GizmoCoordinateSpace.WORLD;

         Vector3d axis = switch (handle) {
            case X -> worldSpace ? new Vector3d(1.0, 0.0, 0.0) : localX;
            case Y -> worldSpace ? new Vector3d(0.0, 1.0, 0.0) : localY;
            case Z -> worldSpace ? new Vector3d(0.0, 0.0, 1.0) : localZ;
            default -> null;
            case RING_X -> worldSpace ? new Vector3d(1.0, 0.0, 0.0) : localX;
            case RING_Y -> worldSpace ? new Vector3d(0.0, 1.0, 0.0) : localY;
            case RING_Z -> worldSpace ? new Vector3d(0.0, 0.0, 1.0) : localZ;
         };
         GizmoConstraint constraint;
         Vector3d constraintVector;
         if (handle == HolographicEditorScreenState.GizmoHandle.CENTER && this.activeTool != HolographicEditorScreenState.EditTool.ROTATE) {
            Matrix4f cameraWorld = cameraFrame.matrices().view().invert(new Matrix4f());
            Vector3f forward = cameraWorld.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
            constraint = GizmoConstraint.VIEW_PLANE;
            constraintVector = new Vector3d(forward);
         } else if (this.activeTool == HolographicEditorScreenState.EditTool.ROTATE) {
            if (axis == null) {
               return null;
            }

            constraint = GizmoConstraint.XY_PLANE;
            constraintVector = axis;
         } else {
            constraint = switch (handle) {
               case X -> GizmoConstraint.X_AXIS;
               case Y -> GizmoConstraint.Y_AXIS;
               case Z -> GizmoConstraint.Z_AXIS;
               default -> GizmoConstraint.VIEW_PLANE;
            };
            constraintVector = axis != null ? axis : localZ;
         }

         PickingRay startRay = EditorProjection.rayFromScreen(mouseX, mouseY, cameraFrame.matrices(), cameraFrame.viewport());
         Vector3d startHit = GizmoDragMath.intersectConstraint(startRay, origin, constraintVector, constraint).orElse(null);
         return startHit == null
            ? null
            : new HolographicEditorScreenState.GizmoDragSession(
               this.selectedScreen, this.activeTool, handle, cameraFrame, start, origin, localY, axis, constraint, constraintVector, startHit
            );
      }
   }

   protected HolographicEditorScreenState.EditorSceneState currentEditState() {
      return this.snapshotScene();
   }

   @Override
   protected HolographicEditorScreenState.EditorSceneState snapshotScene() {
      return new HolographicEditorScreenState.EditorSceneState(
         this.screens.stream().map(HolographicEditorInputScreen::snapshot).toList(),
         this.selectedScreen,
         this.consoleDraft == null
            ? null
            : new HolographicEditorScreenState.ConsoleProperties(
               this.consoleDraft.displayName(), this.consoleDraft.hardRangeX(), this.consoleDraft.hardRangeY(), this.consoleDraft.hardRangeZ()
            )
      );
   }

   protected void applySceneState(HolographicEditorScreenState.EditorSceneState state) {
      if (state != null) {
         this.screens.clear();
         state.screens.forEach(valuex -> this.screens.add(HolographicEditorScreenState.PreviewScreenSpec.fromSnapshot(valuex)));
         this.selectedScreen = this.screens.isEmpty() ? -1 : Math.clamp((long)state.selectedScreen, -1, this.screens.size() - 1);
         if (state.consoleProperties != null && this.consoleDraft != null) {
            HolographicEditorScreenState.ConsoleProperties value = state.consoleProperties;
            this.consoleDraft = new ControlConsoleDocument(
               this.consoleDraft.schemaVersion(),
               this.consoleDraft.consoleId(),
               this.consoleDraft.revision(),
               this.consoleDraft.ownerId(),
               this.consoleDraft.accessMode(),
               this.consoleDraft.trustedPlayerIds(),
               value.displayName,
               this.consoleDraft.sourceDimension(),
               this.consoleDraft.sourceKind(),
               this.consoleDraft.sourceX(),
               this.consoleDraft.sourceY(),
               this.consoleDraft.sourceZ(),
               value.hardRangeX,
               value.hardRangeY,
               value.hardRangeZ,
               this.consoleElementsSnapshot()
            );
         }

         this.syncNumericEditBoxes();
         this.init();
      }
   }

   @Override
   protected void edit(String description, Runnable mutation) {
      HolographicEditorScreenState.EditorSceneState before = this.snapshotScene();
      mutation.run();
      HolographicEditorScreenState.EditorSceneState after = this.snapshotScene();
      if (!before.equals(after)) {
         this.editHistory.execute(before, new StateReplacementCommand<>(before, after, description));
      }
   }

   @Override
   protected void editSelected(String description, Consumer<HolographicEditorScreenState.PreviewScreenSpec> mutation) {
      this.edit(description, () -> {
         HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
         if (selected != null && !selected.locked) {
            mutation.accept(selected);
         }
      });
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

   protected void presetRight() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      if (!screen.locked) {
         screen.distance = 2.2F;
         screen.offsetX = 0.65F;
         screen.offsetY = 0.05F;
         screen.height = 0.75F;
         screen.aspect = 1.7777778F;
         screen.roll = 0.0F;
         resetAdvancedTransform(screen);
      }
   }

   protected void presetLeft() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      if (!screen.locked) {
         screen.distance = 2.2F;
         screen.offsetX = -0.65F;
         screen.offsetY = 0.05F;
         screen.height = 0.75F;
         screen.aspect = 1.7777778F;
         screen.roll = 0.0F;
         resetAdvancedTransform(screen);
      }
   }

   protected void presetCinema() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      if (!screen.locked) {
         screen.distance = 2.1F;
         screen.offsetX = 0.0F;
         screen.offsetY = 0.0F;
         screen.height = 1.8F;
         screen.aspect = 1.7777778F;
         screen.roll = 0.0F;
         resetAdvancedTransform(screen);
      }
   }

   protected void resetDefaults() {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.screen();
      if (!screen.locked) {
         screen.distance = 2.2F;
         screen.offsetX = 0.0F;
         screen.offsetY = 0.05F;
         screen.height = 0.75F;
         screen.aspect = 1.7777778F;
         screen.roll = 0.0F;
         resetAdvancedTransform(screen);
         this.firstPersonPreview = false;
         this.previewScale = 38.0F;
         this.setPreviewCamera(legacyOrbitCamera(180.0, 35.0, 6.5, new Vector3d(0.0, 0.9F, 0.0)));
      }
   }

   protected static void resetAdvancedTransform(HolographicEditorScreenState.PreviewScreenSpec screen) {
      screen.scaleX = 1.0F;
      screen.scaleY = 1.0F;
      screen.scaleZ = 1.0F;
      screen.pivotX = 0.0F;
      screen.pivotY = 0.0F;
      screen.pivotZ = 0.0F;
      screen.skewXByY = 0.0F;
      screen.skewYByX = 0.0F;
   }

   protected boolean inPreview(double mouseX, double mouseY) {
      int x = this.previewX();
      int y = this.previewY();
      return mouseX >= x && mouseX <= x + this.previewW() && mouseY >= y && mouseY <= y + this.previewH();
   }

   protected HolographicEditorScreenState.GizmoProjection gizmoProjection(int x, int y, int w, int h, CameraFrame cameraFrame) {
      HolographicEditorScreenState.PreviewScreenSpec screen = this.selectedScreenOrNull();
      if (screen == null) {
         HolographicEditorScreenState.GizmoPoint hidden = new HolographicEditorScreenState.GizmoPoint(0.0, 0.0, 0.0, false);
         return new HolographicEditorScreenState.GizmoProjection(
            hidden,
            hidden,
            hidden,
            hidden,
            new HolographicEditorScreenState.GizmoPoint[0],
            new HolographicEditorScreenState.GizmoPoint[0],
            new HolographicEditorScreenState.GizmoPoint[0]
         );
      } else {
         double centerX = screen.offsetX + screen.pivotX;
         double centerY = 1.55 + screen.offsetY + screen.pivotY;
         double centerZ = screen.distance + screen.pivotZ;
         Quaternionf rotation = screenRotation(screen);
         boolean worldSpace = this.coordinateSpace == GizmoCoordinateSpace.WORLD;
         Vector3f xDirection = worldSpace ? new Vector3f(1.0F, 0.0F, 0.0F) : rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
         Vector3f yDirection = worldSpace ? new Vector3f(0.0F, 1.0F, 0.0F) : rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
         Vector3f zDirection = worldSpace ? new Vector3f(0.0F, 0.0F, 1.0F) : rotation.transform(new Vector3f(0.0F, 0.0F, 1.0F));
         HolographicEditorScreenState.GizmoPoint center = this.projectGizmoPoint(centerX, centerY, centerZ, cameraFrame);
         HolographicEditorScreenState.GizmoPoint xAxis = this.projectGizmoPoint(
            centerX + xDirection.x * 0.72F, centerY + xDirection.y * 0.72F, centerZ + xDirection.z * 0.72F, cameraFrame
         );
         HolographicEditorScreenState.GizmoPoint yAxis = this.projectGizmoPoint(
            centerX + yDirection.x * 0.72F, centerY + yDirection.y * 0.72F, centerZ + yDirection.z * 0.72F, cameraFrame
         );
         HolographicEditorScreenState.GizmoPoint zAxis = this.projectGizmoPoint(
            centerX + zDirection.x * 0.72F, centerY + zDirection.y * 0.72F, centerZ + zDirection.z * 0.72F, cameraFrame
         );
         HolographicEditorScreenState.GizmoPoint[] ringX = this.projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 0);
         HolographicEditorScreenState.GizmoPoint[] ringY = this.projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 1);
         HolographicEditorScreenState.GizmoPoint[] ringZ = this.projectGizmoRing(centerX, centerY, centerZ, rotation, cameraFrame, 2);
         return new HolographicEditorScreenState.GizmoProjection(center, xAxis, yAxis, zAxis, ringX, ringY, ringZ);
      }
   }

   protected HolographicEditorScreenState.GizmoPoint[] projectGizmoRing(
      double centerX, double centerY, double centerZ, Quaternionf rotation, CameraFrame cameraFrame, int axis
   ) {
      HolographicEditorScreenState.GizmoPoint[] ring = new HolographicEditorScreenState.GizmoPoint[48];
      double radius = 0.4752000188827515;

      for (int i = 0; i < ring.length; i++) {
         double angle = (Math.PI * 2) * i / ring.length;
         float a = (float)(Math.cos(angle) * radius);
         float b = (float)(Math.sin(angle) * radius);

         Vector3f local = switch (axis) {
            case 0 -> new Vector3f(0.0F, a, b);
            case 1 -> new Vector3f(a, 0.0F, b);
            default -> new Vector3f(a, b, 0.0F);
         };
         Vector3f point = rotation.transform(local);
         ring[i] = this.projectGizmoPoint(centerX + point.x, centerY + point.y, centerZ + point.z, cameraFrame);
      }

      return ring;
   }

   protected HolographicEditorScreenState.GizmoPoint projectGizmoPoint(double worldX, double worldY, double worldZ, CameraFrame cameraFrame) {
      ProjectedPoint point = EditorProjection.project(new Vector3d(worldX, worldY, worldZ), cameraFrame.matrices(), cameraFrame.viewport());
      return new HolographicEditorScreenState.GizmoPoint(point.screenX(), point.screenY(), point.depth(), point.visible());
   }

   @Override
   protected HolographicEditorScreenState.SceneHit sceneHitAt(double mouseX, double mouseY, CameraFrame cameraFrame) {
      PickingRay ray;
      try {
         ray = EditorProjection.rayFromScreen(mouseX, mouseY, cameraFrame.matrices(), cameraFrame.viewport());
      } catch (IllegalStateException | IllegalArgumentException var19) {
         return HolographicEditorScreenState.SceneHit.none();
      }

      OptionalDouble playerIntersection = ray.intersectAabb(PREVIEW_PLAYER_BOUNDS_MIN, PREVIEW_PLAYER_BOUNDS_MAX);
      HolographicEditorScreenState.SceneHit nearest = playerIntersection.isPresent()
         ? HolographicEditorScreenState.SceneHit.player(playerIntersection.orElseThrow())
         : HolographicEditorScreenState.SceneHit.none();
      double nearestDistance = nearest.distance;

      for (int i = 0; i < this.screens.size(); i++) {
         HolographicEditorScreenState.PreviewScreenSpec screen = this.screens.get(i);
         double halfHeight = screen.height * 0.5;
         Matrix4f transform = new Matrix4f().translation(0.0F, 1.55F, 0.0F).mul(previewTransform(screen).matrix());
         Optional<RayRectangleIntersection> intersection = ray.intersectTransformedRectangle(transform, halfHeight * screen.aspect, halfHeight);
         if (intersection.isPresent()) {
            double distance = intersection.orElseThrow().distance();
            if (distance <= nearestDistance + 1.0E-7) {
               nearest = HolographicEditorScreenState.SceneHit.screen(i, distance);
               nearestDistance = distance;
            }
         }
      }

      return nearest;
   }

   protected static Quaternionf screenRotation(HolographicEditorScreenState.PreviewScreenSpec screen) {
      return new Quaternionf().rotateYXZ((float)Math.toRadians(screen.yaw), (float)Math.toRadians(screen.pitch), (float)Math.toRadians(screen.roll));
   }

   protected static EditorTransform previewTransform(HolographicEditorScreenState.PreviewScreenSpec screen) {
      return EditorTransform.fromEulerDegrees(
         new Vector3f(screen.offsetX, screen.offsetY, screen.distance),
         screen.yaw,
         screen.pitch,
         screen.roll,
         new Vector3f(screen.scaleX, screen.scaleY, screen.scaleZ),
         new Vector3f(screen.pivotX, screen.pivotY, screen.pivotZ),
         screen.skewXByY,
         screen.skewYByX
      );
   }

   @Override
   protected HolographicEditorScreenState.GizmoHandle gizmoHandleAt(double mouseX, double mouseY, int x, int y, int w, int h, CameraFrame cameraFrame) {
      return !this.selectedElementEditable()
         ? HolographicEditorScreenState.GizmoHandle.NONE
         : this.gizmoHandleAt(mouseX, mouseY, this.gizmoProjection(x, y, w, h, cameraFrame));
   }

   @Override
   protected boolean selectedElementEditable() {
      HolographicEditorScreenState.PreviewScreenSpec selected = this.selectedScreenOrNull();
      return selected != null && !selected.locked;
   }

   protected HolographicEditorScreenState.GizmoHandle gizmoHandleAt(double mouseX, double mouseY, HolographicEditorScreenState.GizmoProjection gizmo) {
      HolographicEditorScreenState.GizmoPoint center = gizmo.center;
      if (!center.visible) {
         return HolographicEditorScreenState.GizmoHandle.NONE;
      } else {
         double cx = center.x;
         double cy = center.y;
         if (this.activeTool == HolographicEditorScreenState.EditTool.ROTATE) {
            if (hitsRing(mouseX, mouseY, gizmo.ringX)) {
               return HolographicEditorScreenState.GizmoHandle.RING_X;
            }

            if (hitsRing(mouseX, mouseY, gizmo.ringY)) {
               return HolographicEditorScreenState.GizmoHandle.RING_Y;
            }

            if (hitsRing(mouseX, mouseY, gizmo.ringZ)) {
               return HolographicEditorScreenState.GizmoHandle.RING_Z;
            }
         }

         if (Math.hypot(mouseX - cx, mouseY - cy) <= 7.0) {
            return HolographicEditorScreenState.GizmoHandle.CENTER;
         } else if (gizmo.xAxis.visible && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.xAxis.x, gizmo.xAxis.y) <= 7.0) {
            return HolographicEditorScreenState.GizmoHandle.X;
         } else if (gizmo.yAxis.visible && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.yAxis.x, gizmo.yAxis.y) <= 7.0) {
            return HolographicEditorScreenState.GizmoHandle.Y;
         } else {
            return gizmo.zAxis.visible && distanceToSegment(mouseX, mouseY, cx, cy, gizmo.zAxis.x, gizmo.zAxis.y) <= 7.0
               ? HolographicEditorScreenState.GizmoHandle.Z
               : HolographicEditorScreenState.GizmoHandle.NONE;
         }
      }
   }

   protected static boolean hitsRing(double mouseX, double mouseY, HolographicEditorScreenState.GizmoPoint[] ring) {
      if (ring.length == 0) {
         return false;
      } else {
         for (int i = 1; i < ring.length; i++) {
            if (ring[i - 1].visible && ring[i].visible && distanceToSegment(mouseX, mouseY, ring[i - 1].x, ring[i - 1].y, ring[i].x, ring[i].y) <= 7.0) {
               return true;
            }
         }

         HolographicEditorScreenState.GizmoPoint first = ring[0];
         HolographicEditorScreenState.GizmoPoint last = ring[ring.length - 1];
         return last.visible && first.visible && distanceToSegment(mouseX, mouseY, last.x, last.y, first.x, first.y) <= 7.0;
      }
   }

   protected static double distanceToSegment(double px, double py, double x1, double y1, double x2, double y2) {
      double dx = x2 - x1;
      double dy = y2 - y1;
      double lenSq = dx * dx + dy * dy;
      if (lenSq <= 1.0E-4) {
         return Math.hypot(px - x1, py - y1);
      } else {
         double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
         t = Math.max(0.0, Math.min(1.0, t));
         return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
      }
   }

   protected boolean isCloseButton(double mouseX, double mouseY) {
      int x = this.width - 8 - 22;
      return mouseX >= x && mouseX <= x + 22 && mouseY >= 4.0 && mouseY <= 22.0;
   }

   protected abstract CameraFrame orbitCameraFrameFor(int var1, int var2, int var3, int var4);

   protected abstract EditorViewport currentPreviewViewport();

   protected abstract void switchProjection(EditorCameraMode var1);

   protected abstract void syncLegacyPreviewScale();
}
