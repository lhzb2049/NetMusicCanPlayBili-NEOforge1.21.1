package com.zhongbai233.net_music_can_play_bili.gui;

import com.zhongbai233.net_music_can_play_bili.client.MP4BiliLoginOverlay;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4EnsureDeviceIdPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

public class MP4FocusScreen extends Screen {
   private static final int TEXTURE_W = 256;
   private static final int TEXTURE_H = 448;
   private static final int BORDER_DRAG_PIXELS = 18;
   private static final float BORDER_DRAG_REVERSE_LIMIT = 16.0F;
   private static final float FLICK_IMPULSE_SCALE = 0.22F;
   private static final float FLICK_IMPULSE_MAX = 32.0F;
   private static final float FLICK_COMMIT_DEGREES = 24.0F;
   private static final float ORIENTATION_SNAP_DEGREES = 45.0F;
   private final InteractionHand hand;
   private boolean rotatingDevice;
   private MP4FocusScreen.SliderDrag draggingSlider = MP4FocusScreen.SliderDrag.NONE;
   private float rotationStartAngle;
   private float rotationStartDegrees;
   private boolean rotationMoved;
   private float lastDragAngle;
   private long lastDragNanos;
   private float dragVelocityDegreesPerSecond;

   public MP4FocusScreen(InteractionHand hand) {
      super(Component.translatable("gui.net_music_can_play_bili.mp4.focus"));
      this.hand = hand;
   }

   public boolean isPauseScreen() {
      return false;
   }

   protected void init() {
      MP4FocusState.activate(this.hand);
   }

   public void onClose() {
      MP4BiliLoginOverlay.close();
      MP4FocusState.settleRotationForClose();
      MP4Client.flushFocusedStateToServer();
      MP4FocusState.deactivate();
      super.onClose();
   }

