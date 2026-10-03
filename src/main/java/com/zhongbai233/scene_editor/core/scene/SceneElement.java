package com.zhongbai233.scene_editor.core.scene;

import com.zhongbai233.scene_editor.core.math.EditorTransform;
import java.util.UUID;

public interface SceneElement {
   UUID id();

   String typeId();

   EditorTransform transform();
}
