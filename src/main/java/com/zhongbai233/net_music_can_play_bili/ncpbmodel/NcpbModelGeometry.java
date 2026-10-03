package com.zhongbai233.net_music_can_play_bili.ncpbmodel;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 把 {@code ncpb:euler_elements} 这个模型 loader 注册进 NeoForge。
 *
 * <p>没有改模组自己的主类（那需要改 jar 内已编译的类），而是靠 NeoForge 的
 * {@code @EventBusSubscriber} 自动扫描 —— 模组加载时会扫描本模组包内带该注解的类，
 * 日志里能看到 {@code Scanning class ... for @EventBusSubscriber-annotated methods}。</p>
 */
@EventBusSubscriber(modid = NcpbModelGeometry.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NcpbModelGeometry {

    public static final String MODID = "net_music_can_play_bili";

    /** 对应模型 JSON 里的 {@code "loader": "net_music_can_play_bili:euler_elements"}。 */
    public static final ResourceLocation EULER_ELEMENTS =
            ResourceLocation.fromNamespaceAndPath(MODID, "euler_elements");

    private NcpbModelGeometry() {
    }

    @SubscribeEvent
    public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(EULER_ELEMENTS, new EulerElementsLoader());
    }
}
