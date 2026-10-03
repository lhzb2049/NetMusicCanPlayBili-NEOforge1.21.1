package com.zhongbai233.scene_editor.core.host;

import java.util.Objects;
import java.util.function.Consumer;

public interface EditorHostAdapter<D, O> {
   D loadDocument();

   EditorHostAdapter.ValidationResult validateDraft(D var1);

   void submitOperations(O var1);

   void renderEnvironment(Object var1, D var2);

   default void describeProperties(D draft, Consumer<EditorHostAdapter.PropertyDescriptor> sink) {
      Objects.requireNonNull(sink, "sink");
   }

   public record PropertyDescriptor(String id, String label, String unit) {
      public PropertyDescriptor(String id, String label, String unit) {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(label, "label");
         Objects.requireNonNull(unit, "unit");
         this.id = id;
         this.label = label;
         this.unit = unit;
      }
   }

   public record ValidationResult(boolean valid, String message) {
      public ValidationResult(boolean valid, String message) {
         Objects.requireNonNull(message, "message");
         this.valid = valid;
         this.message = message;
      }

      public static EditorHostAdapter.ValidationResult ok() {
         return new EditorHostAdapter.ValidationResult(true, "");
      }

      public static EditorHostAdapter.ValidationResult rejected(String message) {
         return new EditorHostAdapter.ValidationResult(false, message);
      }
   }
}
