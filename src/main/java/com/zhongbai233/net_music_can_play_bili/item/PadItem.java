package com.zhongbai233.net_music_can_play_bili.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.client.PadClientHooks;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.media.sync.MonotonicMediaClock;
import com.zhongbai233.net_music_can_play_bili.network.PadDocumentStore;
import com.zhongbai233.net_music_can_play_bili.network.PadStateMirrorPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

public class PadItem extends Item {
   private static final String DATA_DEVICE_ID = "pad_device_id";
   private static final String DATA_TITLE = "pad_title";
   private static final String DATA_AUTHOR = "pad_author";
   private static final String DATA_LOCKED = "pad_locked";
   private static final String DATA_UPDATED_AT = "pad_updated_at";
   private static final String DATA_SEQUENCE = "pad_sequence";
   private static final String DATA_MEDIA = "pad_media_entries";
   private static final String DATA_MEDIA_ID = "id";
   private static final String DATA_MEDIA_STACK = "stack";
   private static final String DATA_POINTS = "pad_trigger_points";
   private static final String DATA_POINT_ID = "id";
   private static final String DATA_POINT_NAME = "name";
   private static final String DATA_POINT_X = "x";
   private static final String DATA_POINT_Y = "y";
   private static final String DATA_POINT_Z = "z";
   private static final String DATA_POINT_RADIUS = "radius";
   private static final String DATA_POINT_MEDIA_ID = "mediaId";
   private static final String DATA_POINT_MODE = "mode";
   private static final String DATA_POINT_LOOP = "loop";
   private static final String DATA_POINT_VOLUME = "volume";
   private static final String DATA_POINT_VISIBLE = "visible";

   public PadItem(Properties properties) {
      super(properties.stacksTo(1));
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (hand != InteractionHand.OFF_HAND && (hand != InteractionHand.MAIN_HAND || !(player.getOffhandItem().getItem() instanceof PadItem))) {
         if (!level.isClientSide()) {
            getOrCreateDeviceId(stack);
         } else {
            PadClientHooks.openFocusScreen(hand);
         }

         return InteractionResultHolder.consume(stack);
      } else {
         return InteractionResultHolder.pass(stack);
      }
   }

