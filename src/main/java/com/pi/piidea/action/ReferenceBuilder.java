package com.pi.piidea.action;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 从编辑器/文件构建 {@code @/path/File.java#L16-23} 形式的引用。
 * 行尾处理：选区末端落在行首时 endLine--（避免多算一行）。
 * 编辑器选区只对编辑器当前打开的文件生效（文件不匹配时为文件级引用）。
 */
public final class ReferenceBuilder {

    private ReferenceBuilder() {
    }

    /** 选区引用（带行号）；无选区或文件不匹配为文件级引用。 */
    public static String buildReference(@Nullable Editor editor, @NotNull VirtualFile file) {
        if (!isEditorFile(editor, file)) return "@" + file.getPath();
        SelectionModel sel = editor.getSelectionModel();
        if (!sel.hasSelection()) return "@" + file.getPath();

        int[] lines = selectionLines(editor, sel);
        return "@" + file.getPath()
                + (lines[0] == lines[1] ? "#L" + lines[0] : "#L" + lines[0] + "-" + lines[1]);
    }

    /** 编辑器是否恰好打开该文件。 */
    private static boolean isEditorFile(@Nullable Editor editor, @NotNull VirtualFile file) {
        if (editor == null || editor.isDisposed()) return false;
        VirtualFile editorFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file.equals(editorFile);
    }

    /** 选区 [startLine, endLine]（1-based）；末端落在行首时 endLine--，避免多算一行。 */
    private static int[] selectionLines(@NotNull Editor editor, @NotNull SelectionModel sel) {
        Document doc = editor.getDocument();
        int start = doc.getLineNumber(sel.getSelectionStart()) + 1;
        int end = doc.getLineNumber(sel.getSelectionEnd()) + 1;
        if (end > start && editor.offsetToLogicalPosition(sel.getSelectionEnd()).column == 0) {
            end--;
        }
        return new int[]{start, end};
    }
}
