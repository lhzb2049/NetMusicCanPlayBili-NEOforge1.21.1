package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.item.InvisibleHeadphonesItem;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.item.MediaManagementToolItem;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item.Properties;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments.Mutable;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredRegister.Items;

public final class ModItems {
   public static final Items ITEMS = DeferredRegister.createItems("net_music_can_play_bili");
   public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, "net_music_can_play_bili");
   public static final DeferredItem<BlockItem> MODERN_TURNTABLE = ITEMS.registerSimpleBlockItem("modern_turntable", ModBlocks.MODERN_TURNTABLE);
   public static final DeferredItem<BlockItem> LYRIC_PROJECTOR = ITEMS.registerSimpleBlockItem("lyric_projector", ModBlocks.LYRIC_PROJECTOR);
   public static final DeferredItem<BlockItem> VIDEO_PROJECTOR = ITEMS.registerSimpleBlockItem("video_projector", ModBlocks.VIDEO_PROJECTOR);
   public static final DeferredItem<BlockItem> SPEAKER = ITEMS.registerSimpleBlockItem("speaker", ModBlocks.SPEAKER);
   public static final DeferredItem<BlockItem> LIVE_STREAMER = ITEMS.registerSimpleBlockItem("live_streamer", ModBlocks.LIVE_STREAMER);
   public static final DeferredItem<BlockItem> CONTROL_CONSOLE = ITEMS.registerSimpleBlockItem("control_console", ModBlocks.CONTROL_CONSOLE);
   public static final DeferredItem<MP4Item> MP4 = ITEMS.registerItem("mp4", MP4Item::new, new Properties());
   public static final DeferredItem<PadItem> PAD = ITEMS.registerItem("pad", PadItem::new, new Properties());
   public static final DeferredItem<MediaManagementToolItem> MEDIA_MANAGEMENT_TOOL = ITEMS.registerItem(
      "media_management_tool", MediaManagementToolItem::new, new Properties().stacksTo(1)
   );
   public static final DeferredItem<InvisibleHeadphonesItem> INVISIBLE_HEADPHONES = ITEMS.registerItem(
      "invisible_headphones", InvisibleHeadphonesItem::new, headphoneItemProperties(new Properties())
   );
   public static final DeferredItem<InvisibleHeadphonesItem> CAT_HEADPHONES = ITEMS.registerItem(
      "cat_headphones", InvisibleHeadphonesItem::new, headphoneItemProperties(new Properties())
   );
   public static final DeferredItem<HolographicGlassesItem> HOLOGRAPHIC_GLASSES = ITEMS.registerItem(
      "holographic_glasses", HolographicGlassesItem::new, holographicGlassesItemProperties(new Properties())
   );
   public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = TABS.register(
      "main",
      () -> CreativeModeTab.builder()
         .title(Component.translatable("itemGroup.net_music_can_play_bili"))
         .icon(() -> new ItemStack((ItemLike)MODERN_TURNTABLE.get()))
         .displayItems(
            (parameters, output) -> {
               output.accept((ItemLike)MODERN_TURNTABLE.get());
               output.accept((ItemLike)LYRIC_PROJECTOR.get());
               output.accept((ItemLike)VIDEO_PROJECTOR.get());
               output.accept((ItemLike)SPEAKER.get());
               output.accept((ItemLike)LIVE_STREAMER.get());
               output.accept((ItemLike)CONTROL_CONSOLE.get());
               output.accept((ItemLike)MP4.get());
               output.accept((ItemLike)PAD.get());
               output.accept((ItemLike)MEDIA_MANAGEMENT_TOOL.get());
               output.accept((ItemLike)INVISIBLE_HEADPHONES.get());
               output.accept((ItemLike)CAT_HEADPHONES.get());
               output.accept((ItemLike)HOLOGRAPHIC_GLASSES.get());
               parameters.holders()
                  .lookupOrThrow(Registries.ENCHANTMENT)
                  .get(HeadphoneAbility.HEADPHONES_KEY)
                  .ifPresent(enchantment -> output.accept(createEnchantedBook(enchantment, 1)));
               parameters.holders()
                  .lookupOrThrow(Registries.ENCHANTMENT)
                  .get(HolographicGlassesAbility.HOLOGRAPHIC_GLASSES_KEY)
                  .ifPresent(enchantment -> output.accept(createEnchantedBook(enchantment, 1)));
            }
         )
         .build()
   );

   private static Properties headphoneItemProperties(Properties properties) {
      return properties.attributes(headphoneAttributeModifiers());
   }

   private static Properties holographicGlassesItemProperties(Properties properties) {
      return properties.attributes(holographicGlassesAttributeModifiers());
   }

   private static ItemAttributeModifiers headphoneAttributeModifiers() {
      return ItemAttributeModifiers.builder()
         .add(
            ModAttributes.HEADPHONES,
            new AttributeModifier(ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "headphones"), 1.0, Operation.ADD_VALUE),
            EquipmentSlotGroup.HEAD
         )
         .build();
   }

   private static ItemAttributeModifiers holographicGlassesAttributeModifiers() {
      return ItemAttributeModifiers.builder()
         .add(
            ModAttributes.HOLOGRAPHIC_GLASSES,
            new AttributeModifier(ResourceLocation.fromNamespaceAndPath("net_music_can_play_bili", "holographic_glasses"), 1.0, Operation.ADD_VALUE),
            EquipmentSlotGroup.HEAD
         )
         .build();
   }

   private static ItemStack createEnchantedBook(Holder<Enchantment> enchantment, int level) {
      ItemStack book = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
      Mutable stored = new Mutable(ItemEnchantments.EMPTY);
      stored.set(enchantment, level);
      book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
      return book;
   }

   private ModItems() {
   }
}
