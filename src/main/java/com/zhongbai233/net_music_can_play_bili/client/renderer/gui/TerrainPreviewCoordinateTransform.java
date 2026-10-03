package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

final class TerrainPreviewCoordinateTransform {
   private TerrainPreviewCoordinateTransform() {
   }

   static TerrainPreviewCoordinateTransform.Frame create(Matrix4fc editorModelView, double cameraX, double cameraY, double cameraZ) {
      int cameraBlockX = floorToInt(cameraX);
      int cameraBlockY = floorToInt(cameraY);
      int cameraBlockZ = floorToInt(cameraZ);
      float cameraFractionX = (float)(cameraX - cameraBlockX);
      float cameraFractionY = (float)(cameraY - cameraBlockY);
      float cameraFractionZ = (float)(cameraZ - cameraBlockZ);
      Matrix4f compensatedModelView = new Matrix4f(editorModelView).translate(cameraFractionX, cameraFractionY, cameraFractionZ);
      return new TerrainPreviewCoordinateTransform.Frame(compensatedModelView, cameraBlockX, cameraBlockY, cameraBlockZ);
   }

   private static int floorToInt(double value) {
      if (Double.isFinite(value) && !(value < -2.1474836E9F) && !(value > 2.147483647E9)) {
         return (int)Math.floor(value);
      } else {
         throw new IllegalArgumentException("camera coordinate must be a finite int-range value");
      }
   }

   record Frame(Matrix4f modelView, int cameraBlockX, int cameraBlockY, int cameraBlockZ) {
      Frame(Matrix4f modelView, int cameraBlockX, int cameraBlockY, int cameraBlockZ) {
         modelView = new Matrix4f(Objects.requireNonNull(modelView, "modelView"));
         this.modelView = modelView;
         this.cameraBlockX = cameraBlockX;
         this.cameraBlockY = cameraBlockY;
         this.cameraBlockZ = cameraBlockZ;
      }

      int encodedSectionX(int localSectionX) {
         return Math.addExact(this.cameraBlockX, localSectionX);
      }

      int encodedSectionY(int localSectionY) {
         return Math.addExact(this.cameraBlockY, localSectionY);
      }

      int encodedSectionZ(int localSectionZ) {
         return Math.addExact(this.cameraBlockZ, localSectionZ);
      }
   }
}
