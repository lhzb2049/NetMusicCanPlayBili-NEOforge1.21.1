package com.zhongbai233.scene_editor.core.camera;

import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import java.util.Objects;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

public final class EditorCameraController {
   private static final double EPSILON = 1.0E-9;
   private final EditorCameraController.Settings settings;

   public EditorCameraController() {
      this(EditorCameraController.Settings.defaults());
   }

   public EditorCameraController(EditorCameraController.Settings settings) {
      this.settings = Objects.requireNonNull(settings, "settings");
   }

   public EditorCameraState orbit(EditorCameraState state, double yawRadians, double pitchRadians, Vector3dc worldUp) {
      requireFinite(yawRadians, "yawRadians");
      requireFinite(pitchRadians, "pitchRadians");
      Vector3f up = normalized(worldUp, "worldUp");
      Vector3d focus = state.focus();
      Vector3d offset = state.position().sub(focus);
      double distance = offset.length();
      if (distance <= 1.0E-9) {
         throw new IllegalArgumentException("camera position and focus must differ");
      } else {
         Vector3f offsetF = new Vector3f((float)offset.x, (float)offset.y, (float)offset.z);
         new Quaternionf().rotateAxis((float)(-yawRadians), up.x, up.y, up.z).transform(offsetF);
         Vector3f forward = new Vector3f(offsetF).negate().normalize();
         Vector3f right = forward.cross(up, new Vector3f());
         if (right.lengthSquared() > 1.0E-8F && pitchRadians != 0.0) {
            right.normalize();
            Vector3f normalizedOffset = new Vector3f(offsetF).normalize();
            double currentElevation = Math.asin(clamp(normalizedOffset.dot(up), -1.0, 1.0));
            double maximumElevation = Math.toRadians(90.0 - this.settings.minimumPoleAngleDegrees());
            double targetElevation = clamp(currentElevation - pitchRadians, -maximumElevation, maximumElevation);
            double safePitch = currentElevation - targetElevation;
            new Quaternionf().rotateAxis((float)safePitch, right.x, right.y, right.z).transform(offsetF);
         }

         offsetF.normalize((float)distance);
         Vector3d position = new Vector3d(focus).add(offsetF.x, offsetF.y, offsetF.z);
         return lookingAtLike(state, position, focus, new Vector3d(up));
      }
   }

   public EditorCameraState panPixels(EditorCameraState state, double deltaPixelsX, double deltaPixelsY, EditorViewport viewport) {
      requireFinite(deltaPixelsX, "deltaPixelsX");
      requireFinite(deltaPixelsY, "deltaPixelsY");
      Objects.requireNonNull(viewport, "viewport");
      double unitsPerPixel = worldUnitsPerPixel(state, viewport);
      Vector3f right = state.orientation().transform(new Vector3f(1.0F, 0.0F, 0.0F));
      Vector3f up = state.orientation().transform(new Vector3f(0.0F, 1.0F, 0.0F));
      Vector3d translation = new Vector3d(right).mul(-deltaPixelsX * unitsPerPixel).add(new Vector3d(up).mul(deltaPixelsY * unitsPerPixel));
      return state.withPose(state.position().add(translation), state.orientation(), state.focus().add(translation));
   }

