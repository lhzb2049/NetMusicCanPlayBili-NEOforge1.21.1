package com.zhongbai233.scene_editor.core.transaction;

import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.command.EditorCommand;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class DragTransaction<S> {
   private final S before;
   private S current;
   private final String description;

   public DragTransaction(S before, String description) {
      this.before = Objects.requireNonNull(before, "before");
      this.current = before;
      this.description = Objects.requireNonNull(description, "description");
   }

   public S update(UnaryOperator<S> operation) {
      this.current = Objects.requireNonNull(operation.apply(this.current), "operation result");
      return this.current;
   }

   public boolean changed() {
      return !this.before.equals(this.current);
   }

   public S before() {
      return this.before;
   }

   public S current() {
      return this.current;
   }

   public String description() {
      return this.description;
   }

   public S commit(CommandStack<S> stack) {
      Objects.requireNonNull(stack, "stack");
      if (!this.changed()) {
         return this.current;
      } else {
         final S after = this.current;
         EditorCommand<S> command = new EditorCommand<S>() {
            @Override
            public S apply(S state) {
               return after;
            }

            @Override
            public S undo(S state) {
               return DragTransaction.this.before;
            }

            @Override
            public String description() {
               return DragTransaction.this.description;
            }
         };
         return stack.execute(this.before, command);
      }
   }
}
