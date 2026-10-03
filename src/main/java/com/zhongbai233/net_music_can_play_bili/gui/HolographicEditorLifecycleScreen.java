package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleRoamingSession;
import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewManager;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket;
import com.zhongbai233.net_music_can_play_bili.network.HolographicGlassesConfigPacket;
import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.gizmo.GizmoCoordinateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;

abstract class HolographicEditorLifecycleScreen extends HolographicEditorScreenState {
   protected HolographicEditorLifecycleScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
      super(bindEquippedGlasses, controlConsolePos);
   }

   protected void restoreRoamingElements(List<ControlConsoleRoamingSession.RoamingElement> elements) {
      if (elements != null && !elements.isEmpty()) {
         this.screens.clear();
         this.consoleElementsLoaded = true;
         this.roamingHistoryPending = true;

         for (ControlConsoleRoamingSession.RoamingElement element : elements) {
            HolographicEditorScreenState.ElementType type;
            try {
               type = HolographicEditorScreenState.ElementType.valueOf(element.type());
            } catch (IllegalArgumentException var6) {
               type = HolographicEditorScreenState.ElementType.SCREEN;
            }

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
            this.screens.add(restored);
         }
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void tick() {
      super.tick();
      if (this.controlConsoleMode && !this.validControlConsoleHost()) {
         this.onClose();
      } else {
         if (this.controlConsoleMode) {
            ControlConsoleClient.tickLease(this.controlConsolePos);
            if (!ControlConsoleClient.hasLease(this.controlConsolePos)) {
               this.onClose();
               return;
            }
         }

         ControlConsoleDocument authoritative = this.controlConsoleDocument();
         if (authoritative != null && this.consoleDraft != null && !this.consoleSaveConflict && authoritative.revision() > this.consoleDraft.revision()) {
            this.installAuthoritativeConsoleDocument(authoritative, "已同步服务器版本");
            this.init();
         }

         if (this.controlConsoleMode) {
            this.processConsoleAutosave();
         }
      }
   }

   protected void processConsoleAutosave() {
      ControlConsoleDocument draft = this.currentConsoleDocument();
      if (draft != null && !this.consoleSaveConflict) {
         int fingerprint = this.consoleDraftFingerprint(draft);
         if (!this.consoleAutosaveFingerprintInitialized) {
            this.consoleSavedFingerprint = fingerprint;
            this.consoleObservedFingerprint = fingerprint;
            this.consoleAutosaveFingerprintInitialized = true;
         } else {
            if (fingerprint != this.consoleObservedFingerprint) {
               this.consoleObservedFingerprint = fingerprint;
               this.consoleAutosaveTick = 10L;
            } else if (this.consoleAutosaveTick > 0L) {
               this.consoleAutosaveTick--;
            }

            if (this.consoleAutosaveTick == 0L && this.consolePendingOperation == null && fingerprint != this.consoleSavedFingerprint) {
               this.sendConsoleAutosave();
            }
         }
      }
   }

   protected void sendConsoleAutosave() {
      if (this.controlConsolePos != null && this.consoleDraft != null && this.consolePendingOperation == null) {
         UUID operationId = UUID.randomUUID();
         List<ControlConsoleElement> elements = this.consoleElementsSnapshot();
         this.consolePendingFingerprint = Objects.hash(
            this.consoleDraft.displayName(), this.consoleDraft.hardRangeX(), this.consoleDraft.hardRangeY(), this.consoleDraft.hardRangeZ(), elements
         );
         this.consoleDraft = new ControlConsoleDocument(
            this.consoleDraft.schemaVersion(),
            this.consoleDraft.consoleId(),
            this.consoleDraft.revision(),
            this.consoleDraft.ownerId(),
            this.consoleDraft.accessMode(),
            this.consoleDraft.trustedPlayerIds(),
            this.consoleDraft.displayName(),
            this.consoleDraft.sourceDimension(),
            this.consoleDraft.sourceKind(),
            this.consoleDraft.sourceX(),
            this.consoleDraft.sourceY(),
            this.consoleDraft.sourceZ(),
            this.consoleDraft.hardRangeX(),
            this.consoleDraft.hardRangeY(),
            this.consoleDraft.hardRangeZ(),
            elements
         );
         this.consolePendingOperation = operationId;
         this.consoleSaveStatus = "保存中…";
         UUID leaseId = ControlConsoleClient.leaseId(this.controlConsolePos);
         if (leaseId == null) {
            this.consolePendingOperation = null;
            this.consoleSaveStatus = "编辑租约不可用";
         } else {
            PacketDistributor.sendToServer(
               new ControlConsoleConfigPacket(
                  this.controlConsolePos,
                  leaseId,
                  operationId,
                  this.consoleDraft.revision(),
                  this.consoleDraft.displayName(),
                  this.consoleDraft.hardRangeX(),
                  this.consoleDraft.hardRangeY(),
                  this.consoleDraft.hardRangeZ(),
                  this.consoleElementsSnapshot()
               ),
               new CustomPacketPayload[0]
            );
         }
      }
   }

   protected int consoleDraftFingerprint(ControlConsoleDocument draft) {
      return Objects.hash(draft.displayName(), draft.hardRangeX(), draft.hardRangeY(), draft.hardRangeZ(), this.consoleElementsSnapshot());
   }

   public static void acceptControlConsoleConfigResult(ControlConsoleConfigResultPacket result) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen instanceof HolographicScreenConfigTestScreen screen) {
         screen.acceptConsoleConfigResult(result);
      }
   }

   protected void acceptConsoleConfigResult(ControlConsoleConfigResultPacket result) {
      if (this.controlConsoleMode && Objects.equals(this.consolePendingOperation, result.operationId())) {
         this.consolePendingOperation = null;
         switch (result.status()) {
            case APPLIED:
            case DUPLICATE:
               this.consoleConflictAuthoritative = null;
               this.consoleDraft = this.consoleDraft.withRevision(result.revision());
               this.consoleSaveStatus = this.consoleAccessRollback != null ? "权限设置已保存" : "已自动保存";
               this.consoleSavedFingerprint = this.consolePendingFingerprint;
               this.consoleAutosaveTick = 10L;
               this.consoleAccessRollback = null;
               break;
            case CONFLICT:
               this.restoreAccessRollback();
               this.consoleConflictAuthoritative = result.authoritativeDocument();
               this.consoleSaveConflict = true;
               this.consoleSaveStatus = "版本冲突：已收到服务器版本，本地修改未覆盖";
               this.init();
               break;
            case READ_ONLY:
               this.consoleConflictAuthoritative = null;
               this.restoreAccessRollback();
               this.consoleSaveConflict = true;
               this.consoleSaveStatus = "文档版本过新：当前版本仅允许只读查看";
               this.init();
               break;
            case REJECTED:
               this.consoleConflictAuthoritative = null;
               this.restoreAccessRollback();
               this.consoleSaveStatus = "保存被服务器拒绝";
               this.init();
         }
      }
   }

   protected void restoreAccessRollback() {
      if (this.consoleAccessRollback != null) {
         this.consoleDraft = this.consoleAccessRollback;
         this.consoleAccessModeDraft = this.consoleDraft.accessMode();
         this.consoleAccessRollback = null;
      }
   }

   protected void reloadAuthoritativeConsoleDocument() {
      ControlConsoleDocument authoritative = this.consoleConflictAuthoritative != null ? this.consoleConflictAuthoritative : this.controlConsoleDocument();
      if (authoritative != null) {
         this.installAuthoritativeConsoleDocument(authoritative, "已重新加载服务器版本");
         this.init();
      }
   }

   protected void installAuthoritativeConsoleDocument(ControlConsoleDocument authoritative, String status) {
      this.consoleDraft = authoritative;
      this.consoleAccessModeDraft = authoritative.accessMode();
      this.consoleAccessRollback = null;
      this.consoleConflictAuthoritative = null;
      this.consoleSaveConflict = false;
      this.consoleSaveStatus = status;
      this.consolePendingOperation = null;
      this.consoleAutosaveTick = 0L;
      this.consoleSavedFingerprint = documentFingerprint(authoritative);
      this.consoleObservedFingerprint = this.consoleSavedFingerprint;
      this.consoleAutosaveFingerprintInitialized = true;
      this.loadConsoleElements(authoritative);
   }

   protected boolean validControlConsoleHost() {
      if (this.minecraft != null
         && this.minecraft.level == this.controlConsoleLevel
         && this.minecraft.player == this.controlConsolePlayer
         && this.minecraft.player != null
         && this.minecraft.player.isAlive()
         && this.controlConsolePos != null) {
         double centerX = this.controlConsolePos.getX() + 0.5;
         double centerY = this.controlConsolePos.getY() + 0.5;
         double centerZ = this.controlConsolePos.getZ() + 0.5;
         return this.minecraft.player.distanceToSqr(centerX, centerY, centerZ) > 64.0
            ? false
            : this.minecraft.level.hasChunk(Math.floorDiv(this.controlConsolePos.getX(), 16), Math.floorDiv(this.controlConsolePos.getZ(), 16))
               && this.minecraft.level.getBlockEntity(this.controlConsolePos) instanceof ControlConsoleBlockEntity;
      } else {
         return false;
      }
   }

   public void advanceCameraFrame(double deltaSeconds) {
      if (!(this.getFocused() instanceof EditBox) && !this.firstPersonPreview) {
         if (this.flyForward || this.flyBackward || this.flyLeft || this.flyRight || this.flyDown || this.flyUp) {
            this.setPreviewCamera(
               this.navigationMode()
                  ? this.cameraController
                     .walk(
                        this.previewCamera,
                        this.flyForward,
                        this.flyBackward,
                        this.flyLeft,
                        this.flyRight,
                        this.flyDown,
                        this.flyUp,
                        deltaSeconds,
                        this.flyFast,
                        EDITOR_WORLD_UP
                     )
                  : this.cameraController
                     .fly(
                        this.previewCamera,
                        this.flyForward,
                        this.flyBackward,
                        this.flyLeft,
                        this.flyRight,
                        this.flyDown,
                        this.flyUp,
                        deltaSeconds,
                        this.flyFast,
                        EDITOR_WORLD_UP
                     )
            );
         }
      }
   }

   public void onClose() {
      this.clearFlyKeys();
      if (this.controlConsoleMode
         && this.consoleDraft != null
         && this.consolePendingOperation == null
         && !this.consoleSaveConflict
         && this.consoleAutosaveFingerprintInitialized
         && this.consoleDraftFingerprint(this.consoleDraft) != this.consoleSavedFingerprint) {
         this.sendConsoleAutosave();
      }

      this.saveEquippedGlassesConfig();
      if (this.controlConsolePos != null) {
         TerrainPreviewManager.close(this.controlConsolePos);
         if (!this.transferLeaseToRoaming) {
            ControlConsoleClient.releaseLease(this.controlConsolePos);
         }
      }

      this.clearNumericPanelRefs();
      super.onClose();
   }

   protected void startWorldRoaming() {
      if (this.controlConsoleMode
         && !this.worldRoamingTransitionPending
         && this.validControlConsoleHost()
         && this.minecraft != null
         && this.controlConsolePos != null) {
         this.worldRoamingTransitionPending = true;
         this.transferLeaseToRoaming = true;
         Minecraft client = this.minecraft;
         BlockPos origin = this.controlConsolePos;
         Vector3d localPosition = this.previewCamera.position();
         Quaternionf localOrientation = this.previewCamera.orientation();
         this.onClose();
         client.execute(() -> {
            if (client.screen == null) {
               if (!ControlConsoleRoamingSession.start(origin, localPosition, localOrientation, this.roamingElementsSnapshot())) {
                  ControlConsoleClient.releaseLease(origin);
               }
            } else {
               ControlConsoleClient.releaseLease(origin);
            }
         });
      }
   }

   protected List<ControlConsoleRoamingSession.RoamingElement> roamingElementsSnapshot() {
      List<ControlConsoleRoamingSession.RoamingElement> snapshot = new ArrayList<>(this.screens.size());

      for (HolographicEditorScreenState.PreviewScreenSpec screen : this.screens) {
         snapshot.add(
            new ControlConsoleRoamingSession.RoamingElement(
               screen.elementId,
               screen.type.name(),
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
               screen.skewYByX
            )
         );
      }

      return List.copyOf(snapshot);
   }

   protected void loadEquippedGlassesConfig() {
      Player player = Minecraft.getInstance().player;
      if (player != null) {
         ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
         if (HolographicGlassesAbility.has(head)) {
            List<HolographicGlassesItem.ScreenBinding> bindings = HolographicGlassesItem.readScreenBindings(head);
            this.screens.clear();

            for (int i = 0; i < bindings.size(); i++) {
               this.screens.add(HolographicEditorScreenState.PreviewScreenSpec.fromBinding("屏幕 " + (i + 1), bindings.get(i)));
            }

            if (this.screens.isEmpty()) {
               this.screens.add(HolographicEditorScreenState.PreviewScreenSpec.defaults());
            }

            this.selectedScreen = Math.max(0, Math.min(this.screens.size() - 1, this.selectedScreen));
         }
      }
   }

   protected void saveEquippedGlassesConfig() {
      if (this.bindEquippedGlasses) {
         Player player = Minecraft.getInstance().player;
         if (player != null) {
            ItemStack head = EquippedMediaItems.firstHolographicGlasses(player);
            if (!HolographicGlassesAbility.has(head)) {
               player.sendSystemMessage(Component.literal("未佩戴全息眼镜，配置未保存"));
            } else {
               List<HolographicGlassesItem.ScreenConfig> configs = new ArrayList<>();

               for (int i = 0; i < this.screens.size(); i++) {
                  HolographicGlassesItem.ScreenConfig config = this.screens.get(i).toConfig();
                  configs.add(config);
                  PacketDistributor.sendToServer(HolographicGlassesConfigPacket.fromConfig(i, config), new CustomPacketPayload[0]);
               }

               HolographicGlassesItem.writeScreenConfigs(head, configs);
               player.sendSystemMessage(Component.literal("全息眼镜配置已保存（" + configs.size() + " 屏）"));
            }
         }
      }
   }

   protected void init() {
      this.clearWidgets();
      this.clearNumericPanelRefs();
      if (this.controlConsoleMode) {
         this.ensureConsoleDocumentLoaded();
      }

      int iconY = 4;
      int buttonCount = this.controlConsoleMode ? 6 : 5;
      int startX = this.width - 8 - (22 * buttonCount + 3 * (buttonCount - 1));
      this.addRenderableWidget(
         new BlackGoldButton(startX, iconY, 22, 18, Component.literal("⇱"), btn -> this.activeTool = HolographicEditorScreenState.EditTool.MOVE, -2840509)
      );
      int x = startX + 25;
      this.addRenderableWidget(
         new BlackGoldButton(x, iconY, 22, 18, Component.literal("↻"), btn -> this.activeTool = HolographicEditorScreenState.EditTool.ROTATE, -2840509)
      );
      x += 25;
      this.addRenderableWidget(
         new BlackGoldButton(x, iconY, 22, 18, Component.literal("⇲"), btn -> this.activeTool = HolographicEditorScreenState.EditTool.SCALE, -2840509)
      );
      x += 25;
      this.addRenderableWidget(
         new BlackGoldButton(x, iconY, 22, 18, Component.literal(this.coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本" : "世"), btn -> {
            this.coordinateSpace = this.coordinateSpace == GizmoCoordinateSpace.LOCAL ? GizmoCoordinateSpace.WORLD : GizmoCoordinateSpace.LOCAL;
            btn.setMessage(Component.literal(this.coordinateSpace == GizmoCoordinateSpace.LOCAL ? "本" : "世"));
         }, -2840509)
      );
      x += 25;
      if (this.controlConsoleMode) {
         this.addRenderableWidget(new BlackGoldButton(x, iconY, 22, 18, Component.literal("魂"), btn -> this.startWorldRoaming(), -12208153));
         x += 25;
      }

      this.addRenderableWidget(new BlackGoldButton(x, iconY, 22, 18, Component.literal("✕"), btn -> this.onClose(), -3129280));
      if (this.showNumericPanel) {
         this.addNumericPanelWidgets();
      }

      if (this.controlConsoleMode) {
         this.addControlConsoleWidgets();
         this.addControlConsoleInspectorWidgets();
         this.addControlConsoleDocumentWidgets();
      }

      this.applyInitialElementFocus();
   }

   protected abstract ControlConsoleDocument controlConsoleDocument();

   protected abstract ControlConsoleDocument currentConsoleDocument();

   protected abstract List<ControlConsoleElement> consoleElementsSnapshot();

   protected abstract void loadConsoleElements(ControlConsoleDocument var1);

   protected abstract void setPreviewCamera(EditorCameraState var1);

   protected abstract boolean navigationMode();

   protected abstract void clearFlyKeys();

   protected abstract void clearNumericPanelRefs();

   protected abstract void ensureConsoleDocumentLoaded();

   protected abstract void addNumericPanelWidgets();

   protected abstract void addControlConsoleWidgets();

   protected abstract void addControlConsoleInspectorWidgets();

   protected abstract void addControlConsoleDocumentWidgets();

   protected abstract void applyInitialElementFocus();

   protected abstract void focusControlConsoleCenter();

   protected abstract void focusSelectedScreen();

   protected abstract void syncNumericEditBoxes();

   protected abstract void selectElement(int var1);

   protected abstract boolean selectedElementEditable();

   protected abstract void edit(String var1, Runnable var2);

   protected abstract HolographicEditorScreenState.EditorSceneState snapshotScene();
}
