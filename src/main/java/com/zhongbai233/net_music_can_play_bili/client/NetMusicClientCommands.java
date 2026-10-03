package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.debug.PlaybackDebugMode;
import com.zhongbai233.net_music_can_play_bili.client.debug.PlaybackRangeDebugRenderer;
import com.zhongbai233.net_music_can_play_bili.client.debug.VideoPlaybackDebugRenderer;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapClientCache;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientAiSubtitleRegistry;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoPlaceholderDebugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber({Dist.CLIENT})
public final class NetMusicClientCommands {
   public static final String ROOT_COMMAND = "netmusicbiliclient";
   public static final String SHORT_ROOT_COMMAND = "ncpbc";

   private NetMusicClientCommands() {
   }

   @SubscribeEvent
   public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
      CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
      dispatcher.register(clientRoot("netmusicbiliclient"));
      dispatcher.register(clientRoot("ncpbc"));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> clientRoot(String name) {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                              name
                           )
                           .then(Commands.literal("status").executes(NetMusicClientCommands::showPlaybackStatus)))
                        .then(hologlassCommands()))
                     .then(padCommands()))
                  .then(videoCommands()))
               .then(debugCommands()))
            .then(benchCommands()))
         .then(dolbyCommands());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> debugCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("debug")
         .then(
            ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                                "playback"
                                             )
                                             .executes(ctx -> togglePlaybackDebug()))
                                          .then(Commands.literal("on").executes(ctx -> setPlaybackDebug(true))))
                                       .then(Commands.literal("off").executes(ctx -> setPlaybackDebug(false))))
                                    .then(Commands.literal("ui").executes(ctx -> setPlaybackDebugMode(PlaybackDebugMode.UI))))
                                 .then(Commands.literal("range").executes(ctx -> setPlaybackDebugMode(PlaybackDebugMode.RANGE))))
                              .then(Commands.literal("both").executes(ctx -> setPlaybackDebugMode(PlaybackDebugMode.BOTH))))
                           .then(Commands.literal("toggle").executes(ctx -> togglePlaybackDebug())))
                        .then(Commands.literal("status").executes(ctx -> showPlaybackDebugStatus())))
                     .then(Commands.literal("dump").executes(ctx -> dumpPlaybackDebugStatus())))
                  .then(audioDebugCommands()))
               .then(videoDebugCommands())
         );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> audioDebugCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                    "audio"
                                 )
                                 .executes(ctx -> toggleAudioDebug()))
                              .then(Commands.literal("on").executes(ctx -> setAudioDebug(true))))
                           .then(Commands.literal("off").executes(ctx -> setAudioDebug(false))))
                        .then(Commands.literal("ui").executes(ctx -> setAudioDebugMode(PlaybackDebugMode.UI))))
                     .then(Commands.literal("range").executes(ctx -> setAudioDebugMode(PlaybackDebugMode.RANGE))))
                  .then(Commands.literal("both").executes(ctx -> setAudioDebugMode(PlaybackDebugMode.BOTH))))
               .then(Commands.literal("toggle").executes(ctx -> toggleAudioDebug())))
            .then(Commands.literal("status").executes(ctx -> showAudioDebugStatus())))
         .then(Commands.literal("dump").executes(ctx -> dumpAudioDebugStatus()));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> videoDebugCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                                    "video"
                                 )
                                 .executes(ctx -> toggleVideoDebug()))
                              .then(Commands.literal("on").executes(ctx -> setVideoDebug(true))))
                           .then(Commands.literal("off").executes(ctx -> setVideoDebug(false))))
                        .then(Commands.literal("ui").executes(ctx -> setVideoDebugMode(PlaybackDebugMode.UI))))
                     .then(Commands.literal("range").executes(ctx -> setVideoDebugMode(PlaybackDebugMode.RANGE))))
                  .then(Commands.literal("both").executes(ctx -> setVideoDebugMode(PlaybackDebugMode.BOTH))))
               .then(Commands.literal("toggle").executes(ctx -> toggleVideoDebug())))
            .then(Commands.literal("status").executes(ctx -> showVideoDebugStatus())))
         .then(Commands.literal("dump").executes(ctx -> dumpVideoDebugStatus()));
   }

   private static int setPlaybackDebug(boolean enabled) {
      PlaybackRangeDebugRenderer.setEnabled(enabled);
      VideoPlaybackDebugRenderer.setEnabled(enabled);
      feedback(Component.literal("音频与视频调试已" + (enabled ? "开启" : "关闭")));
      return 1;
   }

   private static int setPlaybackDebugMode(PlaybackDebugMode mode) {
      PlaybackRangeDebugRenderer.setMode(mode);
      VideoPlaybackDebugRenderer.setMode(mode);
      feedback(Component.literal("音频与视频调试模式：" + mode.name()));
      return 1;
   }

   private static int togglePlaybackDebug() {
      return setPlaybackDebug(!PlaybackRangeDebugRenderer.enabled() || !VideoPlaybackDebugRenderer.enabled());
   }

   private static int showPlaybackDebugStatus() {
      showAudioDebugStatus();
      showVideoDebugStatus();
      return 1;
   }

   private static int dumpPlaybackDebugStatus() {
      dumpAudioDebugStatus();
      dumpVideoDebugStatus();
      return 1;
   }

   private static int setAudioDebug(boolean enabled) {
      PlaybackRangeDebugRenderer.setEnabled(enabled);
      feedback(Component.literal("音频范围调试已" + (enabled ? "开启" : "关闭")));
      return 1;
   }

   private static int setAudioDebugMode(PlaybackDebugMode mode) {
      PlaybackRangeDebugRenderer.setMode(mode);
      feedback(Component.literal("音频调试模式：" + mode.name()));
      return 1;
   }

   private static int toggleAudioDebug() {
      return setAudioDebug(PlaybackRangeDebugRenderer.toggle());
   }

   private static int showAudioDebugStatus() {
      PlaybackRangeDebugRenderer.describe().stream().findFirst().ifPresent(line -> feedback(Component.literal(line)));
      return 1;
   }

   private static int dumpAudioDebugStatus() {
      PlaybackRangeDebugRenderer.describe().forEach(line -> feedback(Component.literal(line)));
      return 1;
   }

   private static int setVideoDebug(boolean enabled) {
      VideoPlaybackDebugRenderer.setEnabled(enabled);
      feedback(Component.literal("视频视锥与解码调试已" + (enabled ? "开启" : "关闭")));
      return 1;
   }

   private static int setVideoDebugMode(PlaybackDebugMode mode) {
      VideoPlaybackDebugRenderer.setMode(mode);
      feedback(Component.literal("视频调试模式：" + mode.name()));
      return 1;
   }

   private static int toggleVideoDebug() {
      return setVideoDebug(VideoPlaybackDebugRenderer.toggle());
   }

   private static int showVideoDebugStatus() {
      VideoPlaybackDebugRenderer.describe().stream().findFirst().ifPresent(line -> feedback(Component.literal(line)));
      return 1;
   }

   private static int dumpVideoDebugStatus() {
      VideoPlaybackDebugRenderer.describe().forEach(line -> feedback(Component.literal(line)));
      return 1;
   }

   private static LiteralArgumentBuilder<CommandSourceStack> hologlassCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal("hologlass")
            .then(Commands.literal("config").executes(NetMusicClientCommands::openHolographicGlassesConfig)))
         .then(Commands.literal("test").executes(NetMusicClientCommands::openHolographicScreenConfigTest));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> padCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)Commands.literal("pad")
         .then(
            ((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("cache")
                     .then(Commands.literal("status").executes(NetMusicClientCommands::showPadMapCacheStatus)))
                  .then(Commands.literal("save").executes(NetMusicClientCommands::savePadMapCache)))
               .then(Commands.literal("refresh").executes(NetMusicClientCommands::refreshPadMapCache))
         );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> videoCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("video")
               .then(Commands.literal("status").executes(NetMusicClientCommands::showVideoLifecycleStatus)))
            .then(Commands.literal("placeholders").executes(NetMusicClientCommands::openVideoPlaceholderDebug)))
         .then(Commands.literal("retry").executes(NetMusicClientCommands::retryFailedVideos));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> benchCommands() {
      LiteralArgumentBuilder<CommandSourceStack> bench = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal(
                     "bench"
                  )
                  .then(Commands.literal("status").executes(NetMusicClientCommands::showBenchStatus)))
               .then(Commands.literal("reset").executes(NetMusicClientCommands::resetBench)))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("mark")
                     .then(
                        Commands.argument("label", StringArgumentType.greedyString())
                           .executes(ctx -> markBench(ctx, StringArgumentType.getString(ctx, "label")))
                     ))
                  .executes(ctx -> markBench(ctx, "manual"))
            ))
         .then(
            Commands.literal("perceived")
               .then(
                  ((RequiredArgumentBuilder)Commands.argument("delayMs", IntegerArgumentType.integer(-10000, 10000))
                        .then(
                           Commands.argument("note", StringArgumentType.greedyString())
                              .executes(ctx -> perceivedBench(ctx, IntegerArgumentType.getInteger(ctx, "delayMs"), StringArgumentType.getString(ctx, "note")))
                        ))
                     .executes(ctx -> perceivedBench(ctx, IntegerArgumentType.getInteger(ctx, "delayMs"), ""))
               )
         );
      LiteralArgumentBuilder<CommandSourceStack> videoBench = (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)Commands.literal("video")
            .then(
               ((LiteralArgumentBuilder)Commands.literal("cpu-bars").executes(ctx -> startCpuBarsBench(ctx, false)))
                  .then(Commands.literal("ignoreSlowFrames").executes(ctx -> startCpuBarsBench(ctx, true)))
            ))
         .then(
            ((LiteralArgumentBuilder)Commands.literal("bili-real").executes(ctx -> startBiliRealBench(ctx, false)))
               .then(Commands.literal("ignoreSlowFrames").executes(ctx -> startBiliRealBench(ctx, true)))
         );
      return (LiteralArgumentBuilder<CommandSourceStack>)bench.then(videoBench);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> dolbyCommands() {
      return (LiteralArgumentBuilder<CommandSourceStack>)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("dolby")
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)Commands.literal("joc")
                              .then(Commands.literal("on").executes(ctx -> setJoc(ctx, true))))
                           .then(Commands.literal("off").executes(ctx -> setJoc(ctx, false))))
                        .then(Commands.literal("toggle").executes(ctx -> setJoc(ctx, !BiliConfig.dolbyJocEnabled))))
                     .then(Commands.literal("status").executes(NetMusicClientCommands::showJocStatus))
               ))
            .then(
               ((LiteralArgumentBuilder)Commands.literal("objects").then(Commands.literal("status").executes(NetMusicClientCommands::showObjectLimit)))
                  .then(
                     Commands.argument("count", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> setObjectLimit(ctx, IntegerArgumentType.getInteger(ctx, "count")))
                  )
            ))
         .then(Commands.literal("source").then(Commands.literal("status").executes(NetMusicClientCommands::showSourceStatus)));
   }

   private static int openHolographicGlassesConfig(CommandContext<CommandSourceStack> ctx) {
      Minecraft minecraft = Minecraft.getInstance();
      minecraft.execute(() -> minecraft.setScreen(new HolographicScreenConfigTestScreen(true)));
      feedback(Component.literal("已打开全息眼镜配置界面"));
      return 1;
   }

   private static int openHolographicScreenConfigTest(CommandContext<CommandSourceStack> ctx) {
      Minecraft minecraft = Minecraft.getInstance();
      minecraft.execute(() -> minecraft.setScreen(new HolographicScreenConfigTestScreen()));
      feedback(Component.literal("已打开全息屏幕配置测试界面"));
      return 1;
   }

   private static int openVideoPlaceholderDebug(CommandContext<CommandSourceStack> ctx) {
      Minecraft minecraft = Minecraft.getInstance();
      minecraft.execute(() -> minecraft.setScreen(new VideoPlaceholderDebugScreen()));
      feedback(Component.literal("已打开视频占位图调试界面"));
      return 1;
   }

   private static int retryFailedVideos(CommandContext<CommandSourceStack> ctx) {
      int retried = VideoBillboardPreview.retryAllNetworkFailures();
      feedback(Component.literal(retried > 0 ? "已重试 " + retried + " 个网络失败的视频会话" : "当前没有可重试的网络失败视频"));
      return retried > 0 ? 1 : 0;
   }

   private static int showVideoLifecycleStatus(CommandContext<CommandSourceStack> ctx) {
      for (String line : ModernTurntableVideoClient.describeVideoLifecycle()) {
         feedback(Component.literal(line));
      }

      feedback(Component.literal(ClientAiSubtitleRegistry.describe()));
      return 1;
   }

   private static void feedback(Component msg) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null) {
         player.sendSystemMessage(msg);
      }
   }

   private static int setJoc(CommandContext<CommandSourceStack> ctx, boolean enabled) {
      BiliConfig.dolbyJocEnabled = enabled;
      BiliConfig.save();
      feedback(Component.literal(enabled ? "杜比 JOC 对象音频：已启用" : "杜比 JOC 对象音频：已关闭"));
      return 1;
   }

   private static int showJocStatus(CommandContext<CommandSourceStack> ctx) {
      feedback(Component.literal(BiliConfig.dolbyJocEnabled ? "杜比 JOC 对象音频：已启用" : "杜比 JOC 对象音频：已关闭"));
      return 1;
   }

   private static int setObjectLimit(CommandContext<CommandSourceStack> ctx, int count) {
      BiliConfig.dolbyMaxObjectSources = count;
      BiliConfig.save();
      feedback(Component.literal("Dolby JOC object source limit: " + BiliConfig.dolbyMaxObjectSources()));
      return 1;
   }

   private static int showObjectLimit(CommandContext<CommandSourceStack> ctx) {
      feedback(Component.literal("Dolby JOC object source limit: " + BiliConfig.dolbyMaxObjectSources()));
      return 1;
   }

   private static int showSourceStatus(CommandContext<CommandSourceStack> ctx) {
      for (String line : ClientAudioOutputRegistry.describeActiveSources()) {
         feedback(Component.literal(line));
      }

      return 1;
   }

   private static int showPlaybackStatus(CommandContext<CommandSourceStack> ctx) {
      for (String line : BiliPlaybackDiagnostics.describeCurrentPlayback()) {
         feedback(Component.literal(line));
      }

      return 1;
   }

   private static int showBenchStatus(CommandContext<CommandSourceStack> ctx) {
      PlaybackLatencyBench.logNow();
      feedback(Component.literal(PlaybackLatencyBench.enabled() ? "播放延迟Bench已开启，详细数据已输出到日志" : "播放延迟Bench未开启；runClient 加 -PncpbPlaybackLatencyBench=true"));
      return 1;
   }

   private static int resetBench(CommandContext<CommandSourceStack> ctx) {
      PlaybackLatencyBench.reset();
      feedback(Component.literal("播放延迟Bench已重置"));
      return 1;
   }

   private static int markBench(CommandContext<CommandSourceStack> ctx, String label) {
      PlaybackLatencyBench.markUser(label);
      feedback(Component.literal("播放延迟Bench标记: " + label));
      return 1;
   }

   private static int perceivedBench(CommandContext<CommandSourceStack> ctx, int delayMs, String note) {
      PlaybackLatencyBench.recordPerceivedDelay(delayMs, note);
      feedback(Component.literal("播放延迟Bench听感记录: " + delayMs + "ms"));
      return 1;
   }

   private static int startCpuBarsBench(CommandContext<CommandSourceStack> ctx, boolean ignoreSlowFrames) {
      boolean started = VideoRenderStressBench.startCommand(ignoreSlowFrames);
      feedback(Component.literal(started ? "CPU彩条视频Bench已启动；" + slowFramePolicy(ignoreSlowFrames) : "CPU彩条视频Bench已在运行"));
      return started ? 1 : 0;
   }

   private static int startBiliRealBench(CommandContext<CommandSourceStack> ctx, boolean ignoreSlowFrames) {
      boolean started = BiliRealVideoPlaybackBench.startCommand(ignoreSlowFrames);
      feedback(Component.literal(started ? "B站真实解析视频Bench已启动；" + slowFramePolicy(ignoreSlowFrames) : "B站真实解析视频Bench已在运行"));
      return started ? 1 : 0;
   }

   private static String slowFramePolicy(boolean ignoreSlowFrames) {
      return ignoreSlowFrames ? "将无视过慢帧继续跑完更高分辨率" : "遇到过慢帧会停止后续更高分辨率";
   }

   private static int showPadMapCacheStatus(CommandContext<CommandSourceStack> ctx) {
      feedback(Component.literal("Pad地图缓存: " + PadMapClientCache.describeStatus()));
      return 1;
   }

   private static int savePadMapCache(CommandContext<CommandSourceStack> ctx) {
      PadMapClientCache.flushDiskCache();
      feedback(Component.literal("Pad地图缓存已尝试落盘: " + PadMapClientCache.diskCachePath()));
      return 1;
   }

   private static int refreshPadMapCache(CommandContext<CommandSourceStack> ctx) {
      int cleared = PadMapClientCache.clearAllCaches(true);
      feedback(Component.literal("Pad地图缓存已刷新: clearedMemoryCells=" + cleared));
      return 1;
   }
}
