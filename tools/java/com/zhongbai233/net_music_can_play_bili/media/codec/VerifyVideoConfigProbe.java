package com.zhongbai233.net_music_can_play_bili.media.codec;

/**
 * 离线验收辅助：把视频初始化段喂给**产品自己的** decoder config 提取器，
 * 证明 {@code Fmp4NativeVideoDecoder.onMoov} 那一步（avcC/av1C → Annex-B 前缀 + NAL 长度字节数）
 * 能接受重封装出来的 init（那一步不通过，原生解码器会直接抛「无法从 moov/stsd 提取视频 decoder config」）。
 *
 * <p>放在 tools/java 下、与产品同包，只为了拿到包私有访问权；不进入产物 jar。
 */
public final class VerifyVideoConfigProbe {
   private VerifyVideoConfigProbe() {
   }

   /** 返回 {@code "nalLengthSize/prefixBytes"}；提取失败返回 {@code "none"}。 */
   public static String describe(byte[] moovPayload, int codecId) {
      Fmp4NativeVideoDecoder.DecoderConfig config = Fmp4VideoDecoderConfigParser.extract(moovPayload, codecId);
      return config == null ? "none" : config.nalLengthSize() + "/" + config.packetPrefix().length;
   }
}
