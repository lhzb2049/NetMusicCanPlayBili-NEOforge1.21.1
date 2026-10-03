package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementPosition;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 控制台音频元素（音箱元素）在物理化结构上恒为静音：它的世界位置从头到尾没做过 plot → 世界投影。
 *
 * <h2>根因</h2>
 * <pre>
 *   ControlConsoleRenderer.tickConsumers（每 client tick）→ reconcileConsumer
 *     → registerAudioForConsole(consolePos, source, elements, …)
 *         → ControlConsoleElementPosition.worldPosition(consolePos.getX(), …, element)
 *              = { consoleX + 0.5 + local.x, consoleY + 1.55 + local.y, consoleZ + 0.5 + local.z }
 *         → registerConsoleRelay(key, source, worldPos, …) → relay.setSpeakerPos(worldPos)
 * </pre>
 * 物理化后 {@code consolePos} 是 **plot 坐标**（实测量级 2×10⁷），而 {@code worldPosition} 只做加法、
 * 不查 sub-level ⇒ 传进去的是 plot 坐标系里的点。听者（{@code camera.getPosition()}）在世界坐标，
 * 于是 {@code SpeakerAudioRelay.rangeAt} 算出的距离恒为约 2000 万格 ⇒ 几何增益 0 ⇒ 无论怎么调都听不到。
 *
 * <p>与"主输出只在注册时算一次"（{@link ClientAudioOutputRegistryLivePositionMixin}）不同：这里每 tick
 * 都会重算并重设位置，**缺的只是坐标系换算**。所以本 Mixin 只把这一次调用的返回值换成世界坐标。
 *
 * <p>为什么用 {@code @Redirect} 而不是改 {@code worldPosition}：同一个方法还被渲染路径调用
 * （元素本体绘制、range 调试快照），那些地方要的是 plot 坐标系里的相对点，改公共方法会把画面一起改坏。
 * {@code registerAudioForConsole} 里只有这一处调用，重定向范围就是音频这一条路。
 *
 * <p>地面场景：{@code projectToWorld} 返回 {@code null}（不在任何 sub-level 里）⇒ 原样返回原值，
 * 行为与改动前逐字节一致。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer")
public class ControlConsoleAudioElementPositionMixin {

    @Redirect(method = "registerAudioForConsole", at = @At(value = "INVOKE", target = "Lcom/zhongbai233/net_music_can_play_bili/editor/host/controlconsole/document/ControlConsoleElementPosition;worldPosition(IIILcom/zhongbai233/net_music_can_play_bili/editor/host/controlconsole/document/ControlConsoleElement;)Lorg/joml/Vector3d;"))
    private static Vector3d ncpb$projectConsoleAudioElement(int consoleX, int consoleY, int consoleZ,
                                                             ControlConsoleElement element) {
        Vector3d original = ControlConsoleElementPosition.worldPosition(consoleX, consoleY, consoleZ, element);
        if (original == null) {
            return null;
        }
        // 同 ClientAudioOutputRegistryLivePositionMixin：null level 会让真实 Sable 抛错，
        // 而兼容层把调用异常当成"Sable 内部出错"并 degradeAll()（整局失效）。这里挡在调用之前。
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return original;
        }
        float[] live = SableGeometryCompat.projectToWorld(minecraft.level, original.x, original.y, original.z);
        return live == null ? original : new Vector3d(live[0], live[1], live[2]);
    }
}
