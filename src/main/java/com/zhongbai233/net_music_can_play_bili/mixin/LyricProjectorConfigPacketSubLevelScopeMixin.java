package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.network.LyricProjectorConfigPacket;
import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端"8 格距离守卫"的坐标系修复（jar 里 8 个包处理器用的是同一形状，这是其中之一）。
 *
 * <h2>根因</h2>
 * <pre>
 *   if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) &gt; 64.0)) { ...真正处理... }
 * </pre>
 * 物理化结构上 payload.pos() 是 Sable 的 plot 坐标（实测 20481032,129,20481032），而玩家在世界坐标
 * （实测 -28.5,-58.5,24.2）⇒ 距离² ≈ 8×10¹⁴ &gt; 64 ⇒ 守卫失败 ⇒ **整包被静默丢弃**。
 * 表现出来就是：方块上的 UI 能打开、按钮点了没反应（客户端确实把包发出去了），
 * 而左键破坏 / 右键取出放入照常 —— 后者走方块实体自己的路径，没有这道守卫。
 *
 * <h2>改法</h2>
 * 只在 handle 内把这次距离比较换成 sub-level 感知距离（比较的两点各自 plot→世界），
 * 阈值与其余逻辑仍是原代码：HEAD 压入该包的 Level、RETURN 弹出（**专用作用域**，
 * 与音频那条 SOURCE_LEVEL 互不影响，避免误弹出别人的压栈）。
 * 地面场景 getContaining 返回 null ⇒ 退化为普通欧氏距离，行为不变。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.network.LyricProjectorConfigPacket")
public class LyricProjectorConfigPacketSubLevelScopeMixin {

    @Inject(method = "handle", at = @At("HEAD"))
    private static void ncpb$pushNetworkScope(LyricProjectorConfigPacket payload, IPayloadContext context, CallbackInfo callback) {
        if (context.player() instanceof ServerPlayer player) {
            SableGeometryCompat.pushNetworkScope(player.level());
        }
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private static void ncpb$popNetworkScope(LyricProjectorConfigPacket payload, IPayloadContext context, CallbackInfo callback) {
        if (context.player() instanceof ServerPlayer) {
            SableGeometryCompat.popNetworkScope();
        }
    }

    @Redirect(method = "handle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"))
    private static double ncpb$subLevelGuardDistance(Vec3 playerPos, Vec3 target) {
        return SableGeometryCompat.distanceSquaredInNetworkScope(
                playerPos.x, playerPos.y, playerPos.z, target.x, target.y, target.z);
    }
}
