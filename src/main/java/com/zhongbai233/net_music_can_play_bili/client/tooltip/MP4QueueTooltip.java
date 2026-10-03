package com.zhongbai233.net_music_can_play_bili.client.tooltip;

import java.util.List;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

public record MP4QueueTooltip(List<String> titles, int selectedIndex) implements TooltipComponent {
   public MP4QueueTooltip(List<String> titles, int selectedIndex) {
      titles = List.copyOf(titles);
      this.titles = titles;
      this.selectedIndex = selectedIndex;
   }
}
