package com.zhongbai233.net_music_can_play_bili.block;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableClientHooks;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ModernTurntableBlock extends HorizontalDirectionalBlock implements EntityBlock {
   public static final BooleanProperty HAS_DISC = BooleanProperty.create("has_disc");
   public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
   private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 8.0, 15.0);
   private static final MapCodec<ModernTurntableBlock> CODEC = simpleCodec(ModernTurntableBlock::new);

   public ModernTurntableBlock(Properties properties) {
      super(properties.sound(SoundType.WOOD).strength(1.5F).noOcclusion());
      this.registerDefaultState(
         (BlockState)((BlockState)((BlockState)((BlockState)this.stateDefinition.any()).setValue(FACING, Direction.SOUTH)).setValue(HAS_DISC, false))
            .setValue(PLAYING, false)
      );
   }

   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new ModernTurntableBlockEntity(pos, state);
   }

   public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
      return !level.isClientSide() && type == ModBlockEntities.MODERN_TURNTABLE.get()
         ? (tickLevel, pos, tickState, blockEntity) -> ModernTurntableBlockEntity.tick(tickLevel, pos, tickState, (ModernTurntableBlockEntity)blockEntity)
         : null;
   }

   protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
      if (level instanceof ServerLevel serverLevel && (movedByPiston || !state.is(newState.getBlock()))) {
         AudioLinkIndex.removePlaybackSource(serverLevel, pos);
      }

      super.onRemove(state, level, pos, newState, movedByPiston);
   }

   protected ItemInteractionResult useItemOn(
      ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
   ) {
      if (hand == InteractionHand.OFF_HAND) {
         return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      } else if (player.isShiftKeyDown()) {
         if (level.isClientSide()) {
            openClientScreen(pos);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         } else {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      } else if (stack.getItem() == ModItems.LYRIC_PROJECTOR.get()) {
         if (level.isClientSide()) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         } else {
            LinkHelper.writeLinkToItem(stack, pos);
            player.sendSystemMessage(
               Component.translatable("message.net_music_can_play_bili.lyric_projector.item_linked", new Object[]{pos.getX(), pos.getY(), pos.getZ()})
                  .withStyle(ChatFormatting.GOLD)
            );
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      } else if (stack.getItem() == ModItems.VIDEO_PROJECTOR.get()) {
         if (level.isClientSide()) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         } else {
            LinkHelper.writeLinkToItem(stack, pos);
            VideoProjectorBlock.writeLinkedBlockEntityData(stack, pos);
            player.sendSystemMessage(
               Component.translatable("message.net_music_can_play_bili.video_projector.item_linked", new Object[]{pos.getX(), pos.getY(), pos.getZ()})
                  .withStyle(ChatFormatting.GOLD)
            );
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      } else if (stack.getItem() == ModItems.SPEAKER.get()) {
         if (level.isClientSide()) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         } else {
            LinkHelper.writeLinkToItem(stack, pos);
            player.sendSystemMessage(
               Component.translatable("message.net_music_can_play_bili.speaker.item_linked", new Object[]{pos.getX(), pos.getY(), pos.getZ()})
                  .withStyle(ChatFormatting.GOLD)
            );
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      } else if (stack.getItem() == ModItems.CONTROL_CONSOLE.get()) {
         if (level.isClientSide()) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         } else {
            LinkHelper.writeControlConsoleLinkToItem(stack, pos, level.dimension().location().toString(), LinkHelper.ControlConsoleSourceKind.TURNTABLE);
            player.sendSystemMessage(
               Component.translatable("message.net_music_can_play_bili.control_console.item_linked", new Object[]{pos.getX(), pos.getY(), pos.getZ()})
                  .withStyle(ChatFormatting.GOLD)
            );
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      } else if (level.isClientSide()) {
         return ItemInteractionResult.sidedSuccess(level.isClientSide());
      } else if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable && player instanceof ServerPlayer serverPlayer)) {
         return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      } else if (turntable.hasDisc()) {
         ejectDisc(level, pos, turntable);
         return ItemInteractionResult.sidedSuccess(level.isClientSide());
      } else {
         SongInfo songInfo = ItemMusicCD.getSongInfo(stack);
         if (songInfo == null) {
            player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.modern_turntable.need_cd").withStyle(ChatFormatting.RED));
            return ItemInteractionResult.FAIL;
         } else {
            turntable.setDisc(stack.copyWithCount(1));
            if (!player.isCreative()) {
               stack.shrink(1);
            }

            updateState(level, pos, turntable);
            turntable.startFromDisc(serverPlayer);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
         }
      }
   }

   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
      if (player.isShiftKeyDown()) {
         if (level.isClientSide()) {
            openClientScreen(pos);
            return InteractionResult.SUCCESS;
         } else {
            return InteractionResult.SUCCESS;
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   protected boolean hasAnalogOutputSignal(BlockState state) {
      return true;
   }

   protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
      return level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable ? turntable.getComparatorOutput() : 0;
   }

   private static void openClientScreen(BlockPos pos) {
      ModernTurntableClientHooks.openModernTurntableScreen(pos);
   }

   public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool) {
      if (!level.isClientSide() && blockEntity instanceof ModernTurntableBlockEntity turntable && turntable.hasDisc()) {
         popResource(level, pos, turntable.removeDiscForBlockRemoval());
      }

      super.playerDestroy(level, player, pos, state, blockEntity, tool);
   }

   public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
      if (level instanceof Level realLevel && !realLevel.isClientSide() && realLevel.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable) {
         turntable.stopPlaybackForBlockRemoval();
      }

      super.destroy(level, pos, state);
   }

   private static void ejectDisc(Level level, BlockPos pos, ModernTurntableBlockEntity turntable) {
      ItemStack removed = turntable.removeDisc();
      if (!removed.isEmpty()) {
         popResource(level, pos, removed);
      }

      updateState(level, pos, turntable);
   }

   private static void updateState(Level level, BlockPos pos, ModernTurntableBlockEntity turntable) {
      BlockState state = level.getBlockState(pos);
      if (state.getBlock() instanceof ModernTurntableBlock) {
         level.setBlock(pos, (BlockState)((BlockState)state.setValue(HAS_DISC, turntable.hasDisc())).setValue(PLAYING, turntable.isPlaying()), 3);
      }
   }

   public BlockState getStateForPlacement(BlockPlaceContext context) {
      return (BlockState)this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      builder.add(new Property[]{FACING, HAS_DISC, PLAYING});
   }

   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return SHAPE;
   }

   public RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
      return CODEC;
   }
}
