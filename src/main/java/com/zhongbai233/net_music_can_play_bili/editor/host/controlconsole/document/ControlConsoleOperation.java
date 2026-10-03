package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.util.Objects;

public sealed interface ControlConsoleOperation permits ControlConsoleOperation.ReplaceDocument {
   public record ReplaceDocument(long expectedRevision, ControlConsoleDocument draft) implements ControlConsoleOperation {
      public ReplaceDocument(long expectedRevision, ControlConsoleDocument draft) {
         if (expectedRevision < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
         } else {
            Objects.requireNonNull(draft, "draft");
            if (draft.revision() != expectedRevision) {
               throw new IllegalArgumentException("draft revision must match expectedRevision");
            } else {
               this.expectedRevision = expectedRevision;
               this.draft = draft;
            }
         }
      }
   }
}
