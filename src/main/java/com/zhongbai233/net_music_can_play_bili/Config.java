package com.zhongbai233.net_music_can_play_bili;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

public class Config {
   private static final Builder BUILDER = new Builder();
   private static final BooleanValue ENABLE_DEBUG_LOG = BUILDER.comment("是否启用详细的 B站 API 调试日志").define("enableDebugLog", false);
   private static final BooleanValue ENABLE_LINK_WHITELIST = BUILDER.comment("是否启用服务端 BV/av 号与 NetMusic 第三方链接白名单").define("enableLinkWhitelist", false);
   private static final ConfigValue<String> LINK_WHITELIST_CONTACT_PLACEHOLDER = BUILDER.comment("玩家无权自行添加白名单时，在拒绝提示中显示的联系人名称")
      .define("linkWhitelistContactPlaceholder", "OP4");
   static final ModConfigSpec SPEC = BUILDER.build();
   public static boolean enableDebugLog;
   public static boolean enableLinkWhitelist;
   public static String linkWhitelistContactPlaceholder;

   @SubscribeEvent
   static void onLoad(ModConfigEvent event) {
      enableDebugLog = (Boolean)ENABLE_DEBUG_LOG.get();
      enableLinkWhitelist = (Boolean)ENABLE_LINK_WHITELIST.get();
      linkWhitelistContactPlaceholder = (String)LINK_WHITELIST_CONTACT_PLACEHOLDER.get();
   }
}
