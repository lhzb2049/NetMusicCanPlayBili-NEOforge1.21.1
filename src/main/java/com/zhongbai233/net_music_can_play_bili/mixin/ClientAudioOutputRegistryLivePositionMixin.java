package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 物理化结构上「音源位置只在注册那一瞬间算一次」的修复（结构移动后声音钉在世界绝对坐标）。
 *
 * <h2>根因（不是硬编码常量，是"算一次就存进 record"）</h2>
 * <pre>
 *   播放开始 → ClientAudioOutputRegistry.register(handler, plotPos, …)
 *            → new AudioEntry(key, centerFor(plotPos), …)      // ← 唯一的投影，只做这一次
 *   每 tick  → updatePositions(listenerPos)
 *            → resolveMachinePos(entry) → resolveMachinePos(pos, machinePos, ownerId)
 *            → return originalPos;                             // ← 之后一直复用同一个 float[]
 *            → entry.output().tick(pos, listenerPos, …)        // 方向 + 距离都从这个值算
 * </pre>
 * 地面上方块不动，所以"注册时算一次"没问题；物理化结构会整体平移/旋转，于是那一份世界坐标
 * 永远停在**播放开始瞬间**的位置：声场朝向（{@code atan2(mp-lp)} + forward 平滑器）与距离增益
 * 全部按旧位置算 ⇒ 听起来就是"声音源写死在世界绝对坐标、不跟着结构走"。
 *
 * <p>音箱中继是同一个根因的第二个实例：{@code SpeakerAudioRelay.speakerPos} 也只在
 * {@code registerRelay} 里由 {@code AudioUtils.centerFor} 算一次，之后每 tick 被
 * {@code SpeakerAudioRelay.tick} 直接使用（{@code forward(speakerPos, listenerPos)} 与
 * {@code rangeAt}）。这里一并按 tick 重投影。
 *
 * <h2>为什么改成"每 tick 重投影"就够了</h2>
 * Sable 的 {@code ActiveSableCompanion.projectOutOfSubLevel(Level, Position)} 每次调用都
 * {@code getContaining(level, position)} 重新查一遍，并当场读当前位姿
 * （{@code LevelPoseProviderExtension.sable$getPose(subLevel)}，缺省回退 {@code subLevel.logicalPose()}）
 * 再 {@code pose.transformPosition(...)}（javap 实证）。所以 plot 坐标留在原地、每次重投即可跟着结构走。
 *
 * <p>地面场景：投影返回 {@code null}（该坐标不在任何 sub-level 里）⇒ 两处都原样走原实现，行为逐字节不变。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry")
public abstract class ClientAudioOutputRegistryLivePositionMixin {

    /**
     * 真实方块位置的音箱中继表。注意目标里它是 {@code private static final}：
     * Mixin 的规则是"目标是 final，@Shadow 就必须带 @Final"，否则启动时会报
     * {@code @Shadow target … is final but shadow is not decorated with @Final}。
     */
    @Shadow(remap = false)
    @Final
    private static ConcurrentMap<BlockPos, SpeakerAudioRelay> RELAYS;

    /**
     * 主输出位置：只在"原实现原样退回了注册时冻结的那个数组"时接管。
     *
     * <p>这条判据（{@code getReturnValue() == 传入的 frozenPos}）之所以成立，是因为目标方法只有三个出口：
     * 有 MP4 位置 / 找到拥有者玩家时返回**现算的新数组**，其余情况落到末尾
     * {@code return originalPos;} ——退回的那条路径的字节码结尾就是 {@code aload_1; areturn}
     * （返回入参同一个引用）。t4/build_mixins.py 的 {@code [2h]} 门禁每次构建都会在字节码上复查这一点，
     * 所以"用身份比较而不是数值比较"不是猜测。
     */
    @Inject(method = "resolveMachinePos(Lnet/minecraft/core/BlockPos;[FLjava/util/UUID;)[F",
            at = @At("RETURN"), cancellable = true)
    private static void ncpb$liveMachineWorldPos(BlockPos handlerKey, float[] frozenPos, UUID ownerId,
                                                  CallbackInfoReturnable<float[]> callback) {
        if (frozenPos == null || callback.getReturnValue() != frozenPos) {
            return;
        }
        // 必须自己挡 null level：兼容层把 getContaining 的**调用异常**当成"Sable 内部出错"，
        // 会 degradeAll()（把整套 Sable 能力清掉，整局都不再恢复）。真实 Sable 不接受 null level，
        // 这里只要有一次在菜单/断线瞬间走到，代价就是这一局的音频距离与投影全部失效。
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        float[] live = SableGeometryCompat.centerFor(level, handlerKey);
        if (live != null) {
            // 一次性证据行（结构移动 ≥0.1 格时打一条）：声音本身没法自动断言，
            // 有了这行就能在游戏内区分"结构没动"和"重投影没生效"。
            SableGeometryCompat.noteLivePositionRefreshed(frozenPos, live);
            callback.setReturnValue(live);
        }
    }

    /**
     * 音箱中继位置：每 tick（这个方法由 RenderFrameEvent.Pre + ClientTickEvent.Post 驱动）重投影一次。
     *
     * <p>为什么遍历 {@code RELAYS} 的键是安全的：控制台音频元素的中继键是
     * {@code ControlConsoleAudioElementKey.of} 把 elementId 的哈希 XOR 进控制台坐标得到的**合成键**，
     * 它不是任何真实方块位置。这种键落进某个 sub-level 的概率约为 10⁻¹⁵ 量级；而且控制台中继
     * 每 tick 都会被 {@code registerAudioForConsole} 重新设置位置，即使撞上也只影响一帧。
     */
    @Inject(method = "updatePositions", at = @At("HEAD"))
    private static void ncpb$refreshRelaySpeakerPos(float[] listenerPos, CallbackInfo callback) {
        if (RELAYS.isEmpty()) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (Map.Entry<BlockPos, SpeakerAudioRelay> entry : RELAYS.entrySet()) {
            float[] live = SableGeometryCompat.centerFor(level, entry.getKey());
            if (live != null) {
                entry.getValue().setSpeakerPos(live);
            }
        }
    }
}
