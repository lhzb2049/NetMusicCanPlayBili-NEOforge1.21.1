package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleOperation;
import com.zhongbai233.scene_editor.core.host.EditorHostAdapter;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ControlConsoleHostAdapter implements EditorHostAdapter<ControlConsoleDocument, List<ControlConsoleOperation>> {
   private final Supplier<ControlConsoleDocument> loader;
   private final Consumer<List<ControlConsoleOperation>> submitter;
   private final BiConsumer<Object, ControlConsoleDocument> environmentRenderer;

   public ControlConsoleHostAdapter(
      Supplier<ControlConsoleDocument> loader,
      Consumer<List<ControlConsoleOperation>> submitter,
      BiConsumer<Object, ControlConsoleDocument> environmentRenderer
   ) {
      this.loader = Objects.requireNonNull(loader, "loader");
      this.submitter = Objects.requireNonNull(submitter, "submitter");
      this.environmentRenderer = Objects.requireNonNull(environmentRenderer, "environmentRenderer");
   }

   public ControlConsoleDocument loadDocument() {
      return Objects.requireNonNull(this.loader.get(), "loaded document");
   }

   public EditorHostAdapter.ValidationResult validateDraft(ControlConsoleDocument draft) {
      if (draft == null) {
         return EditorHostAdapter.ValidationResult.rejected("文档不能为空");
      } else {
         return draft.schemaVersion() != 7 ? EditorHostAdapter.ValidationResult.rejected("不支持的中控台文档版本") : EditorHostAdapter.ValidationResult.ok();
      }
   }

   public void submitOperations(List<ControlConsoleOperation> operations) {
      Objects.requireNonNull(operations, "operations");
      if (!operations.isEmpty() && !operations.stream().anyMatch(Objects::isNull)) {
         this.submitter.accept(List.copyOf(operations));
      } else {
         throw new IllegalArgumentException("operations must contain at least one non-null operation");
      }
   }

   public void renderEnvironment(Object renderContext, ControlConsoleDocument draft) {
      this.environmentRenderer.accept(Objects.requireNonNull(renderContext, "renderContext"), Objects.requireNonNull(draft, "draft"));
   }

   public void describeProperties(ControlConsoleDocument draft, Consumer<EditorHostAdapter.PropertyDescriptor> sink) {
      Objects.requireNonNull(draft, "draft");
      Objects.requireNonNull(sink, "sink");
      sink.accept(new EditorHostAdapter.PropertyDescriptor("displayName", "名称", ""));
      sink.accept(new EditorHostAdapter.PropertyDescriptor("hardRangeX", "硬范围 X", "方块"));
      sink.accept(new EditorHostAdapter.PropertyDescriptor("hardRangeY", "硬范围 Y", "方块"));
      sink.accept(new EditorHostAdapter.PropertyDescriptor("hardRangeZ", "硬范围 Z", "方块"));
   }
}