   public void tick() {
      MP4FocusState.tick();
      MP4BiliLoginOverlay.tick();
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
         this.onClose();
         return true;
      } else if (button == 0) {
         if (this.isBorderDragStart((int)mouseX, (int)mouseY)) {
            this.startDeviceRotation((int)mouseX, (int)mouseY);
            return true;
         } else {
            this.handleLeftClick((int)mouseX, (int)mouseY);
            return true;
         }
      } else {
         return false;
      }
   }

   public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
      if (this.draggingSlider != MP4FocusScreen.SliderDrag.NONE) {
         this.updateSliderDrag((int)mouseX, (int)mouseY);
         return true;
      } else if (this.rotatingDevice && button == 0) {
         this.updateDeviceRotation((int)mouseX, (int)mouseY);
         return true;
      } else {
         return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
      }
   }

   public boolean mouseReleased(double mouseX, double mouseY, int button) {
      if (this.draggingSlider != MP4FocusScreen.SliderDrag.NONE) {
         MP4FocusScreen.SliderDrag releasedSlider = this.draggingSlider;
         this.draggingSlider = MP4FocusScreen.SliderDrag.NONE;
         if (releasedSlider == MP4FocusScreen.SliderDrag.PROGRESS) {
            MP4FocusState.setScrubbingProgress(false);
            if (MP4FocusState.playing()) {
               this.sendPlayback(MP4PlaybackControlPacket.Action.SEEK, this.currentProgressMillis());
            }
         } else if (releasedSlider == MP4FocusScreen.SliderDrag.VOLUME && MP4FocusState.playing()) {
            this.sendPlayback(MP4PlaybackControlPacket.Action.VOLUME, this.currentProgressMillis());
         }

         MP4Client.syncFocusedStateToServer();
         return true;
      } else if (this.rotatingDevice && button == 0) {
         this.finishDeviceRotation();
         MP4Client.syncFocusedStateToServer();
         return true;
      } else {
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }

   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      this.updateHover((int)mouseX, (int)mouseY);
      MP4FocusScreen.TexturePoint point = this.toTexturePoint((int)mouseX, (int)mouseY);
      if (point != null && MP4FocusScreen.MP4TextureHitTest.hit(point) == MP4FocusScreen.MP4TextureHit.VOLUME) {
         MP4FocusState.adjustVolume(scrollY);
      } else if (MP4FocusState.playlistOpen()) {
         MP4FocusState.scrollQueue(scrollY);
      } else {
         MP4FocusState.adjustVolume(scrollY);
      }

      MP4Client.syncFocusedStateToServer();
      return true;
   }

   private void handleLeftClick(int mouseX, int mouseY) {
      if (this.rotationMoved) {
         this.rotationMoved = false;
      } else {
         MP4FocusScreen.TexturePoint point = this.toTexturePoint(mouseX, mouseY);
         if (point != null) {
            if (MP4FocusState.rotationHintVisible()) {
               if (MP4FocusScreen.MP4TextureHitTest.rotationHintConfirm(point)) {
                  MP4FocusState.confirmRotationHint();
                  MP4Client.syncFocusedStateToServer();
               }
            } else {
               MP4FocusScreen.HitResult hit = new MP4FocusScreen.HitResult(MP4FocusScreen.MP4TextureHitTest.hit(point), point, false);
               if (!MP4BiliLoginOverlay.visible() || hit.hit() == MP4FocusScreen.MP4TextureHit.BILI_LOGIN) {
                  if (hit.hit() == MP4FocusScreen.MP4TextureHit.PROGRESS || hit.hit() == MP4FocusScreen.MP4TextureHit.VOLUME) {
                     this.draggingSlider = hit.hit() == MP4FocusScreen.MP4TextureHit.PROGRESS
                        ? MP4FocusScreen.SliderDrag.PROGRESS
                        : MP4FocusScreen.SliderDrag.VOLUME;
                     if (this.draggingSlider == MP4FocusScreen.SliderDrag.PROGRESS) {
                        MP4FocusState.setScrubbingProgress(true);
                     }

                     this.updateSliderValue(hit.hit(), hit.point());
                  }

                  this.handleHit(hit.hit(), hit.point());
                  MP4Client.syncFocusedStateToServer();
               }
            }
         }
      }
   }

   private void updateSliderDrag(int mouseX, int mouseY) {
      this.updateHover(mouseX, mouseY);
      MP4FocusScreen.TexturePoint point = this.toTexturePoint(mouseX, mouseY);
      if (point != null) {
         this.updateSliderValue(
            this.draggingSlider == MP4FocusScreen.SliderDrag.PROGRESS ? MP4FocusScreen.MP4TextureHit.PROGRESS : MP4FocusScreen.MP4TextureHit.VOLUME, point
         );
      }
   }

   private void updateSliderValue(MP4FocusScreen.MP4TextureHit hit, MP4FocusScreen.TexturePoint point) {
      if (hit == MP4FocusScreen.MP4TextureHit.PROGRESS) {
         MP4FocusState.setMediaProgress(MP4FocusScreen.MP4TextureHitTest.progressAt(point));
      } else if (hit == MP4FocusScreen.MP4TextureHit.VOLUME) {
         MP4FocusState.setVolume(MP4FocusScreen.MP4TextureHitTest.volumeAt(point));
      }
   }

   private boolean isBorderDragStart(int mouseX, int mouseY) {
      MP4FocusScreen.ProjectedSurface surface = this.projectedSurface();
      return surface == null ? false : surface.inflate(18).contains(mouseX, mouseY) && !surface.contains(mouseX, mouseY);
   }

   private void startDeviceRotation(int mouseX, int mouseY) {
      float currentAbsolute = MP4FocusState.deviceRotationDegrees(1.0F);
      MP4FocusState.cancelRotationAnimation();
      this.rotatingDevice = true;
      this.rotationMoved = false;
      this.rotationStartAngle = this.pointerAngle(mouseX, mouseY);
      this.rotationStartDegrees = currentAbsolute;
      this.lastDragAngle = this.rotationStartAngle;
      this.lastDragNanos = System.nanoTime();
      this.dragVelocityDegreesPerSecond = 0.0F;
   }

   private void updateDeviceRotation(int mouseX, int mouseY) {
      float currentAngle = this.pointerAngle(mouseX, mouseY);
      this.updateDragVelocity(currentAngle);
      float rawDelta = -this.normalizeDegrees(currentAngle - this.rotationStartAngle);
      float delta = this.applyRotationDragCurve(rawDelta);
      float absolute = this.rotationStartDegrees + delta;
      float base = MP4FocusState.landscape() ? -90.0F : 0.0F;
      MP4FocusState.setDragRotationDegrees(absolute - base);
      this.rotationMoved = Math.abs(delta) > 2.0F;
      this.updateHover(mouseX, mouseY);
   }

   private void updateDragVelocity(float currentAngle) {
      long now = System.nanoTime();
      float dt = Math.max(0.001F, (float)(now - this.lastDragNanos) / 1.0E9F);
      float rawVelocity = -this.normalizeDegrees(currentAngle - this.lastDragAngle) / dt;
      this.dragVelocityDegreesPerSecond = this.dragVelocityDegreesPerSecond * 0.65F + rawVelocity * 0.35F;
      this.lastDragAngle = currentAngle;
      this.lastDragNanos = now;
   }

   private float applyRotationDragCurve(float rawDelta) {
      boolean reverse = !MP4FocusState.landscape() ? rawDelta > 0.0F : rawDelta < 0.0F;
      if (reverse) {
         float sign = Math.signum(rawDelta);
         float magnitude = Math.abs(rawDelta);
         return sign * 16.0F * (1.0F - (float)Math.exp(-magnitude / 16.0F));
      } else {
         float sign = Math.signum(rawDelta);
         float magnitude = Math.abs(rawDelta);
         float curved;
         if (magnitude < 18.0F) {
            curved = magnitude * 0.86F;
         } else if (magnitude < 54.0F) {
            curved = 15.48F + (magnitude - 18.0F) * 0.72F;
         } else {
            curved = 41.4F + (magnitude - 54.0F) * 1.12F;
         }

         return sign * Math.min(curved, 100.0F);
      }
   }

   private void finishDeviceRotation() {
      float base = MP4FocusState.landscape() ? -90.0F : 0.0F;
      float impulse = Math.max(-32.0F, Math.min(32.0F, this.dragVelocityDegreesPerSecond * 0.22F));
      float absolute = base + MP4FocusState.dragRotationDegrees() + impulse;
      boolean targetLandscape = Math.abs(this.normalizeDegrees(absolute + 90.0F)) < 45.0F;
      boolean targetPortrait = Math.abs(this.normalizeDegrees(absolute)) < 45.0F;
      if (!MP4FocusState.landscape() && impulse < -24.0F) {
         targetLandscape = true;
         targetPortrait = false;
      } else if (MP4FocusState.landscape() && impulse > 24.0F) {
         targetPortrait = true;
         targetLandscape = false;
      }

      if (targetLandscape) {
         float target = MP4FocusState.landscape() ? 0.0F : -90.0F;
         MP4FocusState.animateRotationTo(target, true);
      } else if (targetPortrait) {
         float target = MP4FocusState.landscape() ? 90.0F : 0.0F;
         MP4FocusState.animateRotationTo(target, false);
      } else {
         MP4FocusState.animateRotationTo(0.0F, MP4FocusState.landscape());
      }

      this.rotatingDevice = false;
   }

   private float pointerAngle(int mouseX, int mouseY) {
      MP4FocusScreen.ScreenPoint pivot = this.rotationPivotScreenPoint();
      double dx = mouseX - pivot.x();
      double dy = mouseY - pivot.y();
      return (float)Math.toDegrees(Math.atan2(dy, dx));
   }

   private MP4FocusScreen.ScreenPoint rotationPivotScreenPoint() {
      MP4FocusScreen.Quad quad = this.projectedInputQuadOrNull();
      return quad != null ? quad.pivotPoint(0.58F, 0.88F) : new MP4FocusScreen.ScreenPoint(this.width * 0.5F, this.height * 0.5F);
   }

   private float normalizeDegrees(float degrees) {
      float result = degrees;

      while (result <= -180.0F) {
         result += 360.0F;
      }

      while (result > 180.0F) {
         result -= 360.0F;
      }

      return result;
   }

   private void handleHit(MP4FocusScreen.MP4TextureHit hit, MP4FocusScreen.TexturePoint point) {
      switch (hit) {
         case NONE:
         default:
            break;
         case MEDIA:
            MP4FocusState.toggleControls();
            break;
         case VIDEO_AREA:
            MP4FocusState.toggleControls();
            break;
         case PLAY:
            MP4FocusState.togglePlaying();
            this.sendPlayback(
               MP4FocusState.playing() ? MP4PlaybackControlPacket.Action.START : MP4PlaybackControlPacket.Action.PAUSE, this.currentProgressMillis()
            );
            break;
         case PREVIOUS:
            MP4FocusState.previousTrack();
            if (MP4FocusState.playing()) {
               this.sendPlayback(MP4PlaybackControlPacket.Action.RESTART, 0L);
            }
            break;
         case NEXT:
            MP4FocusState.nextTrack();
            if (MP4FocusState.playing()) {
               this.sendPlayback(MP4PlaybackControlPacket.Action.RESTART, 0L);
            }
            break;
         case PROGRESS:
            MP4FocusState.setMediaProgress(MP4FocusScreen.MP4TextureHitTest.progressAt(point));
            if (MP4FocusState.playing() && this.draggingSlider != MP4FocusScreen.SliderDrag.PROGRESS) {
               this.sendPlayback(MP4PlaybackControlPacket.Action.SEEK, this.currentProgressMillis());
            }
            break;
         case VOLUME:
            MP4FocusState.setVolume(MP4FocusScreen.MP4TextureHitTest.volumeAt(point));
            if (MP4FocusState.playing() && this.draggingSlider != MP4FocusScreen.SliderDrag.VOLUME) {
               this.sendPlayback(MP4PlaybackControlPacket.Action.VOLUME, this.currentProgressMillis());
            }
            break;
         case SHUFFLE:
            MP4FocusState.cyclePlaybackMode();
            break;
         case REPEAT:
            MP4FocusState.cycleRepeat();
            break;
         case PLAYLIST:
            MP4FocusState.togglePlaylist();
            break;
         case LYRICS:
            MP4FocusState.toggleLyrics();
            break;
         case BILI_LOGIN:
            MP4BiliLoginOverlay.toggle();
            break;
         case SUBTITLE_PRIMARY:
            MP4FocusState.selectSubtitleMode(0);
            break;
         case SUBTITLE_SECONDARY:
            MP4FocusState.selectSubtitleMode(1);
            break;
         case SUBTITLE_OFF:
            MP4FocusState.disableSubtitle();
            break;
         case SUBTITLE_AI:
            MP4FocusState.toggleSubtitleAi();
            break;
         case QUALITY:
            MP4FocusState.toggleQualityMenu();
            break;
         case QUALITY_0:
            MP4FocusState.selectQualityIndex(0);
            break;
         case QUALITY_1:
            MP4FocusState.selectQualityIndex(1);
            break;
         case QUALITY_2:
            MP4FocusState.selectQualityIndex(2);
            break;
         case QUALITY_3:
            MP4FocusState.selectQualityIndex(3);
            break;
         case QUALITY_4:
            MP4FocusState.selectQualityIndex(4);
            break;
         case QUALITY_5:
            MP4FocusState.selectQualityIndex(5);
            break;
         case QUALITY_6:
            MP4FocusState.selectQualityIndex(6);
            break;
         case QUALITY_7:
            MP4FocusState.selectQualityIndex(7);
            break;
         case PLAYLIST_AREA:
            MP4FocusState.selectVisibleQueueRow(MP4FocusScreen.MP4TextureHitTest.queueRowAt(point));
      }
   }

   private void sendPlayback(MP4PlaybackControlPacket.Action action, long targetMillis) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null) {
         UUID deviceId = minecraft.player != null ? MP4Item.readDeviceId(minecraft.player.getItemInHand(MP4FocusState.hand())) : null;
         if (deviceId == null) {
            PacketDistributor.sendToServer(new MP4EnsureDeviceIdPacket(MP4FocusState.hand()), new CustomPacketPayload[0]);
            return;
         }

         MP4Client.cacheFocusedState(deviceId);
         PacketDistributor.sendToServer(
            new MP4PlaybackControlPacket(
               action, MP4FocusState.selectedQueueIndex(), Math.round(MP4FocusState.volume() * 1000.0F), Math.max(0L, targetMillis), deviceId
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   private long currentProgressMillis() {
      return Math.round(MP4FocusState.mediaProgress() * (float)MP4FocusState.selectedTrackDurationMillis());
   }

   private void updateHover(int mouseX, int mouseY) {
      MP4FocusScreen.TexturePoint point = this.toTexturePoint(mouseX, mouseY);
      if (point == null) {
         MP4FocusState.clearHoverTarget();
      } else {
         float localX = point.x() / 255.0F * 2.0F - 1.0F;
         float localY = point.y() / 447.0F * 2.0F - 1.0F;
         MP4FocusState.updateHover(localX, localY);
         MP4FocusState.updateHoverTarget(point.x(), point.y(), MP4FocusScreen.MP4TextureHitTest.hit(point).name());
      }
   }

   private MP4FocusScreen.TexturePoint toTexturePoint(int mouseX, int mouseY) {
      MP4FocusScreen.Quad quad = this.projectedInputQuadOrNull();
      return quad != null ? quad.toTexturePoint(mouseX, mouseY) : null;
   }

   private MP4FocusScreen.ProjectedSurface projectedSurface() {
      MP4FocusScreen.Quad quad = this.projectedInputQuadOrNull();
      return quad != null ? quad.bounds() : null;
   }

   private MP4FocusScreen.Quad projectedInputQuadOrNull() {
      return !MP4FocusState.hasProjectedQuad(this.width, this.height)
         ? null
         : new MP4FocusScreen.Quad(
            new MP4FocusScreen.ScreenPoint(MP4FocusState.projectedQuadX(0), MP4FocusState.projectedQuadY(0)),
            new MP4FocusScreen.ScreenPoint(MP4FocusState.projectedQuadX(1), MP4FocusState.projectedQuadY(1)),
            new MP4FocusScreen.ScreenPoint(MP4FocusState.projectedQuadX(2), MP4FocusState.projectedQuadY(2)),
            new MP4FocusScreen.ScreenPoint(MP4FocusState.projectedQuadX(3), MP4FocusState.projectedQuadY(3))
         );
   }

   private record HitResult(MP4FocusScreen.MP4TextureHit hit, MP4FocusScreen.TexturePoint point, boolean snapped) {
   }

   private static enum MP4TextureHit {
      NONE,
      MEDIA,
      VIDEO_AREA,
      PLAY,
      PREVIOUS,
      NEXT,
      PROGRESS,
      VOLUME,
      SHUFFLE,
      REPEAT,
      PLAYLIST,
      LYRICS,
      BILI_LOGIN,
      SUBTITLE_PRIMARY,
      SUBTITLE_SECONDARY,
      SUBTITLE_OFF,
      SUBTITLE_AI,
      QUALITY,
      QUALITY_0,
      QUALITY_1,
      QUALITY_2,
      QUALITY_3,
      QUALITY_4,
      QUALITY_5,
      QUALITY_6,
      QUALITY_7,
      PLAYLIST_AREA;
   }

   private static final class MP4TextureHitTest {
      static MP4FocusScreen.MP4TextureHit hit(MP4FocusScreen.TexturePoint point) {
         return MP4FocusState.landscape() ? landscapeHit(toLandscape(point)) : portraitHit(point);
      }

      static float volumeAt(MP4FocusScreen.TexturePoint point) {
         if (MP4FocusState.landscape()) {
            MP4FocusScreen.TexturePoint landscape = toLandscape(point);
            int x0 = 348;
            int w = 70;
            return (float)(landscape.x() - x0) / Math.max(1, w);
         } else {
            int x0 = 64;
            int w = 154;
            return (float)(point.x() - x0) / Math.max(1, w);
         }
      }

      static float progressAt(MP4FocusScreen.TexturePoint point) {
         if (MP4FocusState.landscape()) {
            MP4FocusScreen.TexturePoint landscape = toLandscape(point);
            int x0 = 32;
            int w = 384;
            return (float)(landscape.x() - x0) / Math.max(1, w);
         } else {
            int x0 = 28;
            int w = 200;
            return (float)(point.x() - x0) / Math.max(1, w);
         }
      }

      static int queueRowAt(MP4FocusScreen.TexturePoint point) {
         if (MP4FocusState.landscape()) {
            MP4FocusScreen.TexturePoint landscape = toLandscape(point);
            return !inside(landscape, 88, 75, 248, 43) ? -1 : Math.max(0, Math.min(2, (landscape.y() - 75) / 14));
         } else {
            return !inside(point, 31, 116, 186, 168) ? -1 : Math.max(0, Math.min(5, (point.y() - 116) / 28));
         }
      }

      static boolean rotationHintConfirm(MP4FocusScreen.TexturePoint point) {
         if (MP4FocusState.landscape()) {
            MP4FocusScreen.TexturePoint landscape = toLandscape(point);
            return inside(landscape, 174, 139, 100, 26);
         } else {
            return inside(point, 78, 232, 100, 26);
         }
      }

      private static MP4FocusScreen.MP4TextureHit portraitHit(MP4FocusScreen.TexturePoint p) {
         if (MP4FocusState.qualityMenuOpen()) {
            for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
               if (inside(p, 160, 68 + i * 13, 66, 11)) {
                  return qualityHit(i);
               }
            }
         }

         if (MP4FocusState.subtitleMenuOpen()) {
            if (inside(p, 146, 354, 44, 14)) {
               return MP4FocusScreen.MP4TextureHit.SUBTITLE_OFF;
            }

            if (inside(p, 146, 375, 44, 14)) {
               return MP4FocusScreen.MP4TextureHit.SUBTITLE_PRIMARY;
            }

            if (inside(p, 146, 396, 44, 14)) {
               return MP4FocusScreen.MP4TextureHit.SUBTITLE_SECONDARY;
            }

            if (inside(p, 146, 417, 72, 14)) {
               return MP4FocusScreen.MP4TextureHit.SUBTITLE_AI;
            }
         }

         if (inside(p, 193, 21, 42, 22)) {
            return MP4FocusScreen.MP4TextureHit.QUALITY;
         } else if (inside(p, 34, 333, 42, 42)) {
            return MP4FocusScreen.MP4TextureHit.PREVIOUS;
         } else if (inside(p, 101, 325, 54, 54)) {
            return MP4FocusScreen.MP4TextureHit.PLAY;
         } else if (inside(p, 180, 333, 42, 42)) {
            return MP4FocusScreen.MP4TextureHit.NEXT;
         } else if (inside(p, 28, 292, 200, 16)) {
            return MP4FocusScreen.MP4TextureHit.PROGRESS;
         } else if (inside(p, 56, 386, 172, 24)) {
            return MP4FocusScreen.MP4TextureHit.VOLUME;
         } else if (inside(p, 20, 411, 42, 14)) {
            return MP4FocusScreen.MP4TextureHit.BILI_LOGIN;
         } else if (inside(p, 68, 411, 58, 14)) {
            return MP4FocusScreen.MP4TextureHit.SHUFFLE;
         } else if (inside(p, 136, 411, 58, 14)) {
            return MP4FocusScreen.MP4TextureHit.PLAYLIST;
         } else if (inside(p, 203, 411, 32, 14)) {
            return MP4FocusScreen.MP4TextureHit.LYRICS;
         } else {
            return MP4FocusState.playlistOpen() && inside(p, 18, 62, 220, 242) ? MP4FocusScreen.MP4TextureHit.PLAYLIST_AREA : MP4FocusScreen.MP4TextureHit.NONE;
         }
      }

      private static MP4FocusScreen.MP4TextureHit landscapeHit(MP4FocusScreen.TexturePoint p) {
         if (MP4FocusState.controlsVisible()) {
            if (MP4FocusState.qualityMenuOpen()) {
               for (int i = 0; i < MP4FocusState.QUALITIES.length; i++) {
                  if (inside(p, 330, 65 + i * 13, 70, 11)) {
                     return qualityHit(i);
                  }
               }
            }

            if (MP4FocusState.subtitleMenuOpen()) {
               if (inside(p, 244, 72, 52, 14)) {
                  return MP4FocusScreen.MP4TextureHit.SUBTITLE_OFF;
               }

               if (inside(p, 244, 94, 52, 14)) {
                  return MP4FocusScreen.MP4TextureHit.SUBTITLE_PRIMARY;
               }

               if (inside(p, 244, 116, 52, 14)) {
                  return MP4FocusScreen.MP4TextureHit.SUBTITLE_SECONDARY;
               }

               if (inside(p, 244, 138, 82, 14)) {
                  return MP4FocusScreen.MP4TextureHit.SUBTITLE_AI;
               }
            }

            if (inside(p, 330, 14, 52, 28)) {
               return MP4FocusScreen.MP4TextureHit.QUALITY;
            }

            if (inside(p, 278, 16, 44, 20)) {
               return MP4FocusScreen.MP4TextureHit.PLAYLIST;
            }

            if (inside(p, 28, 207, 48, 24)) {
               return MP4FocusScreen.MP4TextureHit.LYRICS;
            }

            if (inside(p, 146, 204, 30, 24)) {
               return MP4FocusScreen.MP4TextureHit.PREVIOUS;
            }

            if (inside(p, 200, 198, 46, 34)) {
               return MP4FocusScreen.MP4TextureHit.PLAY;
            }

            if (inside(p, 270, 204, 30, 24)) {
               return MP4FocusScreen.MP4TextureHit.NEXT;
            }

            if (inside(p, 32, 176, 384, 18)) {
               return MP4FocusScreen.MP4TextureHit.PROGRESS;
            }

            if (inside(p, 340, 202, 86, 22)) {
               return MP4FocusScreen.MP4TextureHit.VOLUME;
            }

            if (inside(p, 88, 213, 48, 14)) {
               return MP4FocusScreen.MP4TextureHit.REPEAT;
            }

            if (MP4FocusState.playlistOpen() && inside(p, 72, 44, 304, 76)) {
               return MP4FocusScreen.MP4TextureHit.PLAYLIST_AREA;
            }
         }

         return inside(p, 10, 10, 428, 236) ? MP4FocusScreen.MP4TextureHit.VIDEO_AREA : MP4FocusScreen.MP4TextureHit.NONE;
      }

      private static MP4FocusScreen.MP4TextureHit qualityHit(int index) {
         return switch (Math.max(0, Math.min(7, index))) {
            case 0 -> MP4FocusScreen.MP4TextureHit.QUALITY_0;
            case 1 -> MP4FocusScreen.MP4TextureHit.QUALITY_1;
            case 2 -> MP4FocusScreen.MP4TextureHit.QUALITY_2;
            case 3 -> MP4FocusScreen.MP4TextureHit.QUALITY_3;
            case 4 -> MP4FocusScreen.MP4TextureHit.QUALITY_4;
            case 5 -> MP4FocusScreen.MP4TextureHit.QUALITY_5;
            case 6 -> MP4FocusScreen.MP4TextureHit.QUALITY_6;
            default -> MP4FocusScreen.MP4TextureHit.QUALITY_7;
         };
      }

      private static MP4FocusScreen.TexturePoint toLandscape(MP4FocusScreen.TexturePoint point) {
         return new MP4FocusScreen.TexturePoint(447 - point.y(), point.x());
      }

      private static boolean inside(MP4FocusScreen.TexturePoint p, int x, int y, int w, int h) {
         return p.x() >= x && p.y() >= y && p.x() < x + w && p.y() < y + h;
      }
   }

   private record ProjectedSurface(int left, int top, int width, int height) {
      MP4FocusScreen.ProjectedSurface inflate(int amount) {
         return new MP4FocusScreen.ProjectedSurface(this.left - amount, this.top - amount, this.width + amount * 2, this.height + amount * 2);
      }

      boolean contains(int x, int y) {
         return x >= this.left && y >= this.top && x < this.left + this.width && y < this.top + this.height;
      }
   }

   private record Quad(
      MP4FocusScreen.ScreenPoint topLeft, MP4FocusScreen.ScreenPoint topRight, MP4FocusScreen.ScreenPoint bottomRight, MP4FocusScreen.ScreenPoint bottomLeft
   ) {
      MP4FocusScreen.ScreenPoint pivotPoint(float pivotU, float pivotV) {
         return this.sample(Math.max(0.0F, Math.min(1.0F, pivotU)), Math.max(0.0F, Math.min(1.0F, pivotV)));
      }

      MP4FocusScreen.TexturePoint toTexturePoint(float screenX, float screenY) {
         if (!this.bounds().inflate(4).contains(Math.round(screenX), Math.round(screenY))) {
            return null;
         } else {
            float u = 0.5F;
            float v = 0.5F;

            for (int i = 0; i < 8; i++) {
               MP4FocusScreen.ScreenPoint p = this.sample(u, v);
               float dx = p.x() - screenX;
               float dy = p.y() - screenY;
               if (Math.abs(dx) + Math.abs(dy) < 0.01F) {
                  break;
               }

               MP4FocusScreen.ScreenPoint du = this.derivativeU(v);
               MP4FocusScreen.ScreenPoint dv = this.derivativeV(u);
               float det = du.x() * dv.y() - du.y() * dv.x();
               if (Math.abs(det) < 1.0E-4F) {
                  break;
               }

               float deltaU = (dx * dv.y() - dy * dv.x()) / det;
               float deltaV = (du.x() * dy - du.y() * dx) / det;
               u = clamp(u - deltaU);
               v = clamp(v - deltaV);
            }

            MP4FocusScreen.ScreenPoint resolved = this.sample(u, v);
            float error = Math.abs(resolved.x() - screenX) + Math.abs(resolved.y() - screenY);
            if (error > 18.0F) {
               return null;
            } else {
               int textureX = Math.round(u * 255.0F);
               int textureY = Math.round(v * 447.0F);
               return new MP4FocusScreen.TexturePoint(textureX, textureY);
            }
         }
      }

      MP4FocusScreen.ProjectedSurface bounds() {
         float minX = Math.min(Math.min(this.topLeft.x(), this.topRight.x()), Math.min(this.bottomRight.x(), this.bottomLeft.x()));
         float minY = Math.min(Math.min(this.topLeft.y(), this.topRight.y()), Math.min(this.bottomRight.y(), this.bottomLeft.y()));
         float maxX = Math.max(Math.max(this.topLeft.x(), this.topRight.x()), Math.max(this.bottomRight.x(), this.bottomLeft.x()));
         float maxY = Math.max(Math.max(this.topLeft.y(), this.topRight.y()), Math.max(this.bottomRight.y(), this.bottomLeft.y()));
         return new MP4FocusScreen.ProjectedSurface(Math.round(minX), Math.round(minY), Math.round(maxX - minX), Math.round(maxY - minY));
      }

      private MP4FocusScreen.ScreenPoint sample(float u, float v) {
         float topX = lerp(this.topLeft.x(), this.topRight.x(), u);
         float topY = lerp(this.topLeft.y(), this.topRight.y(), u);
         float bottomX = lerp(this.bottomLeft.x(), this.bottomRight.x(), u);
         float bottomY = lerp(this.bottomLeft.y(), this.bottomRight.y(), u);
         return new MP4FocusScreen.ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
      }

      private MP4FocusScreen.ScreenPoint derivativeU(float v) {
         float topX = this.topRight.x() - this.topLeft.x();
         float topY = this.topRight.y() - this.topLeft.y();
         float bottomX = this.bottomRight.x() - this.bottomLeft.x();
         float bottomY = this.bottomRight.y() - this.bottomLeft.y();
         return new MP4FocusScreen.ScreenPoint(lerp(topX, bottomX, v), lerp(topY, bottomY, v));
      }

      private MP4FocusScreen.ScreenPoint derivativeV(float u) {
         float leftX = this.bottomLeft.x() - this.topLeft.x();
         float leftY = this.bottomLeft.y() - this.topLeft.y();
         float rightX = this.bottomRight.x() - this.topRight.x();
         float rightY = this.bottomRight.y() - this.topRight.y();
         return new MP4FocusScreen.ScreenPoint(lerp(leftX, rightX, u), lerp(leftY, rightY, u));
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

   private static enum SliderDrag {
      NONE,
      PROGRESS,
      VOLUME;
   }

   private record TexturePoint(int x, int y) {
   }
}
