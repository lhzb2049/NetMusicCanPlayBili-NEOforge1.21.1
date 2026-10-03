package com.zhongbai233.scene_editor.core.camera;

import java.util.Objects;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

public final class WorldCameraPose {
   private static final float VERTICAL_EPSILON = 1.0E-6F;
   private final Vector3d position;
   private final float yawDegrees;
   private final float pitchDegrees;

   private WorldCameraPose(Vector3dc position, float yawDegrees, float pitchDegrees) {
      this.position = new Vector3d(position);
      this.yawDegrees = yawDegrees;
      this.pitchDegrees = pitchDegrees;
   }

   public static WorldCameraPose fromLocal(Vector3dc worldOrigin, Vector3dc localPosition, Quaternionfc localOrientation, float verticalFallbackYawDegrees) {
      Vector3d origin = finiteVector(worldOrigin, "worldOrigin");
      Vector3d local = finiteVector(localPosition, "localPosition");
      Quaternionf orientation = new Quaternionf(Objects.requireNonNull(localOrientation, "localOrientation"));
      if (!Float.isFinite(orientation.x)
         || !Float.isFinite(orientation.y)
         || !Float.isFinite(orientation.z)
         || !Float.isFinite(orientation.w)
         || orientation.lengthSquared() <= 1.0E-8F) {
         throw new IllegalArgumentException("localOrientation must be finite and non-zero");
      } else if (!Float.isFinite(verticalFallbackYawDegrees)) {
         throw new IllegalArgumentException("verticalFallbackYawDegrees must be finite");
      } else {
         orientation.normalize();
         Vector3f forward = orientation.transform(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
         float horizontal = (float)Math.hypot(forward.x, forward.z);
         float yaw = horizontal <= 1.0E-6F ? verticalFallbackYawDegrees : (float)Math.toDegrees(Math.atan2(-forward.x, forward.z));
         float pitch = (float)Math.toDegrees(Math.atan2(-forward.y, horizontal));
         return new WorldCameraPose(origin.add(local), yaw, pitch);
      }
   }

   public Vector3d position() {
      return new Vector3d(this.position);
   }

   public float yawDegrees() {
      return this.yawDegrees;
   }

   public float pitchDegrees() {
      return this.pitchDegrees;
   }

   private static Vector3d finiteVector(Vector3dc value, String name) {
      Objects.requireNonNull(value, name);
      if (Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z())) {
         return new Vector3d(value);
      } else {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }
}
