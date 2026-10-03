package com.zhongbai233.net_music_can_play_bili.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 修复「电脑 → B站登录」界面里二维码只显示一部分（右下角被切掉、并放大）的问题。
 *
 * <h2>根因：9 参 {@code blit} 在 26.x 与 1.21.1 的语义完全不同</h2>
 * 上游 26.1.2（0.7.10）写的是：
 * <pre>
 * graphics.blit(qrTextureId, qrX, qrY, qrX + QR_SIZE, qrY + QR_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
 * // 26.x 的 9 参语义 = (贴图, x0, y0, x1, y1, u0, u1, v0, v1) —— 角点 + 归一化 UV
 * </pre>
 * 而 1.21.1 的同名 9 参重载是：
 * <pre>
 * blit(ResourceLocation tex, int x, int y, float uOffset, float vOffset,
 *      int width, int height, int textureWidth, int textureHeight)
 * </pre>
 * 它把 {@code width}/{@code height} <b>同时</b>当作「屏幕上的尺寸」和「要采样的纹素数量」：
 * <pre>
 * u ∈ [uOffset, uOffset + width ] / textureWidth
 * v ∈ [vOffset, vOffset + height] / textureHeight
 * </pre>
 * 移植时按参数个数把 26.x 的调用映到了这个重载上，结果传进去的是
 * {@code width = height = 140}（QR_SIZE）而 {@code textureWidth = textureHeight = 180}
 * （B站返回的二维码 PNG 实测就是 180x180，见日志「二维码图片加载成功: 180x180」）
 * ⇒ 只采样了整张二维码<b>左上角 140/180 = 77.8%</b> 的区域，右下角被切掉、内容还被放大 1.29 倍，
 * 扫描自然会失败。
 *
 * <h2>修法</h2>
 * 把归一化用的 {@code textureWidth}/{@code textureHeight} 改成与采样区同宽（即 {@code width}/{@code height}），
 * 于是 {@code u/v} 覆盖 {@code [0,1]}，整张二维码被完整铺进那个 140x140 的框里
 * —— 也就是 {@code blit(tex, x, y, w, h, 0, 0, texW, texH, texW, texH)} 那个 11 参重载的等价效果。
 * 这样也不依赖二维码图片的实际尺寸（180、200 或别的都能正确铺满）。
 *
 * <p>为什么不直接改类：这是模组自己的类，重编译整类要额外保证 lambda / 合成类（枚举 switch 的
 * {@code $1}）的一致性；用 {@code @ModifyArgs} 只改这一个调用点的两个实参，风险最小。
 * 目标类里只有这一处 9 参 {@code blit} 调用（字节码已核对）。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.gui.BiliQrLoginScreen")
public abstract class BiliQrLoginScreenQrBlitFixMixin {
   @ModifyArgs(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIFFIIII)V"
      ),
      remap = false,
      require = 0
   )
   private void ncpb$mapWholeQrTexture(Args args) {
      Integer screenWidth = args.get(5);
      Integer screenHeight = args.get(6);
      if (screenWidth == null || screenHeight == null || screenWidth <= 0 || screenHeight <= 0) {
         return;
      }

      args.set(7, screenWidth);
      args.set(8, screenHeight);
   }
}
