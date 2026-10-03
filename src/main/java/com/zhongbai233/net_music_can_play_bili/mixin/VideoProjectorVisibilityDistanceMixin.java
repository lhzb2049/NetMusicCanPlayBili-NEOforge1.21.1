package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ProjectorScreenBounds;
import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 物理化结构上「视频投影仪一直停在加载界面」的修复。
 *
 * <h2>根因（与唱片机无声同源）</h2>
 * {@code VideoBillboardQuadSupport.computeProjectorScreenRenderable} 用
 * {@code camera.getPosition()}（**世界坐标**）和投影仪的 **plot 坐标** 去判"投影幕是否在渲染距离内"：
 * <pre>
 *   computeProjectorScreenRenderable            // :403
 *     └ isProjectorWithinRenderDistance(...)    // :416 —— bounds 由 plot 坐标算出
 *         ProjectorScreenBounds.distanceToSqr(bounds, cameraPos) &lt;= 64²
 * </pre>
 * 结构上一个在 plot（{@code 20481032}）、一个在世界（{@code -26,-60,26}），距离约 2000 万格 →
 * 永远判"看不见" → {@code VideoPlaybackPresentation.markVisibility(false, …)} 永不调用
 * {@code grantDecodeAdmission()} → {@code VideoCandidateDecodeRunner} 根本不启动
 * （日志：只有「视频会话创建」，没有「VideoNativeDecoder 已打开」，时间线 {@code video=n/a}）
 * → 屏幕上一直只有 {@code VideoPlaceholderFrames.Kind.LOADING} 占位图。
 *
 * <h2>改法（只换"距离的口径"，不动控制流、不复制公式）</h2>
 * 把那次距离比较的第一个操作数换成 Sable 的 sub-level 感知距离（投影仪中心 ↔ 摄像机，
 * 两边各自 plot→世界）。阈值仍是原代码里的 {@code MAX_RENDER_DISTANCE_SQR}，公式仍由原方法算，
 * 所以**地面场景调用原实现，行为逐字节不变**（此时 {@code getContaining} 返回 null）。
 *
 * <p>为什么要在 HEAD/RETURN 压栈投影仪坐标：{@code @Redirect} 的处理器只能拿到被调用点的
 * 接收者与参数（这里是 {@code AABB} 与 {@code Vec3}），拿不到外层的 projector；而"这个 chunk
 * 属于哪个 sub-level"必须从投影仪自己的坐标问出来（投影仪在结构上会被渲染出来，这一点本身
 * 就证明 {@code getContaining(level, 其 plot 坐标)} 非空，而屏幕中心的坐标没有这个保证）。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardQuadSupport")
public class VideoProjectorVisibilityDistanceMixin {

    @Inject(method = "isProjectorWithinRenderDistance", at = @At("HEAD"))
    private static void ncpb$pushProjectorPos(Vec3 cameraPos, VideoProjectorBlockEntity projector,
                                              double centerX, double centerY, double centerZ, double aspect,
                                              CallbackInfoReturnable<Boolean> callback) {
        if (projector != null) {
            BlockPos pos = projector.getBlockPos();
            SableGeometryCompat.pushProjectorPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        }
    }

    @Inject(method = "isProjectorWithinRenderDistance", at = @At("RETURN"))
    private static void ncpb$popProjectorPos(Vec3 cameraPos, VideoProjectorBlockEntity projector,
                                             double centerX, double centerY, double centerZ, double aspect,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (projector != null) {
            SableGeometryCompat.popProjectorPos();
        }
    }

    @Redirect(method = "isProjectorWithinRenderDistance", at = @At(value = "INVOKE", target = "Lcom/zhongbai233/net_music_can_play_bili/client/renderer/ProjectorScreenBounds;distanceToSqr(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/Vec3;)D"))
    private static double ncpb$subLevelAwareScreenDistance(AABB bounds, Vec3 cameraPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && cameraPos != null && SableGeometryCompat.isProjectorInSubLevel(minecraft.level)) {
            double distanceSquared = SableGeometryCompat.distanceSquaredFromProjector(
                    minecraft.level, cameraPos.x, cameraPos.y, cameraPos.z);
            if (!Double.isNaN(distanceSquared)) {
                return distanceSquared;
            }
        }
        // 地面 / 兼容层不可用：原实现，行为与改动前一致
        return ProjectorScreenBounds.distanceToSqr(bounds, cameraPos);
    }

    /**
     * "屏幕是否在视野内"这一判定的**方向口径**修复。
     *
     * <p>原实现是 {@code isScreenInView(camera, 采样点…)}：拿摄像机朝向与"摄像机 → 采样点"方向做点积。
     * 采样点在结构上是 plot 坐标，于是它相对摄像机永远指向 plot 网格里那条**固定的对角线**
     * （与屏幕在画面里的真实方向无关）。后果：结构投影仪会在你**仍然看着屏幕**时被判"看不见"
     * → 视频停画、并开始累计离屏时间 → 转回来触发一次离屏恢复重定位（约 1 秒的加载占位图）。
     *
     * <p>这里把采样点先投影成**世界坐标**再判定：结构在世界里就画在眼前几格，方向才与画面一致。
     * 地面场景 {@code projectToWorld} 返回 null ⇒ 用原采样点、按与原方法**逐表达式相同**的算式计算，
     * 结果不变。
     *
     * <p>为什么要照抄这 6 行算式而不是 {@code @Shadow} 原方法：{@code @Shadow} 绑错只会在**运行期**
     * 才暴露（本项目为这类错误付过一次崩溃 + 一次无效验证的代价），而这段算式语义一眼可查；
     * 代价是"上游改了这个公式"时需要同步，所以构建期用 {@code at_targets} 钉住这个调用点
     * （调用被改名/删除时构建直接失败）。
     */
    @Redirect(method = "computeProjectorScreenRenderable", at = @At(value = "INVOKE", target = "Lcom/zhongbai233/net_music_can_play_bili/client/renderer/video/VideoBillboardQuadSupport;isScreenInView(Lnet/minecraft/client/Camera;DDDD)Z"))
    private static boolean ncpb$screenInViewInWorldSpace(Camera camera, double centerX, double centerY, double centerZ,
                                                         double dotThreshold) {
        if (camera == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float[] world = SableGeometryCompat.projectToWorld(
                minecraft == null ? null : minecraft.level, centerX, centerY, centerZ);
        double targetX = world != null ? world[0] : centerX;
        double targetY = world != null ? world[1] : centerY;
        double targetZ = world != null ? world[2] : centerZ;

        Vec3 cameraPos = camera.getPosition();
        double dx = targetX - cameraPos.x;
        double dy = targetY - cameraPos.y;
        double dz = targetZ - cameraPos.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0E-4D) {
            return true;
        }
        Vector3fc forward = camera.getLookVector();
        return (dx / length * forward.x() + dy / length * forward.y() + dz / length * forward.z()) > dotThreshold;
    }
}
