package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复「物理化之后客户端把音量算成 0」。
 *
 * <p>{@code ClientAudioOutputRegistry.hasGeometricAudioDemand} 与 {@code hasPreparationDemand}
 * 都用 {@code AudioUtils.distance(listener, centerFor(sourcePos))}（普通欧氏距离）去喂
 * 64 格球判定 {@code AudioPlaybackRange.evaluateSphere(…)}。音源在 sub-level 里时
 * sourcePos 是 plot 坐标（实测 {@code 20481032,128,20481032}），距离被算成约 2000 万格 →
 * {@code presentationEnvelope.gain(false)} → 音量 0 → 结构上无声。
 *
 * <p>这里把那次距离调用换成 {@link SableGeometryCompat#distance}，内部走 Sable 的
 * {@code distanceSquaredWithSubLevels}（把 plot 位置投影回世界坐标再比）。
 * 没有 Sable 时退化为原来的欧氏距离。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry")
public class ClientAudioDemandSubLevelDistanceMixin {
    @Redirect(
            method = {"hasGeometricAudioDemand", "hasPreparationDemand"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/media/audio/AudioUtils;distance([F[F)F"))
    private static float ncpb$subLevelAwareDistance(float[] a, float[] b) {
        return SableGeometryCompat.distance(Minecraft.getInstance().level, a, b);
    }
}
