package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

/**
 * 离线验收辅助：把 Pad / MP4 物品那条 RGBA 上传路径的打包函数暴露出来，
 * 让探针能用 {@code FastColor.ABGR32} 的取色器**直接验证**「红落在最低字节」。
 *
 * <p>为什么值得单独测：这一处把帧字节（R,G,B,A）打成 {@code setPixelRGBA} 需要的整数，
 * 写错就是把红蓝对调（画面发蓝），而且看不出编译错误。放在 tools/java 下、与产品同包，
 * 只为了拿到包私有访问权；不进入产物 jar。
 */
public final class VerifyHandheldRgbaProbe {
   private VerifyHandheldRgbaProbe() {
   }

   /** 返回 {@code packPixel(r,g,b,a)}：探针会拆开它逐个通道核对。 */
   public static int pack(int r, int g, int b, int a) {
      return MP4RgbaVideoLayer.packPixel(r, g, b, a);
   }
}
