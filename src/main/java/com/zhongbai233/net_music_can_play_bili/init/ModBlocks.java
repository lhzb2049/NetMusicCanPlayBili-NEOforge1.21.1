package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.block.ControlConsoleBlock;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.block.LyricProjectorBlock;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.block.SpeakerBlock;
import com.zhongbai233.net_music_can_play_bili.block.VideoProjectorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.Blocks;

public final class ModBlocks {
   public static final Blocks BLOCKS = DeferredRegister.createBlocks("net_music_can_play_bili");
   public static final DeferredBlock<Block> MODERN_TURNTABLE = BLOCKS.register("modern_turntable", id -> new ModernTurntableBlock(Properties.of()));
   public static final DeferredBlock<Block> LYRIC_PROJECTOR = BLOCKS.register("lyric_projector", id -> new LyricProjectorBlock(Properties.of()));
   public static final DeferredBlock<Block> VIDEO_PROJECTOR = BLOCKS.register("video_projector", id -> new VideoProjectorBlock(Properties.of()));
   public static final DeferredBlock<Block> SPEAKER = BLOCKS.register("speaker", id -> new SpeakerBlock(Properties.of()));
   public static final DeferredBlock<Block> LIVE_STREAMER = BLOCKS.register("live_streamer", id -> new LiveStreamerBlock(Properties.of()));
   public static final DeferredBlock<Block> CONTROL_CONSOLE = BLOCKS.register("control_console", id -> new ControlConsoleBlock(Properties.of()));

   private ModBlocks() {
   }
}
