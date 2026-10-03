package com.zhongbai233.scene_editor.core.camera;

import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import java.util.Objects;

public record CameraFrame(CameraMatrices matrices, EditorViewport viewport, EditorCameraMode mode) {
   public CameraFrame(CameraMatrices matrices, EditorViewport viewport, EditorCameraMode mode) {
      Objects.requireNonNull(matrices, "matrices");
      Objects.requireNonNull(viewport, "viewport");
      Objects.requireNonNull(mode, "mode");
      this.matrices = matrices;
      this.viewport = viewport;
      this.mode = mode;
   }
}
