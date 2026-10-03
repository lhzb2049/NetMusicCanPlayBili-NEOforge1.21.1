package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「音源所在的 Level」交给 {@link SableGeometryCompat} 的作用域。
 *
 * <p>原因：{@code AudioEndpointSubscriptionTracker.update(...)} 里没有 Level 参数，
 * 但它要拿的 {@code sourcePos} 可能是 plot 坐标；判定距离必须有一个 Level 才能问 Sable
 * 「这个 plot 位置投影到世界是哪」。这两个方法（也是 tracker 唯一的两个调用方）手里正好有
 * {@code ServerLevel}/{@code anchorLevel}，所以在 HEAD 压栈、RETURN 出栈。
 *
 * <p>⚠️ 处理器签名必须**逐参数匹配目标方法**（Mixin 不接受只捕获前几个参数的写法）：
 * 目标返回 {@code Set<UUID>}，所以末尾用 {@code CallbackInfoReturnable<Set<UUID>>}。
 * 这一条已经用真实崩溃付过学费：只写前两个参数时，Mixin 在**目标类首次加载**时报
 * {@code InvalidInjectionException: Invalid descriptor} —— 而目标类是玩家往唱片机放唱片、
 * 服务端第一次同步受众时才加载的，于是表现为「放唱片时崩服」。
 * t4/build_mixins.py 的 [2e] 步现在会在构建期用字节码比对挡住这类错误。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableAudienceSync")
public class AudienceSyncLevelScopeMixin {
    private static final String SYNC_NEARBY_PLAYERS =
            "syncNearbyPlayers(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lcom/zhongbai233/net_music_can_play_bili/media/sync/PlaybackSourceId;Ljava/util/Set;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJII)Ljava/util/Set;";
    private static final String SYNC_NEARBY_SPECTATORS =
            "syncNearbySpectators(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lcom/zhongbai233/net_music_can_play_bili/media/sync/PlaybackSourceId;Ljava/util/Set;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJII)Ljava/util/Set;";

    @Inject(method = SYNC_NEARBY_PLAYERS, at = @At("HEAD"))
    private static void ncpb$pushSourceLevelForPlayers(
            ServerLevel serverLevel,
            Level anchorLevel,
            BlockPos sourcePos,
            PlaybackSourceId sourceId,
            Set<UUID> previouslySynced,
            String playUrl,
            String rawUrl,
            String songName,
            String sessionId,
            long elapsedMillis,
            long durationMillis,
            int remainingSeconds,
            int range,
            CallbackInfoReturnable<Set<UUID>> callback) {
        SableGeometryCompat.pushSourceLevel(anchorLevel);
    }

    @Inject(method = SYNC_NEARBY_PLAYERS, at = @At("RETURN"))
    private static void ncpb$popSourceLevelForPlayers(
            ServerLevel serverLevel,
            Level anchorLevel,
            BlockPos sourcePos,
            PlaybackSourceId sourceId,
            Set<UUID> previouslySynced,
            String playUrl,
            String rawUrl,
            String songName,
            String sessionId,
            long elapsedMillis,
            long durationMillis,
            int remainingSeconds,
            int range,
            CallbackInfoReturnable<Set<UUID>> callback) {
        SableGeometryCompat.popSourceLevel();
    }

    @Inject(method = SYNC_NEARBY_SPECTATORS, at = @At("HEAD"))
    private static void ncpb$pushSourceLevelForSpectators(
            ServerLevel serverLevel,
            Level anchorLevel,
            BlockPos sourcePos,
            PlaybackSourceId sourceId,
            Set<UUID> previouslySynced,
            String playUrl,
            String rawUrl,
            String songName,
            String sessionId,
            long elapsedMillis,
            long durationMillis,
            int remainingSeconds,
            int range,
            CallbackInfoReturnable<Set<UUID>> callback) {
        SableGeometryCompat.pushSourceLevel(anchorLevel);
    }

    @Inject(method = SYNC_NEARBY_SPECTATORS, at = @At("RETURN"))
    private static void ncpb$popSourceLevelForSpectators(
            ServerLevel serverLevel,
            Level anchorLevel,
            BlockPos sourcePos,
            PlaybackSourceId sourceId,
            Set<UUID> previouslySynced,
            String playUrl,
            String rawUrl,
            String songName,
            String sessionId,
            long elapsedMillis,
            long durationMillis,
            int remainingSeconds,
            int range,
            CallbackInfoReturnable<Set<UUID>> callback) {
        SableGeometryCompat.popSourceLevel();
    }
}
