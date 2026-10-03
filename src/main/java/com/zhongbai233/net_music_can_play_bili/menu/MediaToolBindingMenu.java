package com.zhongbai233.net_music_can_play_bili.menu;

import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

public class MediaToolBindingMenu extends AbstractContainerMenu {
   public static final int TARGET_SLOT = 0;
   public static final int INPUT_SLOT = 1;
   public static final int OUTPUT_SLOT = 2;
   private final SimpleContainer toolContainer = new SimpleContainer(3);
   private final MediaToolBindingMenu.TargetKind targetKind;
   private final BlockPos targetPos;
   private final UUID mediaDeviceId;
   private int headphoneBindingCount;
   private int holographicBindingCount;
   private boolean confirmed;
   private final Player owner;

   public MediaToolBindingMenu(int containerId, Inventory inventory) {
      this(containerId, inventory, MediaToolBindingMenu.TargetKind.MP4, null, null);
   }

   public MediaToolBindingMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
      this(containerId, inventory, readTargetKind(buffer), readTargetPos(buffer), readMp4DeviceId(buffer));
   }

   public MediaToolBindingMenu(int containerId, Inventory inventory, MediaToolBindingMenu.TargetKind targetKind, BlockPos targetPos, UUID mp4DeviceId) {
      super((MenuType)ModMenus.MEDIA_TOOL_BINDING.get(), containerId);
      this.targetKind = targetKind != null ? targetKind : MediaToolBindingMenu.TargetKind.MP4;
      this.targetPos = targetPos != null ? targetPos.immutable() : null;
      this.mediaDeviceId = mp4DeviceId;
      this.owner = inventory.player;
      if (!this.usesManualMp4TargetSlot()) {
         this.toolContainer.setItem(0, this.targetIcon());
      }

      this.addSlot(
         (Slot)(this.usesManualMp4TargetSlot()
            ? new MediaToolBindingMenu.Mp4TargetSlot(this.toolContainer, 0, 43, 43, this)
            : new MediaToolBindingMenu.LockedSlot(this.toolContainer, 0, 43, 43))
      );
      this.addSlot(new MediaToolBindingMenu.EquipmentInputSlot(this.toolContainer, 1, 155, 43));
      this.addSlot(new MediaToolBindingMenu.OutputSlot(this.toolContainer, 2, 155, 85));
      this.addPlayerInventory(inventory, 22, 120);
      this.addDataSlot(new DataSlot() {
         public int get() {
            return MediaToolBindingMenu.this.headphoneBindingCount;
         }

         public void set(int value) {
            MediaToolBindingMenu.this.headphoneBindingCount = value;
         }
      });
      this.addDataSlot(new DataSlot() {
         public int get() {
            return MediaToolBindingMenu.this.holographicBindingCount;
         }

         public void set(int value) {
            MediaToolBindingMenu.this.holographicBindingCount = value;
         }
      });
      this.refreshTargetBindingStats(inventory.player);
   }

   public MediaToolBindingMenu.TargetKind targetKind() {
      return this.targetKind;
   }

   public BlockPos targetPos() {
      return this.targetPos;
   }

   public UUID mp4DeviceId() {
      return this.mediaDeviceId;
   }

   public boolean usesManualMp4TargetSlot() {
      return (this.targetKind == MediaToolBindingMenu.TargetKind.MP4 || this.targetKind == MediaToolBindingMenu.TargetKind.PAD) && this.mediaDeviceId == null;
   }

   public static void writeClientData(RegistryFriendlyByteBuf buffer, MediaToolBindingMenu.TargetKind targetKind, BlockPos targetPos, UUID mp4DeviceId) {
      MediaToolBindingMenu.TargetKind safeKind = targetKind != null ? targetKind : MediaToolBindingMenu.TargetKind.MP4;
      buffer.writeEnum(safeKind);
      buffer.writeBoolean(targetPos != null);
      if (targetPos != null) {
         buffer.writeBlockPos(targetPos);
      }

      buffer.writeBoolean(mp4DeviceId != null);
      if (mp4DeviceId != null) {
         buffer.writeUUID(mp4DeviceId);
      }
   }

   public ItemStack manualMp4TargetStack() {
      return this.usesManualMp4TargetSlot() ? this.toolContainer.getItem(0) : ItemStack.EMPTY;
   }

   public int headphoneBindingCount() {
      return this.headphoneBindingCount;
   }

   public int holographicBindingCount() {
      return this.holographicBindingCount;
   }

   public int totalTargetBindingCount() {
      return this.headphoneBindingCount + this.holographicBindingCount;
   }

   public MediaBindingData.MediaSource targetSource(Player player) {
      return switch (this.targetKind) {
         case TURNTABLE -> MediaBindingCleanupService.turntableSource(player != null ? player.level() : null, this.targetPos);
         case MP4 -> {
            UUID deviceId = this.mediaDeviceId;
            if (deviceId == null) {
               ItemStack mp4Stack = this.manualMp4TargetStack();
               deviceId = mp4Stack.getItem() instanceof MP4Item && player instanceof ServerPlayer serverPlayer
                  ? MP4DeviceIdentity.getOrCreateUnique((ServerLevel)serverPlayer.level(), serverPlayer, mp4Stack)
                  : null;
            }

            yield MediaBindingCleanupService.mp4Source(deviceId);
         }
         case PAD -> {
            UUID deviceId = this.mediaDeviceId;
            if (deviceId == null) {
               ItemStack padStack = this.manualMp4TargetStack();
               deviceId = padStack.getItem() instanceof PadItem ? PadItem.getOrCreateDeviceId(padStack) : null;
            }

            yield MediaBindingCleanupService.padSource(deviceId);
         }
      };
   }

   public void refreshTargetBindingStats(Player player) {
      MediaBindingCleanupService.TargetBindingStats stats = player instanceof ServerPlayer serverPlayer
         ? MediaBindingCleanupService.countTargetBindings(serverPlayer, this.targetSource(player))
         : MediaBindingCleanupService.TargetBindingStats.EMPTY;
      this.headphoneBindingCount = stats.headphoneCount();
      this.holographicBindingCount = stats.holographicCount();
      this.broadcastChanges();
   }

   public boolean hasInputEquipment() {
      return !this.toolContainer.getItem(1).isEmpty();
   }

   public boolean confirmBinding(Player player) {
      if (this.confirmed) {
         return false;
      } else {
         ItemStack input = this.toolContainer.getItem(1);
         if (input.isEmpty()) {
            return false;
         } else if (!this.toolContainer.getItem(2).isEmpty()) {
            return false;
         } else {
            this.toolContainer.setItem(1, ItemStack.EMPTY);
            this.toolContainer.setItem(2, input);
            this.confirmed = true;
            this.broadcastChanges();
            return true;
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      Slot slot = (Slot)this.slots.get(index);
      if (slot != null && slot.hasItem()) {
         ItemStack original = slot.getItem();
         ItemStack copy = original.copy();
         if (index == 2) {
            if (!this.moveItemStackTo(original, 3, this.slots.size(), true)) {
               return ItemStack.EMPTY;
            }
         } else if (index >= 3) {
            int destinationSlot = this.usesManualMp4TargetSlot() && this.isExpectedTargetItem(original) ? 0 : 1;
            if (!this.moveItemStackTo(original, destinationSlot, destinationSlot + 1, false)) {
               return ItemStack.EMPTY;
            }
         } else if (index == 1) {
            if (!this.moveItemStackTo(original, 3, this.slots.size(), false)) {
               return ItemStack.EMPTY;
            }
         } else {
            if (index != 0 || !this.usesManualMp4TargetSlot()) {
               return ItemStack.EMPTY;
            }

            if (!this.moveItemStackTo(original, 3, this.slots.size(), false)) {
               return ItemStack.EMPTY;
            }
         }

         if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
         } else {
            slot.setChanged();
         }

         return copy;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public void slotsChanged(Container container) {
      super.slotsChanged(container);
      if (container == this.toolContainer) {
         this.confirmed = false;
         this.refreshTargetBindingStats(this.owner);
      }
   }

   public boolean stillValid(Player player) {
      return true;
   }

   public void removed(Player player) {
      super.removed(player);
      if (!player.level().isClientSide()) {
         if (this.usesManualMp4TargetSlot()) {
            this.returnRealSlotToPlayer(player, 0);
         }

         this.returnRealSlotToPlayer(player, 1);
         this.returnRealSlotToPlayer(player, 2);
         this.toolContainer.setItem(0, ItemStack.EMPTY);
      }
   }

   private void returnRealSlotToPlayer(Player player, int slot) {
      ItemStack stack = this.toolContainer.removeItemNoUpdate(slot);
      if (!stack.isEmpty()) {
         player.getInventory().placeItemBackInInventory(stack);
      }
   }

   private void addPlayerInventory(Inventory inventory, int left, int top) {
      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col + row * 9 + 9, left + col * 18, top + row * 18));
         }
      }

      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(inventory, col, left + col * 18, top + 58));
      }
   }

   private ItemStack targetIcon() {
      return switch (this.targetKind) {
         case TURNTABLE -> new ItemStack((ItemLike)ModItems.MODERN_TURNTABLE.get());
         case MP4 -> new ItemStack((ItemLike)ModItems.MP4.get());
         case PAD -> new ItemStack((ItemLike)ModItems.PAD.get());
      };
   }

   private boolean isExpectedTargetItem(ItemStack stack) {
      return this.targetKind == MediaToolBindingMenu.TargetKind.PAD ? stack.getItem() instanceof PadItem : stack.getItem() instanceof MP4Item;
   }

   private static MediaToolBindingMenu.TargetKind readTargetKind(RegistryFriendlyByteBuf buffer) {
      return buffer != null && buffer.isReadable()
         ? (MediaToolBindingMenu.TargetKind)buffer.readEnum(MediaToolBindingMenu.TargetKind.class)
         : MediaToolBindingMenu.TargetKind.MP4;
   }

   private static BlockPos readTargetPos(RegistryFriendlyByteBuf buffer) {
      return buffer != null && buffer.isReadable() && buffer.readBoolean() ? buffer.readBlockPos() : null;
   }

   private static UUID readMp4DeviceId(RegistryFriendlyByteBuf buffer) {
      return buffer != null && buffer.isReadable() && buffer.readBoolean() ? buffer.readUUID() : null;
   }

   private static final class EquipmentInputSlot extends Slot {
      private EquipmentInputSlot(Container container, int slot, int x, int y) {
         super(container, slot, x, y);
      }

      public boolean mayPlace(ItemStack stack) {
         return HeadphoneAbility.has(stack) || HolographicGlassesAbility.has(stack);
      }
   }

   private static final class LockedSlot extends Slot {
      private LockedSlot(Container container, int slot, int x, int y) {
         super(container, slot, x, y);
      }

      public boolean mayPlace(ItemStack stack) {
         return false;
      }

      public boolean mayPickup(Player player) {
         return false;
      }
   }

   private static final class Mp4TargetSlot extends Slot {
      private final MediaToolBindingMenu menu;

      private Mp4TargetSlot(Container container, int slot, int x, int y, MediaToolBindingMenu menu) {
         super(container, slot, x, y);
         this.menu = menu;
      }

      public boolean mayPlace(ItemStack stack) {
         return this.menu.targetKind == MediaToolBindingMenu.TargetKind.PAD ? stack.getItem() instanceof PadItem : stack.getItem() instanceof MP4Item;
      }

      public int getMaxStackSize() {
         return 1;
      }

      public void setChanged() {
         super.setChanged();
         if (!this.menu.owner.level().isClientSide()) {
            this.menu.confirmed = false;
            this.menu.refreshTargetBindingStats(this.menu.owner);
         }
      }
   }

   private static final class OutputSlot extends Slot {
      private OutputSlot(Container container, int slot, int x, int y) {
         super(container, slot, x, y);
      }

      public boolean mayPlace(ItemStack stack) {
         return false;
      }
   }

   public static enum TargetKind {
      TURNTABLE,
      MP4,
      PAD;
   }
}
