package com.zhongbai233.scene_editor.core.math;

import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class EditorTransform {
   private final Vector3f position;
   private final Quaternionf rotation;
   private final Vector3f scale;
   private final Vector3f pivot;
   private final float skewXByY;
   private final float skewYByX;

   public EditorTransform(Vector3fc position, Quaternionfc rotation, Vector3fc scale, Vector3fc pivot, float skewXByY, float skewYByX) {
      this.position = finiteCopy(position, "position");
      this.scale = finiteCopy(scale, "scale");
      this.pivot = finiteCopy(pivot, "pivot");
      this.rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation"));
      if (Float.isFinite(this.rotation.x)
         && Float.isFinite(this.rotation.y)
         && Float.isFinite(this.rotation.z)
         && Float.isFinite(this.rotation.w)
         && !(this.rotation.lengthSquared() <= 1.0E-8F)) {
         this.rotation.normalize();
         if (this.scale.x <= 0.0F || this.scale.y <= 0.0F || this.scale.z <= 0.0F) {
            throw new IllegalArgumentException("scale components must be positive");
         } else if (Float.isFinite(skewXByY) && Float.isFinite(skewYByX)) {
            this.skewXByY = skewXByY;
            this.skewYByX = skewYByX;
         } else {
            throw new IllegalArgumentException("skew must be finite");
         }
      } else {
         throw new IllegalArgumentException("rotation must be finite and non-zero");
      }
   }

   public static EditorTransform identity() {
      return new EditorTransform(new Vector3f(), new Quaternionf(), new Vector3f(1.0F), new Vector3f(), 0.0F, 0.0F);
   }

   public static EditorTransform fromEulerDegrees(
      Vector3fc position, float yaw, float pitch, float roll, Vector3fc scale, Vector3fc pivot, float skewXByY, float skewYByX
   ) {
      Quaternionf rotation = new Quaternionf().rotateYXZ((float)Math.toRadians(yaw), (float)Math.toRadians(pitch), (float)Math.toRadians(roll));
      return new EditorTransform(position, rotation, scale, pivot, skewXByY, skewYByX);
   }

   public Vector3f position() {
      return new Vector3f(this.position);
   }

   public Quaternionf rotation() {
      return new Quaternionf(this.rotation);
   }

   public Vector3f scale() {
      return new Vector3f(this.scale);
   }

   public Vector3f pivot() {
      return new Vector3f(this.pivot);
   }

   public float skewXByY() {
      return this.skewXByY;
   }

   public float skewYByX() {
      return this.skewYByX;
   }

   public Matrix4f matrix() {
      Matrix4f shear = new Matrix4f().identity();
      shear.m10(this.skewXByY);
      shear.m01(this.skewYByX);
      return new Matrix4f()
         .translate(this.position)
         .translate(this.pivot)
         .rotate(this.rotation)
         .mul(shear)
         .scale(this.scale)
         .translate(-this.pivot.x, -this.pivot.y, -this.pivot.z);
   }

   public EditorTransform withPosition(Vector3fc newPosition) {
      return new EditorTransform(newPosition, this.rotation, this.scale, this.pivot, this.skewXByY, this.skewYByX);
   }

   public EditorTransform withRotation(Quaternionfc newRotation) {
      return new EditorTransform(this.position, newRotation, this.scale, this.pivot, this.skewXByY, this.skewYByX);
   }

   public EditorTransform withEulerDegrees(float yaw, float pitch, float roll) {
      return this.withRotation(new Quaternionf().rotateYXZ((float)Math.toRadians(yaw), (float)Math.toRadians(pitch), (float)Math.toRadians(roll)));
   }

   public EditorTransform withScale(Vector3fc newScale) {
      return new EditorTransform(this.position, this.rotation, newScale, this.pivot, this.skewXByY, this.skewYByX);
   }

   public EditorTransform withPivot(Vector3fc newPivot) {
      return new EditorTransform(this.position, this.rotation, this.scale, newPivot, this.skewXByY, this.skewYByX);
   }

   public EditorTransform withSkew(float newSkewXByY, float newSkewYByX) {
      return new EditorTransform(this.position, this.rotation, this.scale, this.pivot, newSkewXByY, newSkewYByX);
   }

   private static Vector3f finiteCopy(Vector3fc value, String name) {
      Objects.requireNonNull(value, name);
      if (Float.isFinite(value.x()) && Float.isFinite(value.y()) && Float.isFinite(value.z())) {
         return new Vector3f(value);
      } else {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }
}
