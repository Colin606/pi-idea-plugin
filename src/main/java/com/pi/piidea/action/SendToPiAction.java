package com.pi.piidea.action;

import com.pi.piidea.mcp.PiSelectionBundle;
import com.pi.piidea.mcp.PiWebSocketServer;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * "发送到 Pi"：把选区/文件引用通过 WebSocket 直接粘贴进 pi 的输入框，
 * 用户在 pi 里对着引用打说明后提交；pi 扩展提交时自动把引用展开为真实代码。
 * pi 未连接时回退：复制到剪贴板。
 */
public class SendToPiAction extends AnAction implements DumbAware {

    public SendToPiAction() {
        super(PiSelectionBundle.message("action.sendToPi.text"),
              PiSelectionBundle.message("action.sendToPi.description"), null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (files == null || files.length == 0) return;

        List<String> refs = ReadAction.compute(() -> {
            List<String> result = new ArrayList<>();
            var editor = e.getData(CommonDataKeys.EDITOR);
            for (VirtualFile file : files) {
                if (file.isValid()) {
                    result.add(ReferenceBuilder.buildReference(editor, file));
                }
            }
            return result;
        });
        if (refs.isEmpty()) return;
        String payload = String.join("\n", refs);

        PiWebSocketServer server = PiWebSocketServer.getInstance(project);
        boolean sent = server != null && server.sendPinToPi(payload);
        if (sent) {
            NotifyUtil.info(project, PiSelectionBundle.message("action.sendToPi.sent", refs.size()));
        } else {
            // pi 未运行/未连接：退化为复制，用户手动粘贴
            CopyPasteManager.getInstance().setContents(new StringSelection(payload));
            NotifyUtil.info(project, PiSelectionBundle.message("action.sendToPi.piOffline", refs.size()));
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        e.getPresentation().setEnabledAndVisible(files != null && files.length > 0);
    }
}
