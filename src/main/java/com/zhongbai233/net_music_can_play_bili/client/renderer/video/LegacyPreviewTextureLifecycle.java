package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import java.util.Objects;
import java.util.function.Consumer;

final class LegacyPreviewTextureLifecycle<R, Y, P> {
   private final Consumer<? super R> rgbaDisposer;
   private final Consumer<? super Y> yuvDisposer;
   private final Consumer<? super P> packedDisposer;
   private volatile R rgba;
   private volatile Y yuv;
   private volatile P packed;

   LegacyPreviewTextureLifecycle(Consumer<? super R> rgbaDisposer, Consumer<? super Y> yuvDisposer, Consumer<? super P> packedDisposer) {
      this.rgbaDisposer = Objects.requireNonNull(rgbaDisposer, "rgbaDisposer");
      this.yuvDisposer = Objects.requireNonNull(yuvDisposer, "yuvDisposer");
      this.packedDisposer = Objects.requireNonNull(packedDisposer, "packedDisposer");
   }

   synchronized void replaceRgba(R replacement) {
      R previous = this.rgba;
      if (previous != replacement) {
         this.rgba = replacement;
         dispose(previous, this.rgbaDisposer);
      }
   }

   synchronized void replaceYuv(Y replacement) {
      Y previous = this.yuv;
      if (previous != replacement) {
         this.yuv = replacement;
         dispose(previous, this.yuvDisposer);
      }
   }

   synchronized void replacePacked(P replacement) {
      P previous = this.packed;
      if (previous != replacement) {
         this.packed = replacement;
         dispose(previous, this.packedDisposer);
      }
   }

   synchronized void clear() {
      R previousRgba = this.rgba;
      Y previousYuv = this.yuv;
      P previousPacked = this.packed;
      this.rgba = null;
      this.yuv = null;
      this.packed = null;
      Throwable failure = null;
      failure = disposeCapturing(previousRgba, this.rgbaDisposer, failure);
      failure = disposeCapturing(previousYuv, this.yuvDisposer, failure);
      failure = disposeCapturing(previousPacked, this.packedDisposer, failure);
      rethrow(failure);
   }

   R rgba() {
      return this.rgba;
   }

   Y yuv() {
      return this.yuv;
   }

   P packed() {
      return this.packed;
   }

   boolean hasRgbaOrPacked() {
      return this.rgba != null || this.packed != null;
   }

   boolean hasYuv() {
      return this.yuv != null;
   }

   private static <T> void dispose(T value, Consumer<? super T> disposer) {
      if (value != null) {
         disposer.accept(value);
      }
   }

   private static <T> Throwable disposeCapturing(T value, Consumer<? super T> disposer, Throwable failure) {
      if (value == null) {
         return failure;
      } else {
         try {
            disposer.accept(value);
         } catch (Throwable var4) {
            if (failure == null) {
               return var4;
            }

            failure.addSuppressed(var4);
         }

         return failure;
      }
   }

   private static void rethrow(Throwable failure) {
      if (failure instanceof RuntimeException runtime) {
         throw runtime;
      } else if (failure instanceof Error error) {
         throw error;
      } else if (failure != null) {
         throw new IllegalStateException("legacy preview texture disposal failed", failure);
      }
   }
}
