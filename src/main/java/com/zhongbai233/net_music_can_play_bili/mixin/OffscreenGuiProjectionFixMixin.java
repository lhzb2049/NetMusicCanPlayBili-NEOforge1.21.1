package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 1.21.1 移植版「Pad / MP4 手持设备的屏幕一片纯色、UI 不显示」。
 *
 * <p><b>根因</b>：离屏 GUI 渲染器把 GUI 正交投影设成了
 * {@code setOrtho(0, W, H, 0, 1000, 3000)}（这两个值是从 26.x 版本照搬的），
 * 但紧接着又把模型视图置为 {@code identity()}，于是 GUI 几何体落在 z≈0..400。
 *
 * <p>JOML 的 {@code setOrtho} 采用与 glOrtho 相反的 z 符号约定，其可见 z 区间为
 * {@code z ∈ [-far, -near]}：
 * <ul>
 *   <li>{@code (near=1000, far=3000)} → 只允许 {@code z ∈ [-3000, -1000]}，<b>z≈0 的 GUI 全部被近平面裁掉</b>，
 *       RenderTarget 只剩清屏色 —— 设备模型照常渲染（那是硬编码纯色四边形），屏幕面因此显示为纯色。</li>
 *   <li>作为对照，1.21.1 原版 GUI 用的是 {@code (1000, 21000)}（允许 {@code [-21000, -1000]}），
 *       并且原版 {@code GameRenderer} 会先把模型视图沿 z 平移 {@code -11000}，使 GUI 几何体落进该区间。
 *       移植版照搬了 26.x 的投影参数，却没有 1.21.1 需要的这段 z 平移。</li>
 * </ul>
 *
 * <p><b>修法</b>：不改原有字节码，只在那次 {@code identity()} 之后补一次
 * {@code translation(0, 0, -2000)}（该投影区间 [-3000,-1000] 的中间位置），
 * 使局部 z 0..400 映射到 [-2000,-1600]，并保持「局部 z 越大越靠前」的正确图层顺序。
 * 该区间还能容纳局部 z 到 1000 的内容，留有富余。
 *
 * <p>这里用 {@code translation}（把矩阵设为纯平移）而非 {@code translate}（在矩阵上累乘平移），
 * 与 1.21.1 原版 {@code GameRenderer} 的做法一致：原版同样是在刚 {@code pushMatrix()} 出的
 * 单位矩阵上调用 {@code Matrix4fStack.translation(0, 0, -11000)}，随后 {@code applyModelViewMatrix()}。
 *
 * <p>注入点声明 {@code require = 0}：万一签名对不上只会不生效，不会导致启动崩溃。
 */
@Mixin(targets = {
   "com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadOffscreenGuiRenderer",
   "com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4OffscreenGuiRenderer"
})
public abstract class OffscreenGuiProjectionFixMixin {
   private static final Logger LOGGER = LoggerFactory.getLogger("ncpb-offscreen-fix");

   /** 把 GUI 几何体平移进该正交投影真正允许的 z 区间 [-3000, -1000] 的中间。 */
   private static final float GUI_Z_TRANSLATE = -2000.0F;

   private boolean ncpb$loggedDepthFix;
   private boolean ncpb$loggedOrthoFix;

   @Shadow(remap = false)
   @Final
   private static int WIDTH;

   @Shadow(remap = false)
   @Final
   private static int HEIGHT;

   /**
    * 修正投影的坐标口径：原代码把 {@code TARGET_WIDTH/TARGET_HEIGHT}（物理像素，如 896x512）
    * 填进了 setOrtho，但 1.21.1 的 {@code GuiGraphics} 是按<b>逻辑坐标</b>绘制的
    * （原版 GUI 投影用的就是 {@code width/guiScale} 这类逻辑值）。
    * 口径不一致会让内容只占目标的一小块（实测非透明像素包围盒为 854x480，即窗口 GUI 空间，
    * 而目标宽 896 —— 两者对不上），贴到设备屏幕面后就表现为大片空白/黑色。
    * 这里改用目标自身的逻辑尺寸重建投影。
    */
   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexSorting;)V",
         shift = At.Shift.AFTER
      ),
      require = 0
   )
   private void ncpb$matchGuiCoordinateSpace(CallbackInfo ci) {
      if (WIDTH <= 0 || HEIGHT <= 0) {
         return;
      }

      RenderSystem.setProjectionMatrix(
         new Matrix4f().setOrtho(0.0F, WIDTH, HEIGHT, 0.0F, 1000.0F, 3000.0F),
         VertexSorting.ORTHOGRAPHIC_Z
      );
      if (!this.ncpb$loggedOrthoFix) {
         this.ncpb$loggedOrthoFix = true;
         LOGGER.info("[NCPB 离屏修复] 投影已改用逻辑尺寸 {}x{}（原为物理像素口径，导致内容只占目标一小块）", WIDTH, HEIGHT);
      }
   }

   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lorg/joml/Matrix4fStack;identity()Lorg/joml/Matrix4f;",
         shift = At.Shift.AFTER
      ),
      require = 0
   )
   private void ncpb$applyGuiDepthRange(CallbackInfo ci) {
      RenderSystem.getModelViewStack().translation(0.0F, 0.0F, GUI_Z_TRANSLATE);
      if (!this.ncpb$loggedDepthFix) {
         this.ncpb$loggedDepthFix = true;
         LOGGER.info("[NCPB 离屏修复] 已将离屏 GUI 模型视图沿 z 平移 {}，使内容落入正交投影区间 [-3000,-1000]（原 z≈0 会被近平面裁掉）", GUI_Z_TRANSLATE);
      }
   }
}
