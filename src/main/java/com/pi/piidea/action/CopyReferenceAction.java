package com.pi.piidea.action;

import com.pi.piidea.mcp.PiSelectionBundle;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * 复制引用到剪贴板：选区时 {@code /path/File.java#L16-23}，无选区/项目视图为 {@code /path/File.java}。
 * 项目视图多选：每行一条。
 */
public class CopyReferenceAction extends AnAction implements DumbAware {

    public CopyReferenceAction() {
        super(PiSelectionBundle.message("action.copyReference.text"),
              PiSelectionBundle.message("action.copyReference.description"), null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files == null || files.length == 0) return;

        List<String> refs = new ArrayList<>();
        for (VirtualFile file : files) {
            if (!file.isValid()) continue;
            refs.add(ReferenceBuilder.buildReference(e.getData(CommonDataKeys.EDITOR), file));
        }
        if (refs.isEmpty()) return;

        CopyPasteManager.getInstance().setContents(new StringSelection(String.join("\n", refs)));
        if (project != null) {
            NotifyUtil.info(project, PiSelectionBundle.message("action.copyReference.done", refs.size()));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabledAndVisible(files != null && files.length > 0);
    }
}
