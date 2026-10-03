package com.zhongbai233.net_music_can_play_bili.mixin;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Pad / MP4 手持设备「屏幕面纯黑」的<b>真正根因</b>。
 *
 * <h2>根因</h2>
 * 移植版的离屏渲染顺序是「先画 GUI，再绑定离屏目标」：
 * <pre>
 * GuiGraphics graphics = new GuiGraphics(minecraft, this.guiBuffer);
 * this.drawGui(graphics, view);        // ← (1) 先「画」
 * RenderSystem.backupProjectionMatrix();
 * ... identity() ...
 * this.target.bindWrite(true);         // ← (2) 之后才绑定离屏目标
 * RenderSystem.clear(16640, ON_OSX);   // ← (3) 再清屏
 * RenderSystem.setProjectionMatrix(正交投影);
 * graphics.flush();
 * </pre>
 * 但 1.21.1 的 {@code GuiGraphics} 是<b>立即模式</b>：{@code fill}/{@code drawString}/{@code blit} 等在每次绘制前都会调用
 * {@code flushIfUnmanaged()}（见 {@code GuiGraphics} 第 221/235/253/290/303 行），
 * 而 {@code BufferSource.getBuffer} 在切换 RenderType 时也会立刻 flush 上一个。
 * 因此在第 (1) 步里，GUI 的顶点就已经被「画出去了」—— 那时绑定的是<b>主帧缓冲</b>，
 * 用的是<b>世界的透视投影</b>，随后第 (3) 步又把离屏目标清成全透明。
 *
 * <p>结果：离屏贴图 <b>全零</b>（896x512 逐像素回读确认：RGB 与 alpha 全为 0）。
 * 于是世界空间的 UI 面片采样到 alpha=0 的贴图，被 {@code entityCutout} 的
 * {@code color.a < 0.1} 判定整片丢弃 → 屏幕面只剩设备自己的深色前面板 = 用户看到的「纯黑」。
 *
 * <h2>修法</h2>
 * 把「绑定离屏目标 + 清屏 + 设正交投影 + 模型视图 identity/z 平移」<b>提前到 {@code drawGui} 之前</b>（HEAD），
 * 这样 GUI 的那些急切 flush 就会落进正确的目标、用正确的投影；同时
 * <ul>
 *   <li>用 {@code @ModifyArg} 把原代码第 (3) 步那次 {@code clear} 的掩码改成 0（{@code glClear(0)} 什么都不清），
 *       否则它会把 {@code drawGui} 急切画进离屏目标的内容再擦掉；</li>
 *   <li>原代码的 {@code backupProjectionMatrix()/restoreProjectionMatrix()} 会把我们设的正交投影当成「世界投影」备份走，
 *       所以在其 {@code restoreProjectionMatrix()} 之后显式恢复世界投影，并刷新备份槽；</li>
 *   <li>自己压的模型视图栈在 {@code popMatrix()} 之后弹出，异常路径同样覆盖。</li>
 * </ul>
 *
 * <p>全部 handler 都不带目标方法参数（{@code PadGuiViewState}/{@code MP4GuiViewState} 是包私有的 record，
 * 本 Mixin 在另一个包里引用不到；而不带参数是被 {@code HandheldScreenFlushFixMixin} 实测证明可行的写法），
 * 因此不存在描述符不匹配导致启动崩溃的风险。
 */
@Mixin(targets = {
   "com.zhongbai233.net_music_can_play_bili.client.renderer.item.PadOffscreenGuiRenderer",
   "com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4OffscreenGuiRenderer"
})
public abstract class OffscreenGuiPassFixMixin {
   private static final Logger LOGGER = LoggerFactory.getLogger("ncpb-offscreen-pass-fix");

   /** 与既有 OffscreenGuiProjectionFixMixin 一致的 z 平移：把 GUI 几何体送进正交投影真正允许的 [-3000,-1000] 区间。 */
   private static final float GUI_Z_TRANSLATE = -2000.0F;

   @Shadow(remap = false)
   @Final
   private static int WIDTH;

   @Shadow(remap = false)
   @Final
   private static int HEIGHT;

   @Shadow(remap = false)
   @Final
   private static int TARGET_WIDTH;

   @Shadow(remap = false)
   @Final
   private static int TARGET_HEIGHT;

   @Shadow(remap = false)
   private TextureTarget target;

   @Unique
   private Matrix4f ncpb$savedWorldProjection;

   @Unique
   private VertexSorting ncpb$savedWorldSorting;

   @Unique
   private boolean ncpb$passActive;

   @Unique
   private boolean ncpb$loggedPassFix;

   @Inject(method = "render", at = @At("HEAD"), require = 0)
   private void ncpb$setupOffscreenTargetBeforeDrawing(CallbackInfo ci) {
      TextureTarget t = this.target;
      if (t == null || WIDTH <= 0 || HEIGHT <= 0) {
         return;
      }

      this.ncpb$savedWorldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
      this.ncpb$savedWorldSorting = RenderSystem.getVertexSorting();
      this.ncpb$passActive = true;

      RenderSystem.getModelViewStack().pushMatrix();
      RenderSystem.getModelViewStack().identity();
      RenderSystem.getModelViewStack().translation(0.0F, 0.0F, GUI_Z_TRANSLATE);
      RenderSystem.applyModelViewMatrix();

      RenderSystem.enableBlend();
      t.bindWrite(true);
      RenderSystem.enableScissor(0, 0, TARGET_WIDTH, TARGET_HEIGHT);
      RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
      RenderSystem.clear(16640, Minecraft.ON_OSX);
      RenderSystem.setProjectionMatrix(
         new Matrix4f().setOrtho(0.0F, WIDTH, HEIGHT, 0.0F, 1000.0F, 3000.0F),
         VertexSorting.ORTHOGRAPHIC_Z
      );

      if (!this.ncpb$loggedPassFix) {
         this.ncpb$loggedPassFix = true;
         LOGGER.info(
            "[NCPB 离屏顺序修复] 已在 drawGui 之前绑定离屏目标并设好正交投影 {}x{}（原来 GUI 的急切 flush 会画到主帧缓冲上，随后又被清屏擦掉）",
            WIDTH, HEIGHT
         );
      }
   }

   /**
    * 原代码在 {@code drawGui} 之后还会清一次屏；那一次会把我们刚画进离屏目标的内容擦掉，
    * 所以把它的掩码改成 0（{@code glClear(0)} 什么都不清）。清屏已由 HEAD 完成。
    */
   @ModifyArg(
      method = "render",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"),
      index = 0,
      require = 0
   )
   private static int ncpb$skipLateClear(int mask) {
      return 0;
   }

   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/blaze3d/systems/RenderSystem;restoreProjectionMatrix()V",
         shift = At.Shift.AFTER
      ),
      require = 0
   )
   private void ncpb$restoreWorldProjection(CallbackInfo ci) {
      if (!this.ncpb$passActive) {
         return;
      }

      if (this.ncpb$savedWorldProjection != null) {
         RenderSystem.setProjectionMatrix(this.ncpb$savedWorldProjection, this.ncpb$savedWorldSorting);
         // 刷新备份槽，免得之后任何 restore 又把正交投影装回去。
         RenderSystem.backupProjectionMatrix();
      }
   }

   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;",
         shift = At.Shift.AFTER
      ),
      require = 0
   )
   private void ncpb$popPassModelView(CallbackInfo ci) {
      if (!this.ncpb$passActive) {
         return;
      }

      this.ncpb$passActive = false;
      RenderSystem.getModelViewStack().popMatrix();
      RenderSystem.applyModelViewMatrix();
   }
}