   public static UUID readDeviceId(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         String value = LinkHelper.getStringOr(customData.copyTag(), "pad_device_id", "");
         if (value.isBlank()) {
            return null;
         } else {
            try {
               return UUID.fromString(value);
            } catch (IllegalArgumentException var4) {
               return null;
            }
         }
      } else {
         return null;
      }
   }

   public static UUID getOrCreateDeviceId(ItemStack stack) {
      UUID existing = readDeviceId(stack);
      if (existing != null) {
         return existing;
      } else {
         UUID created = UUID.randomUUID();
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.putString("pad_device_id", created.toString())));
         return created;
      }
   }

   public static void writeDeviceId(ItemStack stack, UUID deviceId) {
      if (isPad(stack) && deviceId != null) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.putString("pad_device_id", deviceId.toString())));
      }
   }

   public static ItemStack findByDeviceId(Player player, UUID deviceId) {
      if (player != null && deviceId != null) {
         List<ItemStack> stacks = findAllByDeviceId(player, deviceId);
         return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static List<ItemStack> findAllByDeviceId(Player player, UUID deviceId) {
      List<ItemStack> matches = new ArrayList<>();
      if (player != null && deviceId != null) {
         ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
         if (deviceId.equals(readDeviceId(carried))) {
            matches.add(carried);
         }

         if (deviceId.equals(readDeviceId(player.getMainHandItem()))) {
            matches.add(player.getMainHandItem());
         }

         if (deviceId.equals(readDeviceId(player.getOffhandItem()))) {
            matches.add(player.getOffhandItem());
         }

         Inventory inventory = player.getInventory();

         for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (deviceId.equals(readDeviceId(stack))) {
               matches.add(stack);
            }
         }

         return matches;
      } else {
         return matches;
      }
   }

   public static void writeDocumentToUnlockedCopies(Player player, UUID deviceId, PadDocument document) {
      if (player != null && deviceId != null && document != null) {
         for (ItemStack stack : findAllByDeviceId(player, deviceId)) {
            if (isPad(stack) && !readLocked(stack)) {
               writeDeviceId(stack, deviceId);
               writeDocument(stack, document.copyWithLocked(false));
            }
         }
      }
   }

   public static void writeDocumentToLockedCopies(Player player, UUID deviceId, PadDocument document) {
      if (player != null && deviceId != null && document != null) {
         for (ItemStack stack : findAllByDeviceId(player, deviceId)) {
            if (isPad(stack) && readLocked(stack)) {
               writeDeviceId(stack, deviceId);
               writeDocument(stack, document.copyWithLocked(true));
            }
         }
      }
   }

   public static boolean isPad(ItemStack stack) {
      return !stack.isEmpty() && stack.getItem() instanceof PadItem;
   }

   public static boolean isNetMusicDisc(ItemStack stack) {
      return !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
   }

   public static PadDocument readDocument(ItemStack stack) {
      PadDocument legacy = readLegacyDocument(stack);
      return legacy != null ? legacy : PadDocument.DEFAULT.copyWithLocked(readLocked(stack));
   }

   public static PadDocument readLegacyDocument(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         CompoundTag tag = customData.copyTag();
         if (!hasLegacyDocument(tag)) {
            return null;
         } else {
            List<PadMediaEntry> media = readMediaEntries(tag);
            List<PadTriggerPoint> points = readTriggerPoints(tag);
            return new PadDocument(
               LinkHelper.getStringOr(tag, "pad_title", ""),
               LinkHelper.getStringOr(tag, "pad_author", ""),
               LinkHelper.getBooleanOr(tag, "pad_locked", false),
               LinkHelper.getLongOr(tag, "pad_updated_at", 0L),
               LinkHelper.getLongOr(tag, "pad_sequence", 0L),
               null,
               media,
               points
            );
         }
      } else {
         return null;
      }
   }

   public static void writeDocument(ItemStack stack, PadDocument document) {
      if (isPad(stack) && document != null) {
         writeLegacyDocument(stack, document);
      }
   }

   public static boolean readLocked(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return customData != null && !customData.isEmpty() && LinkHelper.getBooleanOr(customData.copyTag(), "pad_locked", false);
   }

   public static void writeLocked(ItemStack stack, boolean locked) {
      if (isPad(stack)) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.putBoolean("pad_locked", locked)));
      }
   }

   public static void stripDocumentData(ItemStack stack) {
      if (isPad(stack)) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(PadItem::removeDocumentData));
      }
   }

   private static boolean hasLegacyDocument(CompoundTag tag) {
      return tag.contains("pad_media_entries")
         || tag.contains("pad_trigger_points")
         || tag.contains("pad_title")
         || tag.contains("pad_author")
         || tag.contains("pad_updated_at")
         || tag.contains("pad_sequence");
   }

   private static void removeDocumentData(CompoundTag tag) {
      tag.remove("pad_title");
      tag.remove("pad_author");
      tag.remove("pad_updated_at");
      tag.remove("pad_sequence");
      tag.remove("pad_media_entries");
      tag.remove("pad_trigger_points");
   }

   public static boolean addDisc(ItemStack padStack, ItemStack discStack) {
      if (isPad(padStack) && isNetMusicDisc(discStack)) {
         PadDocument document = readDocument(padStack);
         if (!document.locked() && document.nextFreeMediaId() >= 0) {
            writeDocument(padStack, document.withAddedMedia(BiliSongInfoSanitizer.sanitizeDisc(discStack)));
            discStack.shrink(1);
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean addDisc(ServerPlayer player, ItemStack padStack, ItemStack discStack) {
      if (isPad(padStack) && isNetMusicDisc(discStack) && player.level() instanceof ServerLevel level) {
         UUID var7 = getOrCreateDeviceId(padStack);
         PadDocument document = PadDocumentStore.getOrCreate(level, var7, padStack).copyWithLocked(readLocked(padStack));
         if (!document.locked() && document.nextFreeMediaId() >= 0) {
            PadDocument updated = document.withAddedMedia(BiliSongInfoSanitizer.sanitizeDisc(discStack)).copyWithLocked(false);
            PadDocumentStore.update(level, var7, updated);
            writeDocument(padStack, updated);
            discStack.shrink(1);
            syncDocumentToPlayer(player, var7, updated);
            return true;
         } else {
            return false;
         }
      } else {
         return addDisc(padStack, discStack);
      }
   }

   public static ItemStack removeLastDisc(ItemStack padStack) {
      if (!isPad(padStack)) {
         return ItemStack.EMPTY;
      } else {
         PadDocument document = readDocument(padStack);
         if (!document.locked() && !document.mediaEntries().isEmpty()) {
            PadMediaEntry last = document.mediaEntries().get(document.mediaEntries().size() - 1);
            writeDocument(padStack, document.withRemovedMedia(last.mediaId()));
            return last.disc().copyWithCount(1);
         } else {
            return ItemStack.EMPTY;
         }
      }
   }

   private static ItemStack removeLastDisc(ServerPlayer player, ItemStack padStack) {
      if (isPad(padStack) && player.level() instanceof ServerLevel level) {
         UUID var7 = getOrCreateDeviceId(padStack);
         PadDocument document = PadDocumentStore.getOrCreate(level, var7, padStack).copyWithLocked(readLocked(padStack));
         if (!document.locked() && !document.mediaEntries().isEmpty()) {
            PadMediaEntry last = document.mediaEntries().get(document.mediaEntries().size() - 1);
            PadDocument updated = document.withRemovedMedia(last.mediaId()).copyWithLocked(false);
            PadDocumentStore.update(level, var7, updated);
            writeDocument(padStack, updated);
            syncDocumentToPlayer(player, var7, updated);
            return last.disc().copyWithCount(1);
         } else {
            return ItemStack.EMPTY;
         }
      } else {
         return removeLastDisc(padStack);
      }
   }

   private static void syncDocumentToPlayer(ServerPlayer player, UUID deviceId, PadDocument document) {
      if (player != null && deviceId != null && document != null) {
         PacketDistributor.sendToPlayer(player, new PadStateMirrorPacket(deviceId, document, MonotonicMediaClock.nowTick()), new CustomPacketPayload[0]);
      }
   }

   public boolean overrideStackedOnOther(ItemStack padStack, Slot slot, ClickAction action, Player player) {
      if (action != ClickAction.PRIMARY) {
         if (action == ClickAction.SECONDARY && !slot.hasItem()) {
            if (player instanceof ServerPlayer serverPlayer) {
               ItemStack removed = removeLastDisc(serverPlayer, padStack);
               if (removed.isEmpty()) {
                  return false;
               }

               slot.set(removed);
               slot.setChanged();
               return true;
            }

            ItemStack removed = removeLastDisc(padStack);
            if (!removed.isEmpty()) {
               slot.set(removed);
               slot.setChanged();
               return true;
            }
         }

         return false;
      } else {
         ItemStack slotStack = slot.getItem();
         if (player instanceof ServerPlayer serverPlayer && addDisc(serverPlayer, padStack, slotStack)
            || !(player instanceof ServerPlayer) && addDisc(padStack, slotStack)) {
            if (slotStack.isEmpty()) {
               slot.set(ItemStack.EMPTY);
            }

            slot.setChanged();
            return true;
         } else {
            return false;
         }
      }
   }

   public boolean overrideOtherStackedOnMe(ItemStack padStack, ItemStack carriedStack, Slot slot, ClickAction action, Player player, SlotAccess carriedAccess) {
      if (action != ClickAction.PRIMARY
         || !(player instanceof ServerPlayer serverPlayer && addDisc(serverPlayer, padStack, carriedStack))
            && (player instanceof ServerPlayer || !addDisc(padStack, carriedStack))) {
         if (action == ClickAction.SECONDARY && carriedStack.isEmpty()) {
            if (player instanceof ServerPlayer serverPlayerx) {
               ItemStack removed = removeLastDisc(serverPlayerx, padStack);
               if (removed.isEmpty()) {
                  return false;
               }

               carriedAccess.set(removed);
               return true;
            }

            ItemStack removed = removeLastDisc(padStack);
            if (!removed.isEmpty()) {
               carriedAccess.set(removed);
               return true;
            }
         }

         return false;
      } else {
         carriedAccess.set(carriedStack);
         return true;
      }
   }

   public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag flag) {
      PadDocument document = readDocument(stack);
      tooltipComponents.add(
         Component.translatable("tooltip.net_music_can_play_bili.pad.summary", new Object[]{document.mediaEntries().size(), document.triggerPoints().size()})
            .withStyle(ChatFormatting.GRAY)
      );
      tooltipComponents.add(
         Component.translatable(document.locked() ? "tooltip.net_music_can_play_bili.pad.locked" : "tooltip.net_music_can_play_bili.pad.draft")
            .withStyle(document.locked() ? ChatFormatting.GOLD : ChatFormatting.GREEN)
      );
      tooltipComponents.add(Component.translatable("tooltip.net_music_can_play_bili.pad.playback_target").withStyle(ChatFormatting.DARK_AQUA));
   }

   private static List<PadMediaEntry> readMediaEntries(CompoundTag tag) {
      List<PadMediaEntry> result = new ArrayList<>();

      for (Tag entry : LinkHelper.childrenListOrEmpty(tag, "pad_media_entries")) {
         if (entry instanceof CompoundTag compound) {
            int id = LinkHelper.getIntOr(compound, "id", 0);
            Tag stackTag = compound.get("stack");
            if (stackTag != null) {
               ItemStack.OPTIONAL_CODEC
                  .parse(NbtOps.INSTANCE, stackTag)
                  .result()
                  .filter(PadItem::isNetMusicDisc)
                  .ifPresent(stack -> result.add(new PadMediaEntry(id, BiliSongInfoSanitizer.sanitizeDisc(stack))));
            }
         }
      }

      return result;
   }

   private static void writeLegacyDocument(ItemStack stack, PadDocument document) {
      if (isPad(stack) && document != null) {
         stack.update(
            DataComponents.CUSTOM_DATA,
            CustomData.EMPTY,
            customData -> customData.update(
               tag -> {
                  tag.putString("pad_title", document.title());
                  tag.putString("pad_author", document.author());
                  tag.putBoolean("pad_locked", document.locked());
                  tag.putLong("pad_updated_at", document.updatedAtMillis());
                  tag.putLong("pad_sequence", document.sequence());
                  ListTag media = new ListTag();

                  for (PadMediaEntry entry : document.mediaEntries()) {
                     if (entry != null && isNetMusicDisc(entry.disc())) {
                        CompoundTag compound = new CompoundTag();
                        compound.putInt("id", entry.mediaId());
                        ItemStack sanitized = BiliSongInfoSanitizer.sanitizeDisc(entry.disc());
                        ItemStack.OPTIONAL_CODEC
                           .encodeStart(NbtOps.INSTANCE, sanitized)
                           .resultOrPartial(error -> {})
                           .ifPresent(encoded -> compound.put("stack", encoded));
                        media.add(compound);
                     }
                  }

                  tag.put("pad_media_entries", media);
                  ListTag points = new ListTag();

                  for (PadTriggerPoint point : document.triggerPoints()) {
                     if (point != null) {
                        CompoundTag compound = new CompoundTag();
                        compound.putString("id", point.pointId().toString());
                        compound.putString("name", point.name());
                        compound.putDouble("x", point.x());
                        compound.putDouble("y", point.y());
                        compound.putDouble("z", point.z());
                        compound.putInt("radius", point.radiusBlocks());
                        compound.putInt("mediaId", point.mediaId());
                        compound.putString("mode", point.triggerMode().name());
                        compound.putBoolean("loop", point.loop());
                        compound.putInt("volume", point.volumePerMille());
                        compound.putBoolean("visible", point.visible());
                        points.add(compound);
                     }
                  }

                  tag.put("pad_trigger_points", points);
               }
            )
         );
      }
   }

   private static List<PadTriggerPoint> readTriggerPoints(CompoundTag tag) {
      List<PadTriggerPoint> result = new ArrayList<>();

      for (Tag entry : LinkHelper.childrenListOrEmpty(tag, "pad_trigger_points")) {
         if (entry instanceof CompoundTag compound) {
            UUID pointId = parseUuid(LinkHelper.getStringOr(compound, "id", ""));
            result.add(
               new PadTriggerPoint(
                  pointId,
                  LinkHelper.getStringOr(compound, "name", ""),
                  LinkHelper.getDoubleOr(compound, "x", 0.0),
                  LinkHelper.getDoubleOr(compound, "y", 0.0),
                  LinkHelper.getDoubleOr(compound, "z", 0.0),
                  LinkHelper.getIntOr(compound, "radius", 8),
                  LinkHelper.getIntOr(compound, "mediaId", 0),
                  PadTriggerMode.byName(LinkHelper.getStringOr(compound, "mode", "")),
                  LinkHelper.getBooleanOr(compound, "loop", false),
                  LinkHelper.getIntOr(compound, "volume", 1000),
                  LinkHelper.getBooleanOr(compound, "visible", true)
               )
            );
         }
      }

      return result;
   }

   private static UUID parseUuid(String value) {
      try {
         return value != null && !value.isBlank() ? UUID.fromString(value) : UUID.randomUUID();
      } catch (IllegalArgumentException var2) {
         return UUID.randomUUID();
      }
   }
}
