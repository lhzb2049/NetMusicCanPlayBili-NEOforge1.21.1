package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.logging.LogUtils;
import com.zhongbai233.scene_editor.core.math.EditorTransform;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import java.util.List;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

public final class SceneEditorDedicatedServerSelfTest {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static boolean ran;

   private SceneEditorDedicatedServerSelfTest() {
   }

   public static void onServerStarted(ServerStartedEvent event) {
      if (!ran && Boolean.getBoolean("ncpb.scene_editor.server_self_test")) {
         ran = true;

         try {
            if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
               throw new IllegalStateException("Scene Editor server smoke test must run on DEDICATED_SERVER");
            }

            if (!"com.github.zhongbai2333.SceneEditor".equals("com.github.zhongbai2333.SceneEditor")
               || !"scene-editor-minecraft".equals("scene-editor-minecraft")
               || sceneEditorApiMajor() != 1) {
               throw new IllegalStateException("Scene Editor Minecraft library identity mismatch");
            }

            UUID id = UUID.fromString("00000000-0000-0000-0000-000000000008");
            SceneElement element = new SceneEditorDedicatedServerSelfTest.ServerElement(id, "server:probe", EditorTransform.identity());
            SceneDocument<SceneElement> document = new SceneDocument<>(List.of(element));
            if (!element.equals(document.element(id).orElseThrow())) {
               throw new IllegalStateException("Scene Editor core API failed on dedicated server");
            }

            LOGGER.info(
               "SceneEditorDedicatedServerSelfTest passed: group={}, artifact={}, apiMajor={}, elements={}",
               new Object[]{"com.github.zhongbai2333.SceneEditor", "scene-editor-minecraft", 1, document.elements().size()}
            );
         } catch (RuntimeException var7) {
            LOGGER.error("SceneEditorDedicatedServerSelfTest failed", var7);
            Runtime.getRuntime().halt(1);
            throw var7;
         } finally {
            event.getServer().halt(false);
         }
      }
   }

   private static int sceneEditorApiMajor() {
      return 1;
   }

   private record ServerElement(UUID id, String typeId, EditorTransform transform) implements SceneElement {
   }
}
