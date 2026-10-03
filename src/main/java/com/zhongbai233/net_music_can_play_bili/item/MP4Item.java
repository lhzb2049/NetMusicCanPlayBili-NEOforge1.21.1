package com.zhongbai233.net_music_can_play_bili.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.github.tartaricacid.netmusic.item.ItemMusicCD.SongInfo;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.client.MP4ClientHooks;
import com.zhongbai233.net_music_can_play_bili.client.tooltip.MP4QueueTooltip;
import com.zhongbai233.net_music_can_play_bili.link.LinkHelper;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceStateStore;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public class MP4Item extends Item {
   private static final String DATA_DEVICE_ID = "mp4_device_id";
   private static final String DATA_QUEUE = "mp4_queue";
   private static final String DATA_QUEUE_STACK = "stack";
   public static final int MAX_QUEUE_SIZE = 18;

   public MP4Item(Properties properties) {
      super(properties.stacksTo(1));
   }

   public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
      ItemStack stack = player.getItemInHand(hand);
      if (hand != InteractionHand.OFF_HAND && (hand != InteractionHand.MAIN_HAND || !(player.getOffhandItem().getItem() instanceof MP4Item))) {
         if (!level.isClientSide()) {
            MP4DeviceIdentity.getOrCreateUnique((ServerLevel)level, (ServerPlayer)player, stack);
         }

         if (level.isClientSide()) {
            MP4ClientHooks.openFocusScreen(hand);
         }

         return InteractionResultHolder.consume(stack);
      } else {
         return InteractionResultHolder.pass(stack);
      }
   }

   public static boolean isNetMusicDisc(ItemStack stack) {
      return !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
   }

   public static ItemStack findPlayableInInventory(Player player) {
      return findInInventory(player, true);
   }

   public static ItemStack findAnyInInventory(Player player) {
      return findInInventory(player, false);
   }

   public static ItemStack findByDeviceId(Player player, UUID deviceId) {
      if (player != null && deviceId != null) {
         ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
         if (carried.getItem() instanceof MP4Item && deviceId.equals(readDeviceId(carried))) {
            return carried;
         } else {
            Inventory inventory = player.getInventory();

            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
               ItemStack stack = inventory.getItem(slot);
               if (stack.getItem() instanceof MP4Item && deviceId.equals(readDeviceId(stack))) {
                  return stack;
               }
            }

            return ItemStack.EMPTY;
         }
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static UUID readDeviceId(ItemStack stack) {
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         String value = LinkHelper.getStringOr(customData.copyTag(), "mp4_device_id", "");
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
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.putString("mp4_device_id", created.toString())));
         return created;
      }
   }

   public static void writeDeviceId(ItemStack stack, UUID deviceId) {
      if (!stack.isEmpty() && stack.getItem() instanceof MP4Item && deviceId != null) {
         stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> tag.putString("mp4_device_id", deviceId.toString())));
      }
   }

   private static ItemStack findInInventory(Player player, boolean preferPlaying) {
      if (player == null) {
         return ItemStack.EMPTY;
      } else {
         ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
         if (!(carried.getItem() instanceof MP4Item) || preferPlaying && !isPlaying(player, carried)) {
            ItemStack fallback = carried.getItem() instanceof MP4Item ? carried : ItemStack.EMPTY;
            ItemStack mainHand = player.getMainHandItem();
            if (!(mainHand.getItem() instanceof MP4Item) || preferPlaying && !isPlaying(player, mainHand)) {
               if (fallback.isEmpty() && mainHand.getItem() instanceof MP4Item) {
                  fallback = mainHand;
               }

               Inventory inventory = player.getInventory();

               for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                  ItemStack stack = inventory.getItem(slot);
                  if (stack.getItem() instanceof MP4Item) {
                     if (preferPlaying && isPlaying(player, stack)) {
                        return stack;
                     }

                     if (fallback.isEmpty()) {
                        fallback = stack;
                     }
                  }
               }

               return fallback;
            } else {
               return mainHand;
            }
         } else {
            return carried;
         }
      }
   }

   private static boolean isPlaying(Player player, ItemStack stack) {
      UUID deviceId = readDeviceId(stack);
      if (deviceId == null) {
         return false;
      } else {
         return player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level
            ? MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state().playing()
            : MP4DeviceStateStore.get(deviceId).state().playing();
      }
   }

   public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
      List<ItemStack> queue = readQueue(stack);
      if (queue.isEmpty()) {
         return Optional.empty();
      } else {
         List<String> titles = new ArrayList<>(queue.size());

         for (ItemStack disc : queue) {
            SongInfo songInfo = ItemMusicCD.getSongInfo(disc);
            if (songInfo != null) {
               titles.add(songInfo.songName != null && !songInfo.songName.isBlank() ? songInfo.songName : "NetMusic 唱片 " + (titles.size() + 1));
            }
         }

         return titles.isEmpty() ? Optional.empty() : Optional.of(new MP4QueueTooltip(titles, MP4ClientHooks.selectedQueueIndex(stack)));
      }
   }

   public boolean overrideStackedOnOther(ItemStack mp4Stack, Slot slot, ClickAction action, Player player) {
      if (action == ClickAction.PRIMARY) {
         ItemStack slotStack = slot.getItem();
         if (isNetMusicDisc(slotStack) && addDisc(mp4Stack, slotStack)) {
            syncQueueCopy(player, mp4Stack);
            if (slotStack.isEmpty()) {
               slot.set(ItemStack.EMPTY);
            }

            slot.setChanged();
            return true;
         } else {
            return false;
         }
      } else {
         if (action == ClickAction.SECONDARY && !slot.hasItem()) {
            ItemStack removed = removeSelectedOrLastDisc(mp4Stack, player);
            if (!removed.isEmpty()) {
               syncQueueCopy(player, mp4Stack);
               slot.set(removed);
               slot.setChanged();
               return true;
            }
         }

         return false;
      }
   }

   public boolean overrideOtherStackedOnMe(ItemStack mp4Stack, ItemStack carriedStack, Slot slot, ClickAction action, Player player, SlotAccess carriedAccess) {
      if (action == ClickAction.PRIMARY && isNetMusicDisc(carriedStack) && addDisc(mp4Stack, carriedStack)) {
         syncQueueCopy(player, mp4Stack);
         carriedAccess.set(carriedStack);
         return true;
      } else {
         if (action == ClickAction.SECONDARY && carriedStack.isEmpty()) {
            ItemStack removed = removeSelectedOrLastDisc(mp4Stack, player);
            if (!removed.isEmpty()) {
               syncQueueCopy(player, mp4Stack);
               carriedAccess.set(removed);
               return true;
            }
         }

         return false;
      }
   }

   public static List<ItemStack> readQueue(ItemStack stack) {
      NonNullList<ItemStack> result = NonNullList.create();
      CustomData customData = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (customData != null && !customData.isEmpty()) {
         for (Tag entry : LinkHelper.childrenListOrEmpty(customData.copyTag(), "mp4_queue")) {
            if (entry instanceof CompoundTag compound) {
               Tag stackTag = compound.get("stack");
               if (stackTag != null) {
                  ItemStack.OPTIONAL_CODEC
                     .parse(NbtOps.INSTANCE, stackTag)
                     .result()
                     .filter(MP4Item::isNetMusicDisc)
                     .ifPresent(itemStack -> result.add(BiliSongInfoSanitizer.sanitizeDisc(itemStack)));
               }

               if (result.size() >= 18) {
                  break;
               }
            }
         }

         return result;
      } else {
         return result;
      }
   }

   public static int queueSize(ItemStack stack) {
      return readQueue(stack).size();
   }

   public static boolean addDisc(ItemStack mp4Stack, ItemStack discStack) {
      if (!mp4Stack.isEmpty() && mp4Stack.getItem() instanceof MP4Item && isNetMusicDisc(discStack)) {
         List<ItemStack> queue = readQueue(mp4Stack);
         if (queue.size() >= 18) {
            return false;
         } else {
            queue.add(BiliSongInfoSanitizer.sanitizeDisc(discStack));
            writeQueue(mp4Stack, queue);
            discStack.shrink(1);
            return true;
         }
      } else {
         return false;
      }
   }

   public static ItemStack removeSelectedOrLastDisc(ItemStack mp4Stack) {
      return removeQueueDisc(mp4Stack, -1);
   }

   private static ItemStack removeSelectedOrLastDisc(ItemStack mp4Stack, Player player) {
      int selectedIndex = selectedQueueIndexFor(player, mp4Stack);
      return removeQueueDisc(mp4Stack, selectedIndex);
   }

   private static ItemStack removeQueueDisc(ItemStack mp4Stack, int preferredIndex) {
      List<ItemStack> queue = readQueue(mp4Stack);
      if (queue.isEmpty()) {
         return ItemStack.EMPTY;
      } else {
         int index = preferredIndex >= 0 && preferredIndex < queue.size() ? preferredIndex : queue.size() - 1;
         ItemStack removed = queue.remove(index).copyWithCount(1);
         writeQueue(mp4Stack, queue);
         return removed;
      }
   }

   private static int selectedQueueIndexFor(Player player, ItemStack mp4Stack) {
      UUID deviceId = readDeviceId(mp4Stack);
      if (deviceId == null) {
         return MP4ClientHooks.selectedQueueIndex(mp4Stack);
      } else {
         return player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level
            ? MP4DeviceStateStore.getOrCreate(level, deviceId, mp4Stack).state().selectedQueueIndex()
            : MP4ClientHooks.selectedQueueIndex(mp4Stack);
      }
   }

   private static void writeQueue(ItemStack stack, List<ItemStack> queue) {
      ListTag listTag = new ListTag();

      for (ItemStack disc : queue) {
         if (isNetMusicDisc(disc)) {
            CompoundTag entry = new CompoundTag();
            ItemStack sanitized = BiliSongInfoSanitizer.sanitizeDisc(disc);
            ItemStack.OPTIONAL_CODEC.encodeStart(NbtOps.INSTANCE, sanitized).resultOrPartial(error -> {}).ifPresent(encoded -> entry.put("stack", encoded));
            listTag.add(entry);
         }
      }

      stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, existing -> existing.update(tag -> tag.put("mp4_queue", listTag)));
   }

   private static void syncQueueCopy(Player player, ItemStack mp4Stack) {
      if (player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level && mp4Stack.getItem() instanceof MP4Item) {
         UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, serverPlayer, mp4Stack);
         if (deviceId != null) {
            MP4DeviceStateStore.syncQueueCopy(level, deviceId, mp4Stack);
            MP4PlaybackSyncManager.reconcileQueueChange(serverPlayer, deviceId, readQueue(mp4Stack));
         }
      }
   }

   public record State(
      boolean playing,
      boolean shuffle,
      boolean videoEnabled,
      boolean landscape,
      int qualityIndex,
      int selectedQueueIndex,
      int queueScrollOffset,
      int volumePerMille,
      int repeatMode,
      boolean playlistOpen,
      boolean lyricsEnabled,
      int subtitleMode,
      boolean subtitleAiEnabled,
      int progressPerMille,
      boolean rotationHintShown
   ) {
      public static final int MAX_QUALITY_INDEX = 7;
      public static final int MAX_QUEUE_INDEX = 17;
      public static final int MAX_QUEUE_SCROLL_OFFSET = 15;
      public static final MP4Item.State DEFAULT = new MP4Item.State(false, false, true, false, 5, 0, 0, 1000, 0, false, false, 0, false, 0, false);

      public boolean videoDecodeEnabled() {
         return this.playing && this.videoEnabled && this.landscape;
      }

      public int videoQualityCeiling() {
         return switch (this.qualityIndex) {
            case 0 -> 127;
            case 1 -> 120;
            case 2 -> 116;
            case 3 -> 112;
            case 4 -> 80;
            case 5 -> 64;
            case 6 -> 32;
            default -> 16;
         };
      }
   }
}
