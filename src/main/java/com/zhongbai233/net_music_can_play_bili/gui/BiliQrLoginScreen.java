package com.zhongbai233.net_music_can_play_bili.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLoginManager;
import java.io.ByteArrayInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public class BiliQrLoginScreen extends Screen {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int QR_SIZE = 140;
   private static final int BOX_WIDTH = 200;
   private static final int BOX_HEIGHT = 220;
   private final BiliLoginManager loginManager;
   private DynamicTexture qrTexture;
   private ResourceLocation qrTextureId;
   private int qrTextureWidth;
   private int qrTextureHeight;
   private volatile String statusText = "正在生成二维码...";
   private int pollTick;
   private int closeTick = -1;
   private volatile boolean done;
   private volatile int loadGeneration;
   private boolean removed;

   public BiliQrLoginScreen() {
      super(Component.literal("B站登录"));
      this.loginManager = new BiliLoginManager();
   }

   protected void init() {
      this.removed = false;
      int generation = ++this.loadGeneration;
      this.loginManager.generate().thenAccept(state -> {
         if (this.isCurrent(generation)) {
            if (state == BiliLoginManager.State.PENDING) {
               this.statusText = "请用 B站APP 扫描二维码";
               this.loadQrImage(this.loginManager.getQrUrl(), generation);
            } else {
               this.statusText = "二维码生成失败，请重试";
            }
         }
      });
   }

   private void loadQrImage(String qrContentUrl, int generation) {
      this.loginManager.loadQrImage(qrContentUrl).thenApply(bytes -> {
         if (bytes == null) {
            return null;
         } else {
            try {
               NativeImage var3;
               try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                  NativeImage image = NativeImage.read(in);
                  LOGGER.debug("二维码图片加载成功: {}x{}", image.getWidth(), image.getHeight());
                  var3 = image;
               }

               return var3;
            } catch (Exception var6) {
               LOGGER.error("加载二维码图片失败", var6);
               return null;
            }
         }
      }).thenAcceptAsync(nativeImage -> {
         if (nativeImage != null) {
            if (!this.isCurrent(generation)) {
               nativeImage.close();
            } else {
               DynamicTexture texture = null;

               try {
                  texture = new DynamicTexture(nativeImage);
                  this.qrTextureWidth = nativeImage.getWidth();
                  this.qrTextureHeight = nativeImage.getHeight();
                  ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "bili_qrcode");
                  this.cleanupTexture();
                  this.qrTexture = texture;
                  this.qrTextureId = textureId;
                  this.minecraft.getTextureManager().register(textureId, texture);
                  texture.upload();
               } catch (LinkageError | RuntimeException var5) {
                  if (this.qrTexture == texture) {
                     this.cleanupTexture();
                  } else if (texture != null) {
                     texture.close();
                  } else {
                     nativeImage.close();
                  }

                  LOGGER.error("创建二维码纹理失败", var5);
               }
            }
         }
      }, Minecraft.getInstance());
   }

   private boolean isCurrent(int generation) {
      return !this.removed && generation == this.loadGeneration && this.minecraft != null && this.minecraft.screen == this;
   }

   public void tick() {
      super.tick();
      if (this.done) {
         if (this.closeTick >= 0) {
            this.closeTick--;
            if (this.closeTick <= 0) {
               this.onClose();
            }
         }
      } else {
         this.pollTick++;
         if (this.pollTick % 40 == 0) {
            int generation = this.loadGeneration;
            this.loginManager.poll().thenAccept(state -> {
               if (this.isCurrent(generation)) {
                  switch (state) {
                     case PENDING:
                        this.statusText = "请用 B站APP 扫描二维码";
                        break;
                     case SCANNED:
                        this.statusText = "已扫描，请在手机上确认登录";
                        break;
                     case SUCCESS:
                        this.statusText = "登录成功！";
                        this.done = true;
                        this.closeTick = 40;
                        break;
                     case EXPIRED:
                        this.statusText = "二维码已过期，请关闭重试";
                        this.done = true;
                        break;
                     case FAILED:
                        this.statusText = "登录失败，请重试";
                        this.done = true;
                  }
               }
            });
         }
      }
   }

   public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      graphics.fillGradient(0, 0, this.width, this.height, -1072689136, -804253680);
   }

   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics, mouseX, mouseY, partialTick);
      int boxX = (this.width - 200) / 2;
      int boxY = (this.height - 220) / 2;
      graphics.fillGradient(boxX, boxY, boxX + 200, boxY + 220, -266198494, -265080013);
      graphics.fillGradient(boxX + 1, boxY + 1, boxX + 200 - 1, boxY + 30, -13421773, -14013910);
      graphics.drawCenteredString(this.font, "B站账号登录", boxX + 100, boxY + 10, -1);
      int qrX = boxX + 30;
      int qrY = boxY + 32;
      if (this.qrTextureId != null) {
         graphics.fillGradient(qrX - 2, qrY - 2, qrX + 140 + 2, qrY + 140 + 2, -1, -1);
         graphics.blit(this.qrTextureId, qrX, qrY, 0.0F, 0.0F, 140, 140, Math.max(1, this.qrTextureWidth), Math.max(1, this.qrTextureHeight));
      } else {
         graphics.fillGradient(qrX, qrY, qrX + 140, qrY + 140, -12303292, -12303292);
      }

      int statusColor = this.done ? -11141291 : -5592406;
      graphics.drawCenteredString(this.font, this.statusText, boxX + 100, boxY + 220 - 16, statusColor);
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      if (this.done) {
         this.onClose();
         return true;
      } else {
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      this.cleanupTexture();
      if (this.minecraft != null) {
         this.minecraft.setScreen(null);
      }
   }

   public void removed() {
      this.removed = true;
      this.loadGeneration++;
      this.loginManager.close();
      this.cleanupTexture();
   }

   private void cleanupTexture() {
      if (this.qrTexture != null) {
         if (this.minecraft != null && this.qrTextureId != null) {
            this.minecraft.getTextureManager().release(this.qrTextureId);
         } else {
            this.qrTexture.close();
         }

         this.qrTexture = null;
      }

      this.qrTextureId = null;
   }
}
