package com.zhongbai233.scene_editor.core.command;

public interface EditorCommand<S> {
   S apply(S var1);

   S undo(S var1);

   String description();
}
