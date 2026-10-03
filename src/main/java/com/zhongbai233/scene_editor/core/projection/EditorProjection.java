package com.zhongbai233.scene_editor.core.projection;

import com.zhongbai233.scene_editor.core.camera.CameraMatrices;
import java.util.Objects;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;

public final class EditorProjection {
   private EditorProjection() {
   }

   public static ProjectedPoint project(Vector3dc worldPoint, CameraMatrices matrices, EditorViewport viewport) {
      Vector4f clip = new Vector4f((float)worldPoint.x(), (float)worldPoint.y(), (float)worldPoint.z(), 1.0F).mul(matrices.viewProjection());
      boolean behind = clip.w <= 0.0F;
      if (Float.isFinite(clip.w) && !(Math.abs(clip.w) <= 1.0E-7F)) {
         double ndcX = clip.x / clip.w;
         double ndcY = clip.y / clip.w;
         double ndcZ = clip.z / clip.w;
         double screenX = viewport.x() + (ndcX + 1.0) * 0.5 * viewport.width();
         double screenY = viewport.y() + (1.0 - ndcY) * 0.5 * viewport.height();
         boolean visible = !behind && ndcX >= -1.0 && ndcX <= 1.0 && ndcY >= -1.0 && ndcY <= 1.0 && ndcZ >= -1.0 && ndcZ <= 1.0;
         return new ProjectedPoint(screenX, screenY, ndcZ, visible, behind);
      } else {
         return new ProjectedPoint(Double.NaN, Double.NaN, Double.NaN, false, behind);
      }
   }

   public static PickingRay rayFromScreen(double mouseX, double mouseY, CameraMatrices matrices, EditorViewport viewport) {
      double ndcX = (mouseX - viewport.x()) / viewport.width() * 2.0 - 1.0;
      double ndcY = 1.0 - (mouseY - viewport.y()) / viewport.height() * 2.0;
      Vector3d near = unproject(ndcX, ndcY, -1.0, matrices);
      Vector3d interior = unproject(ndcX, ndcY, 0.0, matrices);
      return new PickingRay(near, interior.sub(near));
   }

   public static Vector3d worldPointAtScreenDepth(double screenX, double screenY, double depth, CameraMatrices matrices, EditorViewport viewport) {
      Objects.requireNonNull(matrices, "matrices");
      Objects.requireNonNull(viewport, "viewport");
      if (Double.isFinite(screenX) && Double.isFinite(screenY) && Double.isFinite(depth)) {
         double ndcX = (screenX - viewport.x()) / viewport.width() * 2.0 - 1.0;
         double ndcY = 1.0 - (screenY - viewport.y()) / viewport.height() * 2.0;
         return unproject(ndcX, ndcY, depth, matrices);
      } else {
         throw new IllegalArgumentException("screen point and depth must be finite");
      }
   }

   private static Vector3d unproject(double x, double y, double z, CameraMatrices matrices) {
      Vector4f world = new Vector4f((float)x, (float)y, (float)z, 1.0F).mul(matrices.inverseViewProjection());
      if (Float.isFinite(world.w) && !(Math.abs(world.w) <= 1.0E-7F)) {
         return new Vector3d(world.x / world.w, world.y / world.w, world.z / world.w);
      } else {
         throw new IllegalStateException("camera matrix cannot unproject this point");
      }
   }
}
