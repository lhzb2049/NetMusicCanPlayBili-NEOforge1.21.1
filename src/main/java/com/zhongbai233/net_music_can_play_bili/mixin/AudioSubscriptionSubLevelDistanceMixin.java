package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复「物理化之后服务端不再向玩家同步音频」。
 *
 * <p>目标方法（package-private class，用字符串定位）：
 * {@code AudioEndpointSubscriptionTracker.update(UUID, PlaybackSourceId, String, long, double, double, double, int, SpatialSnapshot)}。
 * 里面那句
 * <pre>
 * boolean sourceInterested = distanceSquared(playerX, playerY, playerZ,
 *         unpackX(sourcePos) + 0.5, unpackY(sourcePos) + 0.5, unpackZ(sourcePos) + 0.5) &lt;= range²;
 * </pre>
 * 用的是**普通 level 坐标**。物理化后 {@code sourcePos} 变成 plot 坐标（实测
 * {@code 20481032,128,20481032}），与玩家的世界坐标差了约 2000 万格 → 永远判 false →
 * 客户端再也收不到播放包（也就永远拿不到 plot 坐标去重建声音）。
 *
 * <p>这里把那次静态调用重定向到 {@link SableGeometryCompat#distanceSquaredInScope}：
 * 距离用 Sable 自己的 {@code distanceSquaredWithSubLevels}（内部把 plot 位置投影回世界）。
 * 没有 Sable 时退化为原来的欧氏距离，行为与修改前一致。
 *
 * <p>只用 {@code method=} 的完整描述符定位 {@code SpatialSnapshot} 那个重载：
 * 同类里还有一个 {@code (…, List)} 重载，若只写方法名，Mixin 会往两个方法上都注入，
 * 而另一个方法里没有这次调用 → 注入失败直接崩。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.blockentity.AudioEndpointSubscriptionTracker")
public class AudioSubscriptionSubLevelDistanceMixin {
    @Redirect(
            method = "update(Ljava/util/UUID;Lcom/zhongbai233/net_music_can_play_bili/media/sync/PlaybackSourceId;Ljava/lang/String;JDDDILcom/zhongbai233/net_music_can_play_bili/blockentity/AudioEndpointSubscriptionTracker$SpatialSnapshot;)Lcom/zhongbai233/net_music_can_play_bili/blockentity/AudioEndpointSubscriptionTracker$Update;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/blockentity/AudioEndpointSubscriptionTracker;distanceSquared(DDDDDD)D"))
    private static double ncpb$subLevelAwareDistance(double ax, double ay, double az, double bx, double by, double bz) {
        return SableGeometryCompat.distanceSquaredInScope(ax, ay, az, bx, by, bz);
    }
}
