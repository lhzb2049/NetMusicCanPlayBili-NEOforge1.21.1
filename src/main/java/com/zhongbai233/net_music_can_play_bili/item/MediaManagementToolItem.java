package com.zhongbai233.net_music_can_play_bili.item;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public class MediaManagementToolItem extends Item {
   private static final String TAG_TARGET_KIND = "media_tool_target_kind";
   private static final String TAG_MP4_DEVICE_ID = "media_tool_mp4_device_id";
   private static final String TARGET_KIND_MP4 = "mp4";

   public MediaManagementToolItem(Properties properties) {
      super(properties.stacksTo(1));
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack tool = player.getItemInHand(hand);
      if (hand != InteractionHand.MAIN_HAND) {
         return InteractionResultHolder.pass(tool);
      } else {
         ItemStack offhand = player.getOffhandItem();
         if (level.isClientSide()) {
            return InteractionResultHolder.success(tool);
         } else if (player instanceof ServerPlayer serverPlayer) {
            if (offhand.getItem() instanceof MP4Item) {
               UUID deviceId = MP4DeviceIdentity.getOrCreateUnique((ServerLevel)serverPlayer.level(), serverPlayer, offhand);
               if (deviceId == null) {
                  return InteractionResultHolder.pass(tool);
               } else if (player.isShiftKeyDown()) {
                  openReportMenu(serverPlayer, sourceListFor(PlaybackAuditManager.findMp4(serverPlayer.level().getServer(), deviceId), serverPlayer));
                  return InteractionResultHolder.success(tool);
               } else {
                  rememberMp4(tool, deviceId);
                  openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.MP4, null, null);
                  return InteractionResultHolder.success(tool);
               }
            } else if (offhand.getItem() instanceof PadItem) {
               UUID deviceId = PadItem.getOrCreateDeviceId(offhand);
               if (deviceId == null) {
                  return InteractionResultHolder.pass(tool);
               } else {
                  openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.PAD, null, deviceId);
                  return InteractionResultHolder.success(tool);
               }
            } else if (player.isShiftKeyDown()) {
               openReportMenu(serverPlayer, nearbyAudibleSources(serverPlayer));
               return InteractionResultHolder.success(tool);
            } else {
               openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.MP4, null, null);
               return InteractionResultHolder.success(tool);
            }
         } else {
            return InteractionResultHolder.pass(tool);
         }
      }
   }

   public InteractionResult useOn(UseOnContext context) {
      Player player = context.getPlayer();
      Level level = context.getLevel();
      if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
         return InteractionResult.PASS;
      } else if (level.isClientSide()) {
         return InteractionResult.SUCCESS;
      } else if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
         BlockPos pos = context.getClickedPos();
         if (player.isShiftKeyDown()) {
            openReportMenu(
               serverPlayer, sourceListFor(PlaybackAuditManager.findModernTurntable(serverPlayer.level().getServer(), serverLevel, pos), serverPlayer)
            );
            return InteractionResult.CONSUME;
         } else {
            BlockEntity be = level.getBlockEntity(pos);
            if (!(be instanceof ModernTurntableBlockEntity)) {
               player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.media_tool.unsupported_target").withStyle(ChatFormatting.RED));
               return InteractionResult.CONSUME;
            } else if (!player.mayBuild()) {
               return InteractionResult.PASS;
            } else {
               openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.TURNTABLE, pos, null);
               return InteractionResult.CONSUME;
            }
         }
      } else {
         return InteractionResult.PASS;
      }
   }

   private static void openBindingMenu(ServerPlayer player, MediaToolBindingMenu.TargetKind targetKind, BlockPos targetPos, UUID mp4DeviceId) {
      player.openMenu(new MediaManagementToolItem.MediaToolMenuProvider(targetKind, targetPos, mp4DeviceId));
   }

   private static void openReportMenu(ServerPlayer player, List<MediaToolReportMenu.ReportSourceInfo> sources) {
      if (sources != null && !sources.isEmpty()) {
         player.openMenu(new MediaManagementToolItem.MediaToolReportMenuProvider(sources));
      } else {
         player.sendSystemMessage(Component.translatable("message.net_music_can_play_bili.media_tool.no_active_source").withStyle(ChatFormatting.RED));
      }
   }

   private static List<MediaToolReportMenu.ReportSourceInfo> nearbyAudibleSources(ServerPlayer player) {
      return PlaybackAuditManager.snapshot(player.level().getServer())
         .stream()
         .filter(source -> player.level().dimension().equals(source.levelKey()))
         .filter(source -> source.sourcePos().distToCenterSqr(player.position()) <= 4096.0)
         .sorted(Comparator.comparingDouble(source -> source.sourcePos().distToCenterSqr(player.position())))
         .limit(12L)
         .map(source -> MediaToolReportMenu.fromActiveSource(player.level().getServer(), player, source))
         .toList();
   }

   private static List<MediaToolReportMenu.ReportSourceInfo> sourceListFor(PlaybackAuditManager.ActiveSource source, ServerPlayer player) {
      return source == null ? List.of() : List.of(MediaToolReportMenu.fromActiveSource(player.level().getServer(), player, source));
   }

   private static void rememberMp4(ItemStack tool, UUID deviceId) {
      tool.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
         tag.putString("media_tool_target_kind", "mp4");
         tag.putString("media_tool_mp4_device_id", deviceId.toString());
      }));
   }

   private static UUID readRememberedMp4(ItemStack tool) {
      CustomData customData = (CustomData)tool.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         CompoundTag tag = customData.copyTag();
         if (!"mp4".equals(LinkHelper.getStringOr(tag, "media_tool_target_kind", ""))) {
            return null;
         } else {
            String value = LinkHelper.getStringOr(tag, "media_tool_mp4_device_id", "");

            try {
               return value.isBlank() ? null : UUID.fromString(value);
            } catch (IllegalArgumentException var5) {
               return null;
            }
         }
      } else {
         return null;
      }
   }

   private static String shortUuid(UUID deviceId) {
      String value = deviceId != null ? deviceId.toString() : "";
      return value.length() > 8 ? value.substring(0, 8) : value;
   }

   public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag flag) {
      tooltipComponents.add(Component.translatable("tooltip.net_music_can_play_bili.media_tool.binding").withStyle(ChatFormatting.GRAY));
      tooltipComponents.add(Component.translatable("tooltip.net_music_can_play_bili.media_tool.report").withStyle(ChatFormatting.GRAY));
      UUID deviceId = readRememberedMp4(stack);
      if (deviceId != null) {
         tooltipComponents.add(
            Component.translatable("tooltip.net_music_can_play_bili.media_tool.mp4_target", new Object[]{shortUuid(deviceId)}).withStyle(ChatFormatting.GOLD)
         );
      }
   }

   private record MediaToolMenuProvider(MediaToolBindingMenu.TargetKind targetKind, BlockPos targetPos, UUID mp4DeviceId) implements MenuProvider {
      public Component getDisplayName() {
         return Component.translatable("gui.net_music_can_play_bili.media_tool_binding");
      }

      public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
         return new MediaToolBindingMenu(containerId, inventory, this.targetKind, this.targetPos, this.mp4DeviceId);
      }

      public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
         MediaToolBindingMenu.writeClientData(buffer, this.targetKind, this.targetPos, this.mp4DeviceId);
      }
   }

   private record MediaToolReportMenuProvider(List<MediaToolReportMenu.ReportSourceInfo> sources) implements MenuProvider {
      public Component getDisplayName() {
         return Component.translatable("gui.net_music_can_play_bili.media_tool_report");
      }

      public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
         return new MediaToolReportMenu(containerId, inventory, this.sources);
      }

      public void writeClientSideData(AbstractContainerMenu menu, RegistryFriendlyByteBuf buffer) {
         MediaToolReportMenu.writeClientData(buffer, this.sources);
      }
   }
}
