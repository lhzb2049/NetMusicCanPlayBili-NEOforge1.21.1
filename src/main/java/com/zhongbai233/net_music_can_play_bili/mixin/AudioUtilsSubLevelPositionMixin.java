package com.zhongbai233.net_music_can_play_bili.mixin;

import com.zhongbai233.net_music_can_play_bili.compat.sable.SableGeometryCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把「音源世界位置」这一个入口变成 sub-level 感知的。
 *
 * <p>{@code AudioUtils.centerFor(BlockPos)} = {@code {x+0.5, y+0.5, z+0.5}}，是全模组唯一的
 * 「BlockPos → 世界坐标」入口：{@code ClientAudioOutputRegistry.register/registerStereo} 把它存进
 * {@code AudioEntry}（音源位置），{@code audioDemandDebug} / {@code hasAudibleOutput} / 空间增益
 * 全从它取。物理化后那个 BlockPos 是 plot 坐标（实测 {@code 20481032,128,20481032}），
 * 于是距离被算成约 2000 万格 → 增益 0 → 结构上听不到（**NCPB 的媒体音频不走 Minecraft 的
 * SoundEngine，所以 Sable 的 {@code SoundEngineMixin} 帮不上忙**，它只管 Minecraft 自己的声音）。
 *
 * <p>这里只在「该位置落在某个 sub-level 里」时替换返回值（投影到世界坐标），否则原实现照常返回，
 * 所以地面/普通场景行为逐字节不变。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils")
public class AudioUtilsSubLevelPositionMixin {
    @Inject(method = "centerFor", at = @At("HEAD"), cancellable = true)
    private static void ncpb$subLevelCenterFor(BlockPos pos, CallbackInfoReturnable<float[]> callback) {
        float[] projected = SableGeometryCompat.centerFor(Minecraft.getInstance().level, pos);
        if (projected != null) {
            callback.setReturnValue(projected);
        }
    }
}
