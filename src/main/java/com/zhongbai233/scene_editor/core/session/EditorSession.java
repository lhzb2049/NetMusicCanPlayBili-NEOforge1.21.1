package com.zhongbai233.scene_editor.core.session;

import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;
import com.zhongbai233.scene_editor.core.selection.MultiSelectionPolicy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class EditorSession<E extends SceneElement> implements AutoCloseable {
   private final CommandStack<SceneDocument<E>> commands;
   private SceneDocument<E> document;
   private EditorCameraState camera;
   private EditorViewport viewport;
   private final LinkedHashSet<UUID> selectedElementIds = new LinkedHashSet<>();
   private UUID primaryElementId;
   private boolean closed;

   private EditorSession(SceneDocument<E> document, EditorCameraState camera, EditorViewport viewport, int historyCapacity) {
      this.document = Objects.requireNonNull(document, "document");
      this.camera = Objects.requireNonNull(camera, "camera");
      this.viewport = Objects.requireNonNull(viewport, "viewport");
      this.commands = new CommandStack<>(historyCapacity);
   }

   public static <E extends SceneElement> EditorSession<E> open(
      SceneDocument<E> document, EditorCameraState camera, EditorViewport viewport, int historyCapacity
   ) {
      return new EditorSession<>(document, camera, viewport, historyCapacity);
   }

   public SceneDocument<E> document() {
      this.requireOpen();
      return this.document;
   }

   public void document(SceneDocument<E> document) {
      this.requireOpen();
      this.document = Objects.requireNonNull(document, "document");
      this.selectedElementIds.removeIf(id -> document.element(id).isEmpty());
      if (this.primaryElementId != null && !this.selectedElementIds.contains(this.primaryElementId)) {
         this.primaryElementId = this.selectedElementIds.isEmpty() ? null : this.selectedElementIds.getLast();
      }
   }

   public CommandStack<SceneDocument<E>> commands() {
      this.requireOpen();
      return this.commands;
   }

   public EditorCameraState camera() {
      this.requireOpen();
      return this.camera;
   }

   public void camera(EditorCameraState camera) {
      this.requireOpen();
      this.camera = Objects.requireNonNull(camera, "camera");
   }

   public EditorViewport viewport() {
      this.requireOpen();
      return this.viewport;
   }

   public void resize(EditorViewport viewport) {
      this.requireOpen();
      this.viewport = Objects.requireNonNull(viewport, "viewport");
   }

   public Optional<UUID> selectedElementId() {
      this.requireOpen();
      return Optional.ofNullable(this.primaryElementId);
   }

   public List<UUID> selectedElementIds() {
      this.requireOpen();
      return List.copyOf(this.selectedElementIds);
   }

   public void select(UUID elementId) {
      this.requireOpen();
      Objects.requireNonNull(elementId, "elementId");
      if (this.document.element(elementId).isEmpty()) {
         throw new IllegalArgumentException("scene element is not part of this document: " + elementId);
      } else {
         this.selectedElementIds.clear();
         this.selectedElementIds.add(elementId);
         this.primaryElementId = elementId;
      }
   }

   public void select(Collection<UUID> elementIds, UUID primaryId) {
      this.requireOpen();
      Objects.requireNonNull(elementIds, "elementIds");
      LinkedHashSet<UUID> checked = new LinkedHashSet<>(elementIds);

      for (UUID id : checked) {
         if (this.document.element(Objects.requireNonNull(id, "elementId")).isEmpty()) {
            throw new IllegalArgumentException("scene element is not part of this document: " + id);
         }
      }

      if (primaryId != null && !checked.contains(primaryId)) {
         throw new IllegalArgumentException("primary element must be selected");
      } else {
         this.selectedElementIds.clear();
         this.selectedElementIds.addAll(checked);
         this.primaryElementId = primaryId;
      }
   }

   public void clickSelect(UUID clickedElementId, boolean shiftDown, boolean controlDown) {
      this.requireOpen();
      List<UUID> orderedIds = this.document.elements().stream().map(SceneElement::id).toList();
      MultiSelectionPolicy.Result result = MultiSelectionPolicy.click(
         orderedIds, this.selectedElementIds, this.primaryElementId, clickedElementId, shiftDown, controlDown
      );
      this.select(result.selectedElementIds(), result.primaryElementId());
   }

   public void clearSelection() {
      this.requireOpen();
      this.selectedElementIds.clear();
      this.primaryElementId = null;
   }

   public boolean isClosed() {
      return this.closed;
   }

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         this.selectedElementIds.clear();
         this.primaryElementId = null;
         this.commands.clear();
      }
   }

   private void requireOpen() {
      if (this.closed) {
         throw new IllegalStateException("editor session is closed");
      }
   }
}
