package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import com.zhongbai233.scene_editor.core.math.EditorTransform;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.joml.Vector3f;

public record ControlConsoleElement(
   UUID elementId,
   ControlConsoleElement.Type type,
   String name,
   float distance,
   float offsetX,
   float offsetY,
   float height,
   float aspect,
   float yaw,
   float pitch,
   float roll,
   String contentMode,
   String text,
   boolean followLyrics,
   boolean showTranslation,
   float textScale,
   int color,
   float volume,
   int channelIndex,
   float maxDistance,
   boolean autoMixJoc,
   int translationColor,
   int backgroundColor,
   ControlConsoleElement.Alignment alignment,
   float maxWidth,
   boolean wrap,
   boolean enabled,
   boolean locked,
   float scaleX,
   float scaleY,
   float scaleZ,
   float pivotX,
   float pivotY,
   float pivotZ,
   float skewXByY,
   float skewYByX,
   float brightness
) {
   public static final int MAX_NAME_LENGTH = 64;
   public static final int DEFAULT_TRANSLATION_COLOR = -4663041;
   public static final int DEFAULT_BACKGROUND_COLOR = 1073741824;
   public static final float DEFAULT_MAX_WIDTH = 0.0F;
   public static final float DEFAULT_SCALE = 1.0F;
   public static final float MIN_SCALE = 0.05F;
   public static final float MAX_SCALE = 16.0F;
   public static final float MIN_SKEW = -1.0F;
   public static final float MAX_SKEW = 1.0F;
   public static final float MIN_BRIGHTNESS = 0.0F;
   public static final float MAX_BRIGHTNESS = 1.0F;
   public static final float DEFAULT_BRIGHTNESS = 1.0F;

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      int translationColor,
      int backgroundColor,
      ControlConsoleElement.Alignment alignment,
      float maxWidth,
      boolean wrap,
      boolean enabled,
      boolean locked,
      float scaleX,
      float scaleY,
      float scaleZ,
      float pivotX,
      float pivotY,
      float pivotZ,
      float skewXByY,
      float skewYByX,
      float brightness
   ) {
      elementId = Objects.requireNonNull(elementId, "elementId");
      Objects.requireNonNull(type, "type");
      name = Objects.requireNonNull(name, "name").trim();
      if (!name.isEmpty() && name.length() <= 64) {
         validateFinite(distance, "distance");
         validateFinite(offsetX, "offsetX");
         validateFinite(offsetY, "offsetY");
         validatePositive(height, "height");
         validatePositive(aspect, "aspect");
         validatePositive(height * aspect, "width");
         validateFinite(yaw, "yaw");
         validateFinite(pitch, "pitch");
         validateFinite(roll, "roll");
         yaw = normalizeDegrees(yaw);
         pitch = normalizeDegrees(pitch);
         roll = normalizeDegrees(roll);
         contentMode = Objects.requireNonNull(contentMode, "contentMode").trim();
         if (contentMode.isEmpty() || contentMode.length() > 32) {
            throw new IllegalArgumentException("contentMode must contain 1-32 characters");
         } else if (!validContentMode(type, contentMode)) {
            throw new IllegalArgumentException("contentMode is not valid for element type " + type);
         } else {
            text = Objects.requireNonNull(text, "text");
            if (text.length() > 4096) {
               throw new IllegalArgumentException("text must contain at most 4096 characters");
            } else {
               validatePositive(textScale, "textScale");
               validateFinite(volume, "volume");
               validatePositive(maxDistance, "maxDistance");
               alignment = Objects.requireNonNull(alignment, "alignment");
               validateFinite(maxWidth, "maxWidth");
               if (!(volume > 1.0F) && !(volume < 0.0F) && channelIndex >= -1 && channelIndex <= 11 && !(maxWidth < 0.0F)) {
                  validateScale(scaleX, "scaleX");
                  validateScale(scaleY, "scaleY");
                  validateScale(scaleZ, "scaleZ");
                  validateFinite(pivotX, "pivotX");
                  validateFinite(pivotY, "pivotY");
                  validateFinite(pivotZ, "pivotZ");
                  validateSkew(skewXByY, "skewXByY");
                  validateSkew(skewYByX, "skewYByX");
                  if (Float.isFinite(brightness) && !(brightness < 0.0F) && !(brightness > 1.0F)) {
                     this.elementId = elementId;
                     this.type = type;
                     this.name = name;
                     this.distance = distance;
                     this.offsetX = offsetX;
                     this.offsetY = offsetY;
                     this.height = height;
                     this.aspect = aspect;
                     this.yaw = yaw;
                     this.pitch = pitch;
                     this.roll = roll;
                     this.contentMode = contentMode;
                     this.text = text;
                     this.followLyrics = followLyrics;
                     this.showTranslation = showTranslation;
                     this.textScale = textScale;
                     this.color = color;
                     this.volume = volume;
                     this.channelIndex = channelIndex;
                     this.maxDistance = maxDistance;
                     this.autoMixJoc = autoMixJoc;
                     this.translationColor = translationColor;
                     this.backgroundColor = backgroundColor;
                     this.alignment = alignment;
                     this.maxWidth = maxWidth;
                     this.wrap = wrap;
                     this.enabled = enabled;
                     this.locked = locked;
                     this.scaleX = scaleX;
                     this.scaleY = scaleY;
                     this.scaleZ = scaleZ;
                     this.pivotX = pivotX;
                     this.pivotY = pivotY;
                     this.pivotZ = pivotZ;
                     this.skewXByY = skewXByY;
                     this.skewYByX = skewYByX;
                     this.brightness = brightness;
                  } else {
                     throw new IllegalArgumentException("brightness must be within [0, 1]");
                  }
               } else {
                  throw new IllegalArgumentException("element content field is outside its semantic domain");
               }
            }
         }
      } else {
         throw new IllegalArgumentException("element name must contain 1-64 characters");
      }
   }

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      int translationColor,
      int backgroundColor,
      ControlConsoleElement.Alignment alignment,
      float maxWidth,
      boolean wrap,
      boolean enabled,
      boolean locked,
      float scaleX,
      float scaleY,
      float scaleZ,
      float pivotX,
      float pivotY,
      float pivotZ,
      float skewXByY,
      float skewYByX
   ) {
      this(
         elementId,
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         translationColor,
         backgroundColor,
         alignment,
         maxWidth,
         wrap,
         enabled,
         locked,
         scaleX,
         scaleY,
         scaleZ,
         pivotX,
         pivotY,
         pivotZ,
         skewXByY,
         skewYByX,
         1.0F
      );
   }

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      int translationColor,
      int backgroundColor,
      ControlConsoleElement.Alignment alignment,
      float maxWidth,
      boolean wrap,
      boolean enabled,
      boolean locked
   ) {
      this(
         elementId,
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         translationColor,
         backgroundColor,
         alignment,
         maxWidth,
         wrap,
         enabled,
         locked,
         1.0F,
         1.0F,
         1.0F,
         0.0F,
         0.0F,
         0.0F,
         0.0F,
         0.0F
      );
   }

   public ControlConsoleElement(
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll
   ) {
      this(
         UUID.randomUUID(),
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         type == ControlConsoleElement.Type.SCREEN ? "SOURCE" : (type == ControlConsoleElement.Type.SUBTITLE ? "LYRICS" : "SOURCE"),
         "",
         type == ControlConsoleElement.Type.SUBTITLE,
         true,
         1.0F,
         -1,
         1.0F,
         0,
         32.0F,
         false,
         true,
         false
      );
   }

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll
   ) {
      this(
         elementId,
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         type == ControlConsoleElement.Type.SCREEN ? "SOURCE" : (type == ControlConsoleElement.Type.SUBTITLE ? "LYRICS" : "SOURCE"),
         "",
         type == ControlConsoleElement.Type.SUBTITLE,
         true,
         1.0F,
         -1,
         1.0F,
         0,
         32.0F,
         false,
         true,
         false
      );
   }

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      boolean enabled
   ) {
      this(
         elementId,
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         enabled,
         false
      );
   }

   public ControlConsoleElement(
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      boolean enabled
   ) {
      this(
         UUID.randomUUID(),
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         enabled,
         false
      );
   }

   public ControlConsoleElement(
      UUID elementId,
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      boolean enabled,
      boolean locked
   ) {
      this(
         elementId,
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         -4663041,
         1073741824,
         ControlConsoleElement.Alignment.CENTER,
         0.0F,
         false,
         enabled,
         locked
      );
   }

   public ControlConsoleElement(
      ControlConsoleElement.Type type,
      String name,
      float distance,
      float offsetX,
      float offsetY,
      float height,
      float aspect,
      float yaw,
      float pitch,
      float roll,
      String contentMode,
      String text,
      boolean followLyrics,
      boolean showTranslation,
      float textScale,
      int color,
      float volume,
      int channelIndex,
      float maxDistance,
      boolean autoMixJoc,
      int translationColor,
      int backgroundColor,
      ControlConsoleElement.Alignment alignment,
      float maxWidth,
      boolean wrap,
      boolean enabled
   ) {
      this(
         UUID.randomUUID(),
         type,
         name,
         distance,
         offsetX,
         offsetY,
         height,
         aspect,
         yaw,
         pitch,
         roll,
         contentMode,
         text,
         followLyrics,
         showTranslation,
         textScale,
         color,
         volume,
         channelIndex,
         maxDistance,
         autoMixJoc,
         translationColor,
         backgroundColor,
         alignment,
         maxWidth,
         wrap,
         enabled,
         false
      );
   }

   public static ControlConsoleElement defaultScreen() {
      return new ControlConsoleElement(ControlConsoleElement.Type.SCREEN, "主屏幕", 2.2F, 0.0F, 0.05F, 0.75F, 1.7777778F, 0.0F, 0.0F, 0.0F);
   }

   private static void validateFinite(float value, String name) {
      if (!Float.isFinite(value)) {
         throw new IllegalArgumentException(name + " must be finite");
      }
   }

   private static void validatePositive(float value, String name) {
      if (!Float.isFinite(value) || value <= 0.0F) {
         throw new IllegalArgumentException(name + " must be finite and positive");
      }
   }

   private static void validateScale(float value, String name) {
      if (!Float.isFinite(value) || value < 0.05F || value > 16.0F) {
         throw new IllegalArgumentException(name + " must be within [0.05, 16]");
      }
   }

   private static void validateSkew(float value, String name) {
      if (!Float.isFinite(value) || value < -1.0F || value > 1.0F) {
         throw new IllegalArgumentException(name + " must be within [-1, 1]");
      }
   }

   private static float normalizeDegrees(float value) {
      float normalized = value % 360.0F;
      if (normalized >= 180.0F) {
         normalized -= 360.0F;
      } else if (normalized < -180.0F) {
         normalized += 360.0F;
      }

      return normalized == -0.0F ? 0.0F : normalized;
   }

   public EditorTransform editorTransform() {
      return EditorTransform.fromEulerDegrees(
         new Vector3f(this.offsetX, this.offsetY, this.distance),
         this.yaw,
         this.pitch,
         this.roll,
         new Vector3f(this.scaleX, this.scaleY, this.scaleZ),
         new Vector3f(this.pivotX, this.pivotY, this.pivotZ),
         this.skewXByY,
         this.skewYByX
      );
   }

   private static boolean validContentMode(ControlConsoleElement.Type type, String mode) {
      return switch (type) {
         case SCREEN, AUDIO -> "SOURCE".equals(mode);
         case SUBTITLE -> "LYRICS".equals(mode)
            || "FIXED".equals(mode)
            || "SCROLL_MAIN".equals(mode)
            || "SCROLL_TRANSLATION".equals(mode)
            || "AI_SUBTITLE".equals(mode)
            || "LIVE_TITLE".equals(mode)
            || "LIVE_ROOM".equals(mode)
            || "LIVE_STATUS".equals(mode);
      };
   }

   public static enum Alignment {
      LEFT,
      CENTER,
      RIGHT;

      public static ControlConsoleElement.Alignment parse(String value) {
         return valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
      }
   }

   public static enum Type {
      SCREEN,
      SUBTITLE,
      AUDIO;

      public static ControlConsoleElement.Type parse(String value) {
         return valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT));
      }
   }
}
