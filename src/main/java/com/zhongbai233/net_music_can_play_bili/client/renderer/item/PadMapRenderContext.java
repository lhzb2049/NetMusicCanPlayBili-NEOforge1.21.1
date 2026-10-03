package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import net.minecraft.client.gui.GuiGraphics;

final class PadMapRenderContext implements AutoCloseable {
   private final PadMapLayerTexture layerTexture;
   private final PadMapGuiLayer guiLayer;

   PadMapRenderContext(String textureKey) {
      this.layerTexture = new PadMapLayerTexture(textureKey);
      this.guiLayer = new PadMapGuiLayer(this.layerTexture);
   }

   void tick(PadMapSnapshot snapshot) {
      this.layerTexture.tick(snapshot);
   }

   void draw(GuiGraphics g, PadGuiViewState view, PadMapProjection.Rect mapRect) {
      this.guiLayer.draw(g, view, mapRect);
   }

   @Override
   public void close() {
      this.layerTexture.close();
   }
}