   public EditorCameraState dolly(EditorCameraState state, double wheelSteps) {
      requireFinite(wheelSteps, "wheelSteps");
      double factor = Math.exp(-this.settings.dollyExponent() * wheelSteps);
      if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
         double scale = clamp(state.orthoScale() * factor, this.settings.minimumOrthoScale(), this.settings.maximumOrthoScale());
         return state.withOrthoScale((float)scale);
      } else {
         Vector3d focus = state.focus();
         Vector3d offset = state.position().sub(focus);
         double distance = offset.length();
         double targetDistance = clamp(distance * factor, this.settings.minimumDistance(), this.settings.maximumDistance());
         Vector3d position = new Vector3d(focus).add(offset.mul(targetDistance / distance));
         return state.withPose(position, state.orientation(), focus);
      }
   }

   public EditorCameraState fly(EditorCameraState state, Vector3dc localMovement, double deltaSeconds, double speedMultiplier, Vector3dc worldUp) {
      Objects.requireNonNull(localMovement, "localMovement");
      requireFinite(deltaSeconds, "deltaSeconds");
      requireFinite(speedMultiplier, "speedMultiplier");
      if (!(deltaSeconds < 0.0) && !(speedMultiplier < 0.0)) {
         Vector3f right = state.orientation().transform(new Vector3f(1.0F, 0.0F, 0.0F));
         Vector3f forward = state.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
         Vector3f up = normalized(worldUp, "worldUp");
         double amount = this.settings.flySpeed() * deltaSeconds * speedMultiplier;
         Vector3d translation = new Vector3d(right)
            .mul(localMovement.x() * amount)
            .add(new Vector3d(up).mul(localMovement.y() * amount))
            .add(new Vector3d(forward).mul(localMovement.z() * amount));
         return state.withPose(state.position().add(translation), state.orientation(), state.focus().add(translation));
      } else {
         throw new IllegalArgumentException("fly timing and speed must be non-negative");
      }
   }

   public EditorCameraState fly(
      EditorCameraState state,
      boolean forward,
      boolean backward,
      boolean left,
      boolean right,
      boolean down,
      boolean up,
      double deltaSeconds,
      boolean fast,
      Vector3dc worldUp
   ) {
      Vector3f movement = new Vector3f(
         (right ? 1.0F : 0.0F) - (left ? 1.0F : 0.0F), (up ? 1.0F : 0.0F) - (down ? 1.0F : 0.0F), (forward ? 1.0F : 0.0F) - (backward ? 1.0F : 0.0F)
      );
      if (movement.lengthSquared() > 1.0E-8F) {
         movement.normalize();
      }

      double multiplier = fast ? this.settings.flyBoostMultiplier() : 1.0;
      return this.fly(state, new Vector3d(movement), deltaSeconds, multiplier, worldUp);
   }

   public EditorCameraState walk(
      EditorCameraState state,
      boolean forward,
      boolean backward,
      boolean left,
      boolean right,
      boolean down,
      boolean up,
      double deltaSeconds,
      boolean fast,
      Vector3dc worldUp
   ) {
      requireFinite(deltaSeconds, "deltaSeconds");
      if (deltaSeconds < 0.0) {
         throw new IllegalArgumentException("walk timing must be non-negative");
      } else {
         Vector3f vertical = normalized(worldUp, "worldUp");
         Vector3f cameraForward = state.orientation().transform(new Vector3f(0.0F, 0.0F, -1.0F));
         Vector3f horizontalForward = cameraForward.sub(new Vector3f(vertical).mul(cameraForward.dot(vertical)));
         if (horizontalForward.lengthSquared() <= 1.0E-8F) {
            Vector3d focusDirection = state.focus().sub(state.position());
            horizontalForward.set((float)focusDirection.x, 0.0F, (float)focusDirection.z);
         }

         if (horizontalForward.lengthSquared() <= 1.0E-8F) {
            horizontalForward.set(0.0F, 0.0F, -1.0F);
         }

         horizontalForward.normalize();
         Vector3f horizontalRight = horizontalForward.cross(vertical, new Vector3f()).normalize();
         float forwardInput = (forward ? 1.0F : 0.0F) - (backward ? 1.0F : 0.0F);
         float rightInput = (right ? 1.0F : 0.0F) - (left ? 1.0F : 0.0F);
         float verticalInput = (up ? 1.0F : 0.0F) - (down ? 1.0F : 0.0F);
         Vector3d movement = new Vector3d(horizontalForward)
            .mul(forwardInput)
            .add(new Vector3d(horizontalRight).mul(rightInput))
            .add(new Vector3d(vertical).mul(verticalInput));
         if (movement.lengthSquared() > 1.0E-9) {
            movement.normalize();
         }

         double multiplier = fast ? this.settings.flyBoostMultiplier() : 1.0;
         double amount = this.settings.flySpeed() * deltaSeconds * multiplier;
         Vector3d translation = movement.mul(amount);
         return state.withPose(state.position().add(translation), state.orientation(), state.focus().add(translation));
      }
   }

   public EditorCameraState focus(EditorCameraState state, Vector3dc center, double boundingRadius, EditorViewport viewport, Vector3dc worldUp) {
      Objects.requireNonNull(viewport, "viewport");
      requireFinite(boundingRadius, "boundingRadius");
      if (boundingRadius < 0.0) {
         throw new IllegalArgumentException("boundingRadius must be non-negative");
      } else {
         double radius = Math.max(this.settings.minimumFocusRadius(), boundingRadius) * this.settings.focusMargin();
         Vector3f backward = state.orientation().transform(new Vector3f(0.0F, 0.0F, 1.0F)).normalize();
         if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
            float scale = (float)clamp(radius, this.settings.minimumOrthoScale(), this.settings.maximumOrthoScale());
            Vector3d position = new Vector3d(center)
               .add(new Vector3d(backward).mul(Math.max(this.settings.minimumDistance(), state.position().distance(state.focus()))));
            return lookingAtLike(state.withOrthoScale(scale), position, center, worldUp);
         } else {
            double verticalHalfFov = Math.toRadians(state.fovDegrees()) * 0.5;
            double horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * viewport.aspectRatio());
            double limitingHalfFov = Math.min(verticalHalfFov, horizontalHalfFov);
            double distance = clamp(radius / Math.sin(limitingHalfFov), this.settings.minimumDistance(), this.settings.maximumDistance());
            Vector3d position = new Vector3d(center).add(new Vector3d(backward).mul(distance));
            return lookingAtLike(state, position, center, worldUp);
         }
      }
   }

   public EditorCameraState standardView(EditorCameraState state, StandardCameraView view, Vector3dc worldUp) {
      Objects.requireNonNull(view, "view");

      Vector3d backward = switch (view) {
         case FRONT -> new Vector3d(0.0, 0.0, 1.0);
         case BACK -> new Vector3d(0.0, 0.0, -1.0);
         case LEFT -> new Vector3d(-1.0, 0.0, 0.0);
         case RIGHT -> new Vector3d(1.0, 0.0, 0.0);
         case TOP -> new Vector3d(0.0, 1.0, 0.0);
         case BOTTOM -> new Vector3d(0.0, -1.0, 0.0);
      };
      Vector3d focus = state.focus();
      double distance = Math.max(this.settings.minimumDistance(), state.position().distance(focus));
      Vector3d position = new Vector3d(focus).add(backward.mul(distance));
      Vector3dc effectiveUp = (Vector3dc)(view != StandardCameraView.TOP && view != StandardCameraView.BOTTOM
         ? worldUp
         : new Vector3d(0.0, 0.0, view == StandardCameraView.TOP ? -1.0 : 1.0));
      return lookingAtLike(state, position, focus, effectiveUp);
   }

   public EditorCameraState switchProjection(EditorCameraState state, EditorCameraMode targetMode) {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(targetMode, "targetMode");
      if (targetMode != EditorCameraMode.ORBIT && targetMode != EditorCameraMode.ORTHOGRAPHIC) {
         throw new IllegalArgumentException("targetMode must be ORBIT or ORTHOGRAPHIC");
      } else if (state.mode() == targetMode) {
         return state;
      } else {
         double halfFov = Math.toRadians(state.fovDegrees()) * 0.5;
         if (targetMode == EditorCameraMode.ORTHOGRAPHIC) {
            double distance = state.position().distance(state.focus());
            double scale = clamp(distance * Math.tan(halfFov), this.settings.minimumOrthoScale(), this.settings.maximumOrthoScale());
            return state.withOrthoScale((float)scale).withMode(EditorCameraMode.ORTHOGRAPHIC);
         } else {
            Vector3f backward = state.orientation().transform(new Vector3f(0.0F, 0.0F, 1.0F));
            double distance = clamp(state.orthoScale() / Math.tan(halfFov), this.settings.minimumDistance(), this.settings.maximumDistance());
            Vector3d focus = state.focus();
            Vector3d position = new Vector3d(focus).add(new Vector3d(backward).mul(distance));
            return state.withPose(position, state.orientation(), focus).withMode(EditorCameraMode.ORBIT);
         }
      }
   }

   private static double worldUnitsPerPixel(EditorCameraState state, EditorViewport viewport) {
      if (state.mode() == EditorCameraMode.ORTHOGRAPHIC) {
         return 2.0 * state.orthoScale() / viewport.height();
      } else {
         double distance = state.position().distance(state.focus());
         return 2.0 * distance * Math.tan(Math.toRadians(state.fovDegrees()) * 0.5) / viewport.height();
      }
   }

   private static EditorCameraState lookingAtLike(EditorCameraState source, Vector3dc position, Vector3dc focus, Vector3dc worldUp) {
      return EditorCameraState.lookingAt(
         source.mode(), position, focus, worldUp, source.fovDegrees(), source.orthoScale(), source.nearPlane(), source.farPlane()
      );
   }

   private static Vector3f normalized(Vector3dc value, String name) {
      Objects.requireNonNull(value, name);
      if (Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z())) {
         Vector3f result = new Vector3f((float)value.x(), (float)value.y(), (float)value.z());
         if (result.lengthSquared() <= 1.0E-8F) {
            throw new IllegalArgumentException(name + " must be non-zero");
         } else {
            return result.normalize();
         }
      } else {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }

   private static void requireFinite(double value, String name) {
      if (!Double.isFinite(value)) {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }

   private static double clamp(double value, double minimum, double maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   public record Settings(
      double dollyExponent,
      double minimumDistance,
      double maximumDistance,
      double minimumOrthoScale,
      double maximumOrthoScale,
      double flySpeed,
      double minimumPoleAngleDegrees,
      double minimumFocusRadius,
      double focusMargin,
      double flyBoostMultiplier
   ) {
      public Settings(
         double dollyExponent,
         double minimumDistance,
         double maximumDistance,
         double minimumOrthoScale,
         double maximumOrthoScale,
         double flySpeed,
         double minimumPoleAngleDegrees,
         double minimumFocusRadius,
         double focusMargin
      ) {
         this(
            dollyExponent,
            minimumDistance,
            maximumDistance,
            minimumOrthoScale,
            maximumOrthoScale,
            flySpeed,
            minimumPoleAngleDegrees,
            minimumFocusRadius,
            focusMargin,
            4.0
         );
      }

      public Settings(
         double dollyExponent,
         double minimumDistance,
         double maximumDistance,
         double minimumOrthoScale,
         double maximumOrthoScale,
         double flySpeed,
         double minimumPoleAngleDegrees,
         double minimumFocusRadius,
         double focusMargin,
         double flyBoostMultiplier
      ) {
         if (positiveFinite(dollyExponent)
            && positiveFinite(minimumDistance)
            && positiveFinite(maximumDistance)
            && !(maximumDistance < minimumDistance)
            && positiveFinite(minimumOrthoScale)
            && positiveFinite(maximumOrthoScale)
            && !(maximumOrthoScale < minimumOrthoScale)
            && positiveFinite(flySpeed)
            && Double.isFinite(minimumPoleAngleDegrees)
            && !(minimumPoleAngleDegrees <= 0.0)
            && !(minimumPoleAngleDegrees >= 45.0)
            && positiveFinite(minimumFocusRadius)
            && Double.isFinite(focusMargin)
            && !(focusMargin < 1.0)
            && positiveFinite(flyBoostMultiplier)) {
            this.dollyExponent = dollyExponent;
            this.minimumDistance = minimumDistance;
            this.maximumDistance = maximumDistance;
            this.minimumOrthoScale = minimumOrthoScale;
            this.maximumOrthoScale = maximumOrthoScale;
            this.flySpeed = flySpeed;
            this.minimumPoleAngleDegrees = minimumPoleAngleDegrees;
            this.minimumFocusRadius = minimumFocusRadius;
            this.focusMargin = focusMargin;
            this.flyBoostMultiplier = flyBoostMultiplier;
         } else {
            throw new IllegalArgumentException("invalid camera controller settings");
         }
      }

      public static EditorCameraController.Settings defaults() {
         return new EditorCameraController.Settings(0.18, 0.05, 4096.0, 0.01, 4096.0, 8.0, 2.0, 0.05, 1.15, 4.0);
      }

      private static boolean positiveFinite(double value) {
         return Double.isFinite(value) && value > 0.0;
      }
   }
}
