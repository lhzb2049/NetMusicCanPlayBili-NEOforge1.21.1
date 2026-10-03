package com.zhongbai233.net_music_can_play_bili.gui;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.PadClient;
import com.zhongbai233.net_music_can_play_bili.client.PadFocusState;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSampler;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.network.PadPlaybackControlPacket;
import com.zhongbai233.net_music_can_play_bili.network.PadPublishPacket;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public class PadFocusScreen extends Screen {
   private static final int TEXTURE_W = 448;
   private static final int TEXTURE_H = 256;
   private static final int MAP_X = 14;
   private static final int MAP_Y = 44;
   private static final int MAP_W = 270;
   private static final int MAP_H = 198;
   private static final int LOCKED_MAP_X = 0;
   private static final int LOCKED_MAP_Y = 0;
   private static final int LOCKED_MAP_W = 448;
   private static final int LOCKED_MAP_H = 256;
   private static final int MEDIA_X = 300;
   private static final int MEDIA_Y = 48;
   private static final int MEDIA_W = 132;
   private static final int MEDIA_H = 98;
   private static final int EDITOR_X = 300;
   private static final int EDITOR_Y = 130;
   private static final int EDITOR_W = 132;
   private static final int EDITOR_H = 86;
   private static final int PUBLISH_X = 300;
   private static final int PUBLISH_Y = 220;
   private static final int PUBLISH_W = 132;
   private static final int PUBLISH_H = 18;
   private final InteractionHand hand;
   private boolean draggingProgress;

   public PadFocusScreen(InteractionHand hand) {
      super(Component.translatable("gui.net_music_can_play_bili.pad.focus"));
      this.hand = hand;
   }

   public boolean isPauseScreen() {
      return false;
   }

   protected void init() {
      PadFocusState.activate(this.hand);
   }

   public void onClose() {
      PadFocusState.deactivate();
      super.onClose();
   }

   public void tick() {
   }

   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.updateHover(mouseX, mouseY);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.updateHover(mouseX, mouseY);
      super.render(graphics, mouseX, mouseY, partialTick);
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      this.updateHover((int)mouseX, (int)mouseY);
      if (button == 1) {
         PadFocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
         if (point != null && this.insideMap(point, PadClient.cachedDocumentFor(this.heldPadStack()))) {
            PadTriggerPoint hitPoint = this.pointAt(point);
            PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
            if (hitPoint != null && !document.locked()) {
               this.removePoint(hitPoint.pointId());
               return true;
            }
         }

         this.onClose();
         return true;
      } else if (button == 0) {
         this.handleLeftClick((int)mouseX, (int)mouseY);
         return true;
      } else {
         return false;
      }
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      this.updateHover((int)mouseX, (int)mouseY);
      if (button == 0 && this.draggingProgress) {
         PadFocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
         if (point != null) {
            UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
            boolean video = PadFocusState.pausedVideo() || deviceId != null && MP4HandheldVideoClient.latestFrame(deviceId) != null;
            PadFocusState.setMediaProgress(video ? this.videoProgressAt(point) : this.audioProgressAt(point));
         }

         return true;
      } else if (button == 0 && PadFocusState.draggingPoint()) {
         PadFocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
         if (point != null && this.insideMap(point, PadClient.cachedDocumentFor(this.heldPadStack()))) {
            PadFocusState.updatePointDragPreview(point.x(), point.y());
         }

         return true;
      } else {
         return button == 0 || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      this.updateHover((int)mouseX, (int)mouseY);
      if (button == 0 && PadFocusState.draggingMedia()) {
         PadFocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
         if (point != null && this.insideMap(point, PadClient.cachedDocumentFor(this.heldPadStack()))) {
            this.createPointFromDrag(point, PadFocusState.draggingMediaId(), PadFocusState.draggingMediaName());
         }

         PadFocusState.endMediaDrag();
         return true;
      } else if (button == 0 && this.draggingProgress) {
         this.draggingProgress = false;
         PadFocusState.setScrubbingProgress(false);
         this.seekPlayback(this.currentProgressMillis());
         return true;
      } else if (button == 0 && PadFocusState.draggingPoint()) {
         PadFocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
         if (point != null && this.inside(point, 14, 44, 270, 198)) {
            this.moveSelectedPoint(point);
         }

         PadFocusState.endPointDrag();
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      this.updateHover((int)mouseX, (int)mouseY);
      return true;
   }

   private void updateHover(int mouseX, int mouseY) {
      PadFocusScreen.TexturePoint point = this.toTexturePoint(mouseX, mouseY);
      if (point == null) {
         PadFocusState.clearHoverTarget();
      } else {
         float localX = point.x() / 447.0F * 2.0F - 1.0F;
         float localY = point.y() / 255.0F * 2.0F - 1.0F;
         PadFocusState.updateHover(localX, localY);
         PadFocusState.updateHoverTarget(point.x(), point.y(), this.hit(point));
      }
   }

   private void handleLeftClick(int mouseX, int mouseY) {
      PadFocusScreen.TexturePoint point = this.toTexturePoint(mouseX, mouseY);
      if (point != null) {
         String control = this.hit(point);
         PadFocusState.pressFeedback(point.x(), point.y(), control);
         if (!this.handleLockedVideoControl(point, control)) {
            if (!this.handleLockedAudioControl(point, control)) {
               if ("MEDIA".equals(control)) {
                  PadMediaEntry entry = this.mediaEntryAt(point);
                  if (entry != null) {
                     PadFocusState.beginMediaDrag(entry.mediaId(), this.mediaName(entry));
                  }
               } else if ("MAP".equals(control)) {
                  PadTriggerPoint hitPoint = this.pointAt(point);
                  if (hitPoint != null) {
                     PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
                     if (document.locked()) {
                        this.playPoint(hitPoint);
                     } else {
                        PadFocusState.beginPointDrag(hitPoint.pointId());
                     }
                  } else {
                     PadFocusState.selectPoint(null);
                  }
               } else if ("EDITOR".equals(control)) {
                  this.handleEditorClick(point);
               } else if ("PLAYBACK".equals(control)) {
                  this.stopPlayback();
               } else {
                  if ("PUBLISH".equals(control)) {
                     this.publishLockedCopy();
                  }
               }
            }
         }
      }
   }

   private boolean handleLockedVideoControl(PadFocusScreen.TexturePoint point, String control) {
      PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      if (document.locked() && deviceId != null && (ClientMediaPlayback.hasPlayback(deviceId) || PadFocusState.pausedPlaybackAvailable())) {
         switch (control) {
            case "VIDEO_PROGRESS":
               this.draggingProgress = true;
               PadFocusState.setScrubbingProgress(true);
               PadFocusState.setMediaProgress(this.videoProgressAt(point));
               return true;
            case "VIDEO_PLAY":
               this.togglePlayback();
               PadFocusState.showControlsTemporarily();
               return true;
            case "VIDEO_STOP":
               this.stopPlayback();
               PadFocusState.showControlsTemporarily();
               return true;
            case "VIDEO_QUALITY":
               PadFocusState.toggleQualityMenu();
               return true;
            case "VIDEO_SUBTITLE":
               PadFocusState.toggleSubtitleMenu();
               return true;
            case "SUBTITLE_OFF":
               PadFocusState.disableSubtitle();
               return true;
            case "SUBTITLE_PRIMARY":
               PadFocusState.selectSubtitleMode(0);
               return true;
            case "SUBTITLE_SECONDARY":
               PadFocusState.selectSubtitleMode(1);
               return true;
            case "SUBTITLE_AI":
               PadFocusState.toggleSubtitleAi();
               return true;
            case "QUALITY_0":
            case "QUALITY_1":
            case "QUALITY_2":
            case "QUALITY_3":
            case "QUALITY_4":
            case "QUALITY_5":
            case "QUALITY_6":
            case "QUALITY_7":
               PadFocusState.selectQualityIndex(control.charAt(control.length() - 1) - '0');
               return true;
            case "MAP":
               PadFocusState.toggleControls();
               return true;
            default:
               if (PadFocusState.controlsVisible()) {
                  PadFocusState.showControlsTemporarily();
               }

               return false;
         }
      } else {
         return false;
      }
   }

   private boolean handleLockedAudioControl(PadFocusScreen.TexturePoint point, String control) {
      PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      boolean audioPaused = PadFocusState.pausedPlaybackAvailable() && !PadFocusState.pausedVideo();
      if (document.locked() && deviceId != null && (ClientMediaPlayback.hasPlayback(deviceId) || audioPaused)) {
         switch (control) {
            case "AUDIO_PROGRESS":
               this.draggingProgress = true;
               PadFocusState.setScrubbingProgress(true);
               PadFocusState.setMediaProgress(this.audioProgressAt(point));
               return true;
            case "AUDIO_PLAY":
               this.togglePlayback();
               return true;
            case "AUDIO_STOP":
               this.stopPlayback();
               return true;
            case "AUDIO_SUBTITLE":
               PadFocusState.toggleSubtitleMenu();
               return true;
            default:
               return false;
         }
      } else {
         return false;
      }
   }

   private void publishLockedCopy() {
      ItemStack stack = this.heldPadStack();
      if (PadItem.isPad(stack)) {
         PadDocument document = PadClient.cachedDocumentFor(stack);
         if (!document.locked()) {
            UUID deviceId = PadItem.readDeviceId(stack);
            if (deviceId != null) {
               this.writeDocument(stack, document.withLocked(false));
               PacketDistributor.sendToServer(new PadPublishPacket(deviceId), new CustomPacketPayload[0]);
               PadFocusState.selectPoint(null);
               PadFocusState.endMediaDrag();
               PadFocusState.endPointDrag();
            }
         }
      }
   }

   private void handleEditorClick(PadFocusScreen.TexturePoint point) {
      PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
      if (!document.locked()) {
         PadTriggerPoint selected = this.selectedPoint(document);
         if (selected != null) {
            int localX = point.x() - 300;
            int localY = point.y() - 130;
            if (localY >= 28 && localY < 43 && localX >= 8 && localX < 60) {
               this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks(), !selected.visible(), selected.volumePerMille(), selected.loop()));
            } else if (localY >= 28 && localY < 43 && localX >= 66 && localX < 124) {
               this.removePoint(selected.pointId());
            } else if (localY >= 45 && localY < 60 && localX >= 8 && localX < 36) {
               this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks() - 1, selected.visible(), selected.volumePerMille(), selected.loop()));
            } else if (localY >= 45 && localY < 60 && localX >= 40 && localX < 68) {
               this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks() + 1, selected.visible(), selected.volumePerMille(), selected.loop()));
            } else if (localY >= 45 && localY < 60 && localX >= 74 && localX < 124) {
               this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille(), !selected.loop()));
            } else if (localY >= 62 && localY < 77 && localX >= 8 && localX < 60) {
               this.updatePoint(
                  this.rebuildPoint(
                     selected,
                     selected.radiusBlocks(),
                     selected.visible(),
                     selected.volumePerMille(),
                     selected.loop(),
                     selected.triggerMode() == PadTriggerMode.MANUAL ? PadTriggerMode.ENTER_RADIUS : PadTriggerMode.MANUAL
                  )
               );
            } else if (localY >= 62 && localY < 77 && localX >= 66 && localX < 94) {
               this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille() - 100, selected.loop()));
            } else {
               if (localY >= 62 && localY < 77 && localX >= 96 && localX < 124) {
                  this.updatePoint(this.rebuildPoint(selected, selected.radiusBlocks(), selected.visible(), selected.volumePerMille() + 100, selected.loop()));
               }
            }
         }
      }
   }

   private void createPointFromDrag(PadFocusScreen.TexturePoint point, int mediaId, String mediaName) {
      ItemStack stack = this.heldPadStack();
      if (PadItem.isPad(stack)) {
         PadDocument document = PadClient.cachedDocumentFor(stack);
         if (!document.locked() && document.triggerPoints().size() < 128) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
               PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(), minecraft.player.blockPosition().getZ());
               PadMapProjection.Rect mapRect = this.visibleMapRect();
               PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, mapRect, minecraft.player.getX(), minecraft.player.getZ());
               float worldX = PadMapProjection.screenToWorldX(point.x(), map, viewport);
               float worldZ = PadMapProjection.screenToWorldZ(point.y(), map, viewport);
               PadTriggerPoint created = PadTriggerPoint.createManual(mediaName, worldX, minecraft.player.getY(), worldZ, mediaId);
               this.writeDocument(stack, document.withTrigger(created));
               PadFocusState.selectPoint(created.pointId());
               PadFocusState.pressFeedback(point.x(), point.y(), "MAP");
            }
         }
      }
   }

   private void moveSelectedPoint(PadFocusScreen.TexturePoint point) {
      ItemStack stack = this.heldPadStack();
      PadDocument document = PadClient.cachedDocumentFor(stack);
      if (PadItem.isPad(stack) && !document.locked()) {
         PadTriggerPoint selected = this.selectedPoint(document);
         if (selected != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
               PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(), minecraft.player.blockPosition().getZ());
               PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, this.visibleMapRect(), minecraft.player.getX(), minecraft.player.getZ());
               this.writeDocument(
                  stack,
                  document.withTrigger(
                     new PadTriggerPoint(
                        selected.pointId(),
                        selected.name(),
                        PadMapProjection.screenToWorldX(point.x(), map, viewport),
                        selected.y(),
                        PadMapProjection.screenToWorldZ(point.y(), map, viewport),
                        selected.radiusBlocks(),
                        selected.mediaId(),
                        selected.triggerMode(),
                        selected.loop(),
                        selected.volumePerMille(),
                        selected.visible()
                     )
                  )
               );
            }
         }
      }
   }

   private PadTriggerPoint pointAt(PadFocusScreen.TexturePoint point) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return null;
      } else {
         PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
         PadMapSnapshot map = PadMapClientCache.snapshot(minecraft.player.blockPosition().getX(), minecraft.player.blockPosition().getZ());
         PadMapProjection.Viewport viewport = PadMapProjection.viewport(map, this.visibleMapRect(), minecraft.player.getX(), minecraft.player.getZ());

         for (int i = document.triggerPoints().size() - 1; i >= 0; i--) {
            PadTriggerPoint trigger = document.triggerPoints().get(i);
            if (!document.locked() || trigger.visible()) {
               int px = Math.round(PadMapProjection.mapScreenX((float)trigger.x(), map, viewport));
               int pz = Math.round(PadMapProjection.mapScreenY((float)trigger.z(), map, viewport));
               if (Math.abs(point.x() - px) <= 10 && Math.abs(point.y() - pz) <= 14) {
                  return trigger;
               }
            }
         }

         return null;
      }
   }

   private PadTriggerPoint selectedPoint(PadDocument document) {
      UUID selected = PadFocusState.selectedPointId();
      return selected == null ? null : document.triggerPoints().stream().filter(point -> selected.equals(point.pointId())).findFirst().orElse(null);
   }

   private void updatePoint(PadTriggerPoint point) {
      ItemStack stack = this.heldPadStack();
      PadDocument document = PadClient.cachedDocumentFor(stack);
      if (PadItem.isPad(stack) && !document.locked()) {
         this.writeDocument(stack, document.withTrigger(point));
         PadFocusState.selectPoint(point.pointId());
      }
   }

   private void removePoint(UUID pointId) {
      ItemStack stack = this.heldPadStack();
      PadDocument document = PadClient.cachedDocumentFor(stack);
      if (PadItem.isPad(stack) && !document.locked() && pointId != null) {
         ArrayList<PadTriggerPoint> points = new ArrayList<>(document.triggerPoints());
         if (points.removeIf(point -> pointId.equals(point.pointId()))) {
            this.writeDocument(
               stack,
               new PadDocument(
                  document.title(),
                  document.author(),
                  document.locked(),
                  System.currentTimeMillis(),
                  document.sequence() + 1L,
                  document.mapSettings(),
                  document.mediaEntries(),
                  points
               )
            );
            PadFocusState.selectPoint(null);
         }
      }
   }

   private void playPoint(PadTriggerPoint point) {
      if (point != null && point.triggerMode() == PadTriggerMode.MANUAL) {
         UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
         if (deviceId != null) {
            PacketDistributor.sendToServer(
               new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.START, deviceId, point.pointId(), 0L), new CustomPacketPayload[0]
            );
            PadFocusState.selectPoint(point.pointId());
         }
      }
   }

   private void stopPlayback() {
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
         PadFocusState.clearPausedPlayback();
         PacketDistributor.sendToServer(
            new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.STOP, deviceId, null, ClientMediaPlayback.elapsedMillis(deviceId)),
            new CustomPacketPayload[0]
         );
      } else {
         PadFocusState.clearPausedPlayback();
      }
   }

   private void pausePlayback() {
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      if (deviceId != null && ClientMediaPlayback.hasPlayback(deviceId)) {
         UUID pointId = this.activePlaybackPointId(deviceId);
         long elapsedMillis = ClientMediaPlayback.elapsedMillis(deviceId);
         if (pointId != null) {
            PadFocusState.rememberPausedPlayback(
               pointId, elapsedMillis, ClientMediaPlayback.durationMillis(deviceId), MP4HandheldVideoClient.latestFrame(deviceId) != null
            );
         }

         PacketDistributor.sendToServer(
            new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.PAUSE, deviceId, null, elapsedMillis), new CustomPacketPayload[0]
         );
      }
   }

   private void togglePlayback() {
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      if (deviceId != null) {
         if (ClientMediaPlayback.hasPlayback(deviceId)) {
            this.pausePlayback();
         } else {
            UUID pointId = PadFocusState.pausedPointId();
            if (pointId != null) {
               PacketDistributor.sendToServer(
                  new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.START, deviceId, pointId, PadFocusState.pausedElapsedMillis()),
                  new CustomPacketPayload[0]
               );
               PadFocusState.clearPausedPlayback();
            }
         }
      }
   }

   private void seekPlayback(long targetMillis) {
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      if (deviceId != null) {
         if (!ClientMediaPlayback.hasPlayback(deviceId)) {
            UUID pointId = PadFocusState.pausedPointId();
            if (pointId != null) {
               PadFocusState.rememberPausedPlayback(pointId, targetMillis, PadFocusState.pausedDurationMillis(), PadFocusState.pausedVideo());
            }
         } else {
            PacketDistributor.sendToServer(
               new PadPlaybackControlPacket(PadPlaybackControlPacket.Action.SEEK, deviceId, null, Math.max(0L, targetMillis)), new CustomPacketPayload[0]
            );
         }
      }
   }

   private UUID activePlaybackPointId(UUID deviceId) {
      int mediaId = ClientMediaPlayback.queueIndex(deviceId);
      if (mediaId < 0) {
         return null;
      } else {
         PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());

         for (PadTriggerPoint point : document.triggerPoints()) {
            if (point.mediaId() == mediaId) {
               return point.pointId();
            }
         }

         return null;
      }
   }

   private long currentProgressMillis() {
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      long duration = deviceId != null ? ClientMediaPlayback.durationMillis(deviceId) : 0L;
      if (duration <= 0L && PadFocusState.pausedPlaybackAvailable()) {
         duration = PadFocusState.pausedDurationMillis();
      }

      return Math.round(PadFocusState.mediaProgress() * (float)Math.max(0L, duration));
   }

   private float videoProgressAt(PadFocusScreen.TexturePoint point) {
      int x0 = 32;
      int w = 384;
      return Math.max(0.0F, Math.min(1.0F, (float)(point.x() - x0) / Math.max(1, w)));
   }

   private float audioProgressAt(PadFocusScreen.TexturePoint point) {
      int x0 = 36;
      int w = 376;
      return Math.max(0.0F, Math.min(1.0F, (float)(point.x() - x0) / Math.max(1, w)));
   }

   private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop) {
      return this.rebuildPoint(point, radius, visible, volume, loop, point.triggerMode());
   }

   private PadTriggerPoint rebuildPoint(PadTriggerPoint point, int radius, boolean visible, int volume, boolean loop, PadTriggerMode mode) {
      return new PadTriggerPoint(point.pointId(), point.name(), point.x(), point.y(), point.z(), radius, point.mediaId(), mode, loop, volume, visible);
   }

   private PadMediaEntry mediaEntryAt(PadFocusScreen.TexturePoint point) {
      int localY = point.y() - 48;
      if (localY < 30) {
         return null;
      } else {
         int row = (localY - 30) / 16;
         if (row >= 0 && row < 4) {
            PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
            return row < document.mediaEntries().size() ? document.mediaEntries().get(row) : null;
         } else {
            return null;
         }
      }
   }

   private void writeDocument(ItemStack stack, PadDocument document) {
      PadItem.writeDocument(stack, document);
      PadClient.markDocumentDirty(stack, document);
   }

   private String mediaName(PadMediaEntry entry) {
      SongInfo info = ItemMusicCD.getSongInfo(entry.disc());
      return info != null && info.songName != null && !info.songName.isBlank() ? info.songName : "歌曲 #" + entry.mediaId();
   }

   private ItemStack heldPadStack() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack stack = minecraft.player.getItemInHand(this.hand);
         return PadItem.isPad(stack) ? stack : ItemStack.EMPTY;
      }
   }

   private PadMapProjection.Rect visibleMapRect() {
      PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
      int x = document.locked() ? 0 : 14;
      int y = document.locked() ? 0 : 44;
      int w = document.locked() ? 448 : 270;
      int h = document.locked() ? 256 : 198;
      int inset = document.locked() ? 0 : 6;
      return document.locked()
         ? new PadMapProjection.Rect(x, y, w, h)
         : PadMapProjection.fitRect(
            x + inset, y + inset, w - inset * 2, h - inset * 2, PadMapSampler.DEFAULT_VIEW_WIDTH, PadMapSampler.DEFAULT_VIEW_HEIGHT, 1.75F
         );
   }

   private String hit(PadFocusScreen.TexturePoint point) {
      PadDocument document = PadClient.cachedDocumentFor(this.heldPadStack());
      UUID deviceId = PadItem.readDeviceId(this.heldPadStack());
      boolean hasPlayback = ClientMediaPlayback.hasPlayback(deviceId);
      if (document.locked() && (hasPlayback || PadFocusState.pausedPlaybackAvailable())) {
         boolean videoControls = PadFocusState.pausedVideo() || hasPlayback && MP4HandheldVideoClient.latestFrame(deviceId) != null;
         if (!videoControls) {
            if (PadFocusState.subtitleMenuOpen()) {
               if (this.inside(point, 244, 72, 52, 14)) {
                  return "SUBTITLE_OFF";
               }

               if (this.inside(point, 244, 94, 52, 14)) {
                  return "SUBTITLE_PRIMARY";
               }

               if (this.inside(point, 244, 116, 52, 14)) {
                  return "SUBTITLE_SECONDARY";
               }

               if (this.inside(point, 244, 138, 82, 14)) {
                  return "SUBTITLE_AI";
               }
            }

            if (this.inside(point, 36, 194, 376, 16)) {
               return "AUDIO_PROGRESS";
            }

            if (this.inside(point, 146, 210, 30, 24)) {
               return "AUDIO_STOP";
            }

            if (this.inside(point, 200, 206, 46, 32)) {
               return "AUDIO_PLAY";
            }

            if (this.inside(point, 270, 210, 54, 24)) {
               return "AUDIO_SUBTITLE";
            }
         }

         if (PadFocusState.controlsVisible()) {
            if (PadFocusState.qualityMenuOpen()) {
               for (int i = 0; i < PadFocusState.QUALITIES.length; i++) {
                  if (this.inside(point, 330, 65 + i * 13, 70, 11)) {
                     return "QUALITY_" + i;
                  }
               }
            }

            if (PadFocusState.subtitleMenuOpen()) {
               if (this.inside(point, 244, 72, 52, 14)) {
                  return "SUBTITLE_OFF";
               }

               if (this.inside(point, 244, 94, 52, 14)) {
                  return "SUBTITLE_PRIMARY";
               }

               if (this.inside(point, 244, 116, 52, 14)) {
                  return "SUBTITLE_SECONDARY";
               }

               if (this.inside(point, 244, 138, 82, 14)) {
                  return "SUBTITLE_AI";
               }
            }

            if (this.inside(point, 278, 16, 44, 20)) {
               return "VIDEO_SUBTITLE";
            }

            if (this.inside(point, 330, 14, 52, 28)) {
               return "VIDEO_QUALITY";
            }

            if (this.inside(point, 32, 176, 384, 18)) {
               return "VIDEO_PROGRESS";
            }

            if (this.inside(point, 146, 204, 30, 24)) {
               return "VIDEO_STOP";
            }

            if (this.inside(point, 200, 198, 46, 34)) {
               return "VIDEO_PLAY";
            }

            if (this.inside(point, 270, 204, 54, 24)) {
               return "VIDEO_QUALITY";
            }
         }

         if (this.insideMap(point, document)) {
            return "MAP";
         }
      }

      if (this.insideMap(point, document)) {
         return "MAP";
      } else if (document.locked()) {
         return "NONE";
      } else if (this.inside(point, 300, 48, 132, 98)) {
         return "MEDIA";
      } else if (this.inside(point, 300, 130, 132, 86)) {
         return "EDITOR";
      } else if (this.inside(point, 300, 166, 132, 42)) {
         return "PLAYBACK";
      } else {
         return this.inside(point, 300, 220, 132, 18) ? "PUBLISH" : "NONE";
      }
   }

   private boolean insideMap(PadFocusScreen.TexturePoint point, PadDocument document) {
      return document != null && document.locked() ? this.inside(point, 0, 0, 448, 256) : this.inside(point, 14, 44, 270, 198);
   }

   private boolean inside(PadFocusScreen.TexturePoint point, int x, int y, int w, int h) {
      return point.x() >= x && point.y() >= y && point.x() < x + w && point.y() < y + h;
   }

   private PadFocusScreen.TexturePoint toTexturePoint(int mouseX, int mouseY) {
      PadFocusScreen.Quad quad = this.projectedInputQuadOrNull();
      return quad != null ? quad.toTexturePoint(mouseX, mouseY) : null;
   }

   private PadFocusScreen.Quad projectedInputQuadOrNull() {
      return !PadFocusState.hasProjectedQuad(this.width, this.height)
         ? null
         : new PadFocusScreen.Quad(
            new PadFocusScreen.ScreenPoint(PadFocusState.projectedQuadX(0), PadFocusState.projectedQuadY(0)),
            new PadFocusScreen.ScreenPoint(PadFocusState.projectedQuadX(1), PadFocusState.projectedQuadY(1)),
            new PadFocusScreen.ScreenPoint(PadFocusState.projectedQuadX(2), PadFocusState.projectedQuadY(2)),
            new PadFocusScreen.ScreenPoint(PadFocusState.projectedQuadX(3), PadFocusState.projectedQuadY(3))
         );
   }

   private record ProjectedSurface(int left, int top, int width, int height) {
      PadFocusScreen.ProjectedSurface inflate(int amount) {
         return new PadFocusScreen.ProjectedSurface(this.left - amount, this.top - amount, this.width + amount * 2, this.height + amount * 2);
      }

      boolean contains(int x, int y) {
         return x >= this.left && y >= this.top && x < this.left + this.width && y < this.top + this.height;
      }
   }

   private record Quad(
      PadFocusScreen.ScreenPoint topLeft, PadFocusScreen.ScreenPoint topRight, PadFocusScreen.ScreenPoint bottomRight, PadFocusScreen.ScreenPoint bottomLeft
   ) {
      PadFocusScreen.TexturePoint toTexturePoint(float screenX, float screenY) {
         if (!this.bounds().inflate(4).contains(Math.round(screenX), Math.round(screenY))) {
            return null;
         } else {
            float u = 0.5F;
            float v = 0.5F;

            for (int i = 0; i < 8; i++) {
               PadFocusScreen.ScreenPoint p = this.sample(u, v);
               float dx = p.x() - screenX;
               float dy = p.y() - screenY;
               if (Math.abs(dx) + Math.abs(dy) < 0.01F) {
                  break;
               }

               PadFocusScreen.ScreenPoint du = this.derivativeU(v);
               PadFocusScreen.ScreenPoint dv = this.derivativeV(u);
               float det = du.x() * dv.y() - du.y() * dv.x();
               if (Math.abs(det) < 1.0E-4F) {
                  break;
               }

               float deltaU = (dx * dv.y() - dy * dv.x()) / det;
               float deltaV = (du.x() * dy - du.y() * dx) / det;
               u = clamp(u - deltaU);
               v = clamp(v - deltaV);
            }

            PadFocusScreen.ScreenPoint resolved = this.sample(u, v);
            float error = Math.abs(resolved.x() - screenX) + Math.abs(resolved.y() - screenY);
            return error > 18.0F ? null : new PadFocusScreen.TexturePoint(Math.round(u * 447.0F), Math.round(v * 255.0F));
         }
      }

      PadFocusScreen.ProjectedSurface bounds() {
         float minX = Math.min(Math.min(this.topLeft.x(), this.topRight.x()), Math.min(this.bottomRight.x(), this.bottomLeft.x()));
         float minY = Math.min(Math.min(this.topLeft.y(), this.topRight.y()), Math.min(this.bottomRight.y(), this.bottomLeft.y()));
         float maxX = Math.max(Math.max(this.topLeft.x(), this.topRight.x()), Math.max(this.bottomRight.x(), this.bottomLeft.x()));
         float maxY = Math.max(Math.max(this.topLeft.y(), this.topRight.y()), Math.max(this.bottomRight.y(), this.bottomLeft.y()));
         return new PadFocusScreen.ProjectedSurface(Math.round(minX), Math.round(minY), Math.round(maxX - minX), Math.round(maxY - minY));
      }

      private PadFocusScreen.ScreenPoint sample(float u, float v) {
         float topX = lerp(this.topLeft.x(), this.topRight.x(), u);
         float topY = lerp(this.topLeft.y(), this.topRight.y(), u);
         float bottomX = lerp(this.bottomLeft.x(), this.bottomRight.x(), u);
         float bottomY = lerp(this.bottomLeft.y(), this.bottomRight.y(), u);
         return new PadFocusScreen.ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
      }

      private PadFocusScreen.ScreenPoint derivativeU(float v) {
         float topX = this.topRight.x() - this.topLeft.x();
         float topY = this.topRight.y() - this.topLeft.y();
         float bottomX = this.bottomRight.x() - this.bottomLeft.x();
         float bottomY = this.bottomRight.y() - this.bottomLeft.y();
         return new PadFocusScreen.ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
      }

      private PadFocusScreen.ScreenPoint derivativeV(float u) {
         float leftX = this.bottomLeft.x() - this.topLeft.x();
         float leftY = this.bottomLeft.y() - this.topLeft.y();
         float rightX = this.bottomRight.x() - this.topRight.x();
         float rightY = this.bottomRight.y() - this.topRight.y();
         return new PadFocusScreen.ScreenPoint(lerp(leftX, rightX, u), lerp(leftY, rightY, u));
      }

      private static float lerp(float a, float b, float t) {
         return a + (b - a) * t;
      }

      private static float clamp(float value) {
         return Math.max(0.0F, Math.min(1.0F, value));
      }
   }

   private record ScreenPoint(float x, float y) {
   }

   private record TexturePoint(int x, int y) {
   }
}
