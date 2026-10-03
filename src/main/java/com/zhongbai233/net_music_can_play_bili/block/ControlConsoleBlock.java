package com.zhongbai233.net_music_can_play_bili.block;

import com.mojang.serialization.MapCodec;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ControlConsoleBlock extends Block implements EntityBlock {
   private static final MapCodec<ControlConsoleBlock> CODEC = simpleCodec(ControlConsoleBlock::new);
   private static final VoxelShape SELECTION_SHAPE = Block.box(-1.0, 0.0, 0.0, 18.0, 29.0, 16.0);
   private static final VoxelShape COLLISION_SHAPE = Shapes.or(
      Block.box(1.0, 0.0, 1.0, 15.0, 4.0, 15.0),
      new VoxelShape[]{Block.box(7.0, 4.0, 7.0, 9.0, 29.0, 9.0), Block.box(3.0, 18.0, 7.0, 13.0, 27.0, 9.0), Block.box(5.0, 25.0, 0.0, 11.0, 27.0, 7.0)}
   );

   public ControlConsoleBlock(Properties properties) {
      super(properties.strength(2.5F).noOcclusion());
   }

   protected MapCodec<? extends Block> codec() {
      return CODEC;
   }

   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new ControlConsoleBlockEntity(pos, state);
   }

   public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
      super.setPlacedBy(level, pos, state, placer, stack);
      if (!level.isClientSide()) {
         if (placer instanceof Player player && level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
            console.claimIfUnowned(player.getUUID());
         }

         LinkHelper.ControlConsoleLink link = LinkHelper.readControlConsoleLinkFromItem(stack);
         if (link != null) {
            BlockPos sourcePos = link.pos();
            BlockEntity source = level.getBlockEntity(sourcePos);
            String currentDimension = level.dimension().location().toString();
            boolean correctDimension = link.legacy() || currentDimension.equals(link.dimension());
            boolean validSource = link.legacy()
               ? source instanceof ModernTurntableBlockEntity || source instanceof LiveStreamerBlockEntity
               : (
                  link.sourceKind() == LinkHelper.ControlConsoleSourceKind.TURNTABLE
                     ? source instanceof ModernTurntableBlockEntity
                     : source instanceof LiveStreamerBlockEntity
               );
            if (correctDimension && validSource && level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
               console.linkTo(
                  currentDimension,
                  sourcePos,
                  link.sourceKind() == LinkHelper.ControlConsoleSourceKind.LIVE_STREAMER
                     ? ControlConsoleDocument.SourceKind.LIVE_STREAMER
                     : ControlConsoleDocument.SourceKind.TURNTABLE
               );
               if (!(placer instanceof Player player && player.isCreative())) {
                  LinkHelper.clearLinkFromItem(stack);
               }
            } else if (placer instanceof Player player) {
               player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.control_console.invalid_source"));
            }
         }
      }
   }

   public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return SELECTION_SHAPE;
   }

   protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return COLLISION_SHAPE;
   }

   public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
      BlockState above = level.getBlockState(pos.above());
      return above.canBeReplaced() || above.isAir();
   }

   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
      if (!player.mayBuild() && !player.createCommandSourceStack().hasPermission(2)) {
         return InteractionResult.FAIL;
      } else if (level.getBlockEntity(pos) instanceof ControlConsoleBlockEntity console) {
         if (!level.isClientSide()) {
            if (player instanceof ServerPlayer serverPlayer) {
               console.claimIfUnowned(serverPlayer.getUUID());
               return console.canEdit(serverPlayer) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            } else {
               return InteractionResult.FAIL;
            }
         } else {
            ControlConsoleClient.openScreen(pos);
            return InteractionResult.SUCCESS;
         }
      } else {
         return InteractionResult.FAIL;
      }
   }

   protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }
}
