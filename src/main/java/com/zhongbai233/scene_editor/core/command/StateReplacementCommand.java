package com.zhongbai233.scene_editor.core.command;

import java.util.Objects;

public record StateReplacementCommand<S>(S before, S after, String description) implements EditorCommand<S> {
   public StateReplacementCommand(S before, S after, String description) {
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      description = Objects.requireNonNull(description, "description");
      this.before = before;
      this.after = after;
      this.description = description;
   }

   @Override
   public S apply(S ignored) {
      return this.after;
   }

   @Override
   public S undo(S ignored) {
      return this.before;
   }
}
