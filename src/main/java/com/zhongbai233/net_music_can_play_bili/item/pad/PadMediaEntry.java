package com.zhongbai233.net_music_can_play_bili.item.pad;

import java.util.Objects;
import javax.annotation.Nonnull;
import net.minecraft.world.item.ItemStack;

public record PadMediaEntry(int mediaId, @Nonnull ItemStack disc) {
   public PadMediaEntry(int mediaId, ItemStack disc) {
      mediaId = Math.max(1, mediaId);
      disc = sanitizeDisc(disc);
      this.mediaId = mediaId;
      this.disc = disc;
   }

   @Nonnull
   public ItemStack disc() {
      return Objects.requireNonNull(this.disc, "disc");
   }

   @Nonnull
   private static ItemStack sanitizeDisc(ItemStack disc) {
      return disc != null && !disc.isEmpty() ? Objects.requireNonNull(disc.copyWithCount(1), "copyWithCount") : emptyStack();
   }

   @Nonnull
   private static ItemStack emptyStack() {
      return Objects.requireNonNull(ItemStack.EMPTY, "ItemStack.EMPTY");
   }
}
