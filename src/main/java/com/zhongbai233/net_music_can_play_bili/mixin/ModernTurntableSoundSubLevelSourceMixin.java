package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 存活判据兜底：音源在 sub-level 内也算「源可用」。
 *
 * <p>原实现（{@code ModernTurntableSound.fixedSourceAvailable}）是
 * <pre>
 * return turntable != null ? turntable.isPlaying()
 *      : sourceId != null &amp;&amp; ClientAudioEndpointIndex.sourcePosition(sourceId) != null;
 * </pre>
 * 物理化那一瞬间方块搬到 plot 里，若客户端这一步还没拿到 plot 坐标（旧声音停在物理化前的
 * 世界坐标），{@code level.getBlockEntity(pos)} 查不到 → 返回 false → 40 tick 后
 * {@code stopAndFinish()} 把声音杀掉。这里在返回 false 时补一条：
 * 该 pos 落在某个 Sable sub-level 内就认为源可用（配合另外两个 Mixin 把坐标口径修正后，
 * 正常情况下本来就查得到，这条是防止「切换瞬间」被判死）。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntableSound")
public abstract class ModernTurntableSoundSubLevelSourceMixin {
    @Shadow(remap = false)
    @Final
    private BlockPos pos;

    @Inject(method = "fixedSourceAvailable", at = @At("RETURN"), cancellable = true)
    private void ncpb$acceptSubLevelSource(CallbackInfoReturnable<Boolean> callback) {
        if (Boolean.FALSE.equals(callback.getReturnValue())
                && SableGeometryCompat.isInsideSubLevel(Minecraft.getInstance().level, this.pos.getX(), this.pos.getZ())) {
            callback.setReturnValue(true);
        }
    }
}
