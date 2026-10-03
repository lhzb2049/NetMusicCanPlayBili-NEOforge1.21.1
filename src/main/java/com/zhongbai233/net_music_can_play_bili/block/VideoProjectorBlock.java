package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.VideoProjectorClient;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class VideoProjectorBlock extends Block implements EntityBlock {
   public static final EnumProperty<Direction> FACING = EnumProperty.create("facing", Direction.class, new Direction[]{Direction.UP, Direction.DOWN});
   public static final BooleanProperty ACTIVATED = BooleanProperty.create("activated");
   private static final MapCodec<VideoProjectorBlock> CODEC = simpleCodec(VideoProjectorBlock::new);
   private static final VoxelShape SHAPE = Block.box(2.75, 0.0, 2.75, 13.25, 5.3, 13.25);

   public VideoProjectorBlock(Properties properties) {
      super(properties);
      this.registerDefaultState((BlockState)((BlockState)((BlockState)this.stateDefinition.any()).setValue(FACING, Direction.UP)).setValue(ACTIVATED, false));
   }

   protected MapCodec<VideoProjectorBlock> codec() {
      return CODEC;
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> builder) {
      builder.add(new Property[]{FACING, ACTIVATED});
   }

   public BlockState getStateForPlacement(BlockPlaceContext context) {
      return (BlockState)this.defaultBlockState().setValue(FACING, Direction.UP);
   }

   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new VideoProjectorBlockEntity(pos, state);
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
         if (level.getBlockEntity(pos) instanceof VideoProjectorBlockEntity projector) {
            projector.linkTo(linkedPos);
            if (!(placer instanceof Player player && player.isCreative())) {
               LinkHelper.clearLinkFromItem(stack);
               clearLinkedBlockEntityData(stack);
            }
         }
      }
   }

   public static void writeLinkedBlockEntityData(ItemStack stack, BlockPos linkedPos) {
      CustomData existing = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      CompoundTag tag = existing != null ? existing.copyTag() : new CompoundTag();
      tag.putBoolean("LinkedTarget_has", true);
      tag.putInt("LinkedTarget_x", linkedPos.getX());
      tag.putInt("LinkedTarget_y", linkedPos.getY());
      tag.putInt("LinkedTarget_z", linkedPos.getZ());
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public static void clearLinkedBlockEntityData(ItemStack stack) {
      CustomData existing = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (existing != null) {
         CompoundTag tag = existing.copyTag();
         tag.remove("LinkedTarget_has");
         tag.remove("LinkedTarget_x");
         tag.remove("LinkedTarget_y");
         tag.remove("LinkedTarget_z");
         if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
         } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         }
      }
   }

   protected ItemInteractionResult useItemOn(
      ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult
   ) {
      if (hand == InteractionHand.MAIN_HAND && stack.getItem() instanceof MediaManagementToolItem) {
         return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      } else if (!player.mayBuild()) {
         return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
      } else {
         if (level.isClientSide()) {
            openClientScreen(pos);
         }

         return ItemInteractionResult.sidedSuccess(level.isClientSide());
      }
   }

   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
      if (!player.mayBuild()) {
         return InteractionResult.PASS;
      } else {
         if (level.isClientSide()) {
            openClientScreen(pos);
         }

         return InteractionResult.SUCCESS;
      }
   }

   private static void openClientScreen(BlockPos pos) {
      VideoProjectorClient.openScreen(pos);
   }
}
