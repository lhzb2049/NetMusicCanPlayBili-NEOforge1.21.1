package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 视频投影仪"一旦出画面就特别卡"的修复：**让遮挡射线永远不可能跨 2000 万格**。
 *
 * <h2>根因</h2>
 * {@code VideoBillboardQuadSupport.computeProjectorScreenRenderable} 在"投影幕在渲染距离内"之后会逐点做
 * 可见性判定（{@code view_dot_threshold} 默认 0.12 ≈ ±83°，所以这一步基本总会往下走）：
 * <pre>
 *   → VideoScreenOcclusion.isOccluded（level, cameraPos, target, projectorPos）
 *       → BlockGetter.traverseBlocks（cameraPos＝世界坐标, target＝plot 坐标, …）
 * </pre>
 * 这是一条**逐格 DDA**：两点相距约 2000 万格 ⇒ 每次要走约 4×10⁷ 个格子，5 个采样点、
 * 遮挡缓存只有 150ms（{@code ncpb.video.render.occlusion_cache_ms} 默认 150）⇒ 渲染线程几乎全耗在这上面。
 *
 * <p>为什么以前不卡：那次 DDA 在距离判定为假时**根本走不到**（我的可见性修复之前，结构上的投影仪
 * 永远判"太远"）。地面上这条射线只有几格，所以同一段代码在地面一直很便宜 —— 这就是"地面不卡、结构卡"。
 *
 * <h2>改法</h2>
 * 只改这一次调用的**目标点口径**：目标在 sub-level 里就换成它的世界坐标（结构在世界里就在眼前几格，
 * 射线自然短）；只要是 plot 量级却拿不到世界坐标，就**直接不射**（判"没被遮挡"，fail-open）。
 * 地面场景目标本来就是世界坐标 ⇒ 原样调用，行为不变。
 *
 * <p>取舍：结构投影仪的遮挡判定改为对着**世界**里的方块射，因此"被结构自身墙体挡住"不会被识别
 * （视频会透过结构自己的墙画出来）。这是相对"渲染线程卡死"的小代价，而且是本轮之前的行为
 * （那时根本没有遮挡判定）。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoScreenOcclusion")
public class VideoScreenOcclusionSubLevelMixin {

    @Redirect(method = "isOccluded", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;traverseBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Ljava/lang/Object;Ljava/util/function/BiFunction;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private static Object ncpb$subLevelOcclusionRay(Vec3 from, Vec3 to, Object blockGetter,
                                                     BiFunction<Object, BlockPos, Object> visitor,
                                                     Function<Object, Object> finish) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = blockGetter instanceof Level cast ? cast
                : (minecraft == null ? null : minecraft.level);
        float[] target = SableGeometryCompat.occlusionRayTarget(level, to.x, to.y, to.z);
        if (target == null) {
            // 目标本来就是世界坐标（地面）：原样调用，行为与改动前一致
            return BlockGetter.traverseBlocks(from, to, blockGetter, visitor, finish);
        }
        if (target.length == 0) {
            // plot 量级却拿不到世界坐标：绝不射（否则就是 2000 万格的 DDA）
            return Boolean.FALSE;
        }
        return BlockGetter.traverseBlocks(from, new Vec3(target[0], target[1], target[2]), blockGetter, visitor, finish);
    }
}
