package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.SpeakerClient;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SpeakerBlock extends Block implements EntityBlock {
   public static final BooleanProperty ACTIVATED = BooleanProperty.create("activated");
   public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
   private static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);
   private static final VoxelShape SHAPE = Block.box(3.0, 0.0, 3.0, 13.0, 10.0, 13.0);

   public SpeakerBlock(Properties properties) {
      super(properties.sound(SoundType.WOOD).strength(2.0F).lightLevel(state -> state.getValue(ACTIVATED) ? 10 : 0).noOcclusion());
      this.registerDefaultState((BlockState)((BlockState)((BlockState)this.stateDefinition.any()).setValue(ACTIVATED, false)).setValue(FACING, Direction.NORTH));
   }

   protected MapCodec<SpeakerBlock> codec() {
      return CODEC;
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      builder.add(new Property[]{ACTIVATED, FACING});
   }

   public BlockState getStateForPlacement(BlockPlaceContext ctx) {
      return (BlockState)this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
   }

   protected BlockState rotate(BlockState state, Rotation rotation) {
      return (BlockState)state.setValue(FACING, rotation.rotate((Direction)state.getValue(FACING)));
   }

   protected BlockState mirror(BlockState state, Mirror mirror) {
      return this.rotate(state, mirror.getRotation((Direction)state.getValue(FACING)));
   }

   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new SpeakerBlockEntity(pos, state);
   }

   protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return SHAPE;
   }

   protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
      super.setPlacedBy(level, pos, state, placer, stack);
      if (!level.isClientSide()) {
         applyLinkedPosition(level, pos, stack, placer);
      }
   }

   private static void applyLinkedPosition(Level level, BlockPos pos, ItemStack stack, LivingEntity placer) {
      BlockPos linkedPos = LinkHelper.readLinkFromItem(stack);
      if (linkedPos != null) {
         if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            speaker.linkTo(linkedPos);
            if (!(placer instanceof Player player && player.isCreative())) {
               LinkHelper.clearLinkFromItem(stack);
            }
         }
      }
   }

   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
      if (!player.mayBuild()) {
         return InteractionResult.PASS;
      } else {
         if (level.isClientSide()) {
            SpeakerClient.openScreen(pos);
         }

         return InteractionResult.SUCCESS;
      }
   }

   protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
      if (level instanceof ServerLevel serverLevel && (movedByPiston || !state.is(newState.getBlock()))) {
         AudioLinkIndex.removeSpeakerEndpoint(serverLevel, null, pos);
      }

      super.onRemove(state, level, pos, newState, movedByPiston);
   }
}
