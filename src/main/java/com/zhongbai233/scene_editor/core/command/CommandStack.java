package com.zhongbai233.scene_editor.core.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class CommandStack<S> {
   private final int capacity;
   private final Deque<EditorCommand<S>> undo = new ArrayDeque<>();
   private final Deque<EditorCommand<S>> redo = new ArrayDeque<>();

   public CommandStack(int capacity) {
      if (capacity <= 0) {
         throw new IllegalArgumentException("capacity must be positive");
      } else {
         this.capacity = capacity;
      }
   }

   public S execute(S state, EditorCommand<S> command) {
      EditorCommand<S> required = Objects.requireNonNull(command, "command");
      S result = Objects.requireNonNull(required.apply(state), "command result");
      this.undo.addLast(required);

      while (this.undo.size() > this.capacity) {
         this.undo.removeFirst();
      }

      this.redo.clear();
      return result;
   }

   public S undo(S state) {
      if (this.undo.isEmpty()) {
         return state;
      } else {
         EditorCommand<S> command = this.undo.removeLast();
         S result = Objects.requireNonNull(command.undo(state), "undo result");
         this.redo.addLast(command);
         return result;
      }
   }

   public S redo(S state) {
      if (this.redo.isEmpty()) {
         return state;
      } else {
         EditorCommand<S> command = this.redo.removeLast();
         S result = Objects.requireNonNull(command.apply(state), "redo result");
         this.undo.addLast(command);
         return result;
      }
   }

   public boolean canUndo() {
      return !this.undo.isEmpty();
   }

   public boolean canRedo() {
      return !this.redo.isEmpty();
   }

   public int size() {
      return this.undo.size() + this.redo.size();
   }

   public int capacity() {
      return this.capacity;
   }

   public void clear() {
      this.undo.clear();
      this.redo.clear();
   }
}
