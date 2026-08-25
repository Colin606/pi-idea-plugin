package com.fa.piidea.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/**
 * 本地 HTTP 服务 + 选区监听。
 *
 * - 实时监听编辑器选区变化（300ms 防抖）
 * - 将选区写入 ~/.pi/selection.md（Pi 降级读取方案）
 * - 通过 http://127.0.0.1:19232/api/selection 提供实时查询接口（Pi 扩展优先使用）
 */
public class PiWebSocketServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(PiWebSocketServer.class);
    private static final int DEFAULT_PORT = 19232;
    private static final int DEFAULT_PUSH_PORT = 19233;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Project project;
    private final int port;
    private final ScheduledExecutorService scheduler;
    private final SelectionListener selectionListener;
    private HttpServer httpServer;
    private PiPushServer pushServer;
    private volatile boolean running = false;

    private volatile SelectionChangedNotification currentSelection;
    private volatile ScheduledFuture<?> pendingUpdate;

    public PiWebSocketServer(@NotNull Project project) {
        this(project, DEFAULT_PORT);
    }

    public PiWebSocketServer(@NotNull Project project, int port) {
        this.project = project;
        this.port = port;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pi-Selection-Server");
            t.setDaemon(true);
            return t;
        });

        // 监听编辑器选区变化
        this.selectionListener = new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent e) {
                scheduleSelectionUpdate();
            }
        };
    }

    public void start() {
        if (running) return;

        // 注册选区监听
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(selectionListener, this);

        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            httpServer.setExecutor(Executors.newCachedThreadPool());

            // 查询当前选区
            httpServer.createContext("/api/selection", exchange -> {
                SelectionChangedNotification sel = currentSelection;
                byte[] body = (sel != null ? GSON.toJson(sel) : "{\"type\":\"no_selection\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });

            // 健康检查
            httpServer.createContext("/api/health", exchange -> {
                byte[] body = ("{\"status\":\"ok\",\"port\":" + port + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });

            httpServer.start();
            running = true;
            LOG.info("Pi Selection server started on port " + port);

            // 启动 WebSocket 推送服务（真推送，无需轮询）
            pushServer = new PiPushServer(DEFAULT_PUSH_PORT);
            pushServer.start();

            notify("Pi 选区服务已启动，HTTP " + port + " / WS " + DEFAULT_PUSH_PORT,
                    NotificationType.INFORMATION);
        } catch (IOException e) {
            LOG.error("Failed to start Pi Selection server", e);
            notify("Pi 选区服务启动失败: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    /**
     * 300ms 防抖后采集当前选区。
     */
    private void scheduleSelectionUpdate() {
        ScheduledFuture<?> pending = pendingUpdate;
        if (pending != null) {
            pending.cancel(false);
        }
        pendingUpdate = scheduler.schedule(this::captureSelection, 300, TimeUnit.MILLISECONDS);
    }

    private void captureSelection() {
        ApplicationManager.getApplication().runReadAction(() -> {
            if (project.isDisposed()) return;

            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) return;

            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null) return;

            Document doc = editor.getDocument();
            SelectionModel sel = editor.getSelectionModel();
            String text = sel.getSelectedText();

            SelectionChangedNotification notification = new SelectionChangedNotification();
            notification.filePath = file.getPath();
            notification.fileName = file.getName();
            notification.selectedText = text != null ? text : "";
            notification.language = file.getExtension();
            notification.cursorLine = doc.getLineNumber(editor.getCaretModel().getOffset()) + 1;

            if (text != null && !text.isEmpty()) {
                notification.type = "selection_changed";
                notification.startLine = doc.getLineNumber(sel.getSelectionStart()) + 1;
                notification.endLine = doc.getLineNumber(sel.getSelectionEnd()) + 1;
                notification.startColumn = sel.getSelectionStart()
                        - doc.getLineStartOffset(notification.startLine - 1) + 1;
                notification.endColumn = sel.getSelectionEnd()
                        - doc.getLineStartOffset(notification.endLine - 1) + 1;
                currentSelection = notification;
                // 实时推送给已连接的 Pi 客户端
                PiPushServer push = pushServer;
                if (push != null) {
                    push.broadcastSelection(notification);
                }
                writeSelectionToFile(notification);
            } else {
                // 取消选区时不覆盖文件，保留最后一次选区，但通知客户端清空
                currentSelection = null;
                PiPushServer push = pushServer;
                if (push != null) {
                    push.broadcastSelection(null);
                }
            }
        });
    }

    /**
     * 降级方案：写入 ~/.pi/selection.md，Pi 直接 read 即可。
     */
    private void writeSelectionToFile(SelectionChangedNotification sel) {
        scheduler.submit(() -> {
            try {
                Path dir = Paths.get(System.getProperty("user.home"), ".pi");
                Files.createDirectories(dir);
                Path file = dir.resolve("selection.md");

                StringBuilder sb = new StringBuilder();
                sb.append("<!-- File: ").append(sel.filePath).append(" -->\n");
                sb.append("<!-- Lines: ").append(sel.startLine).append("-").append(sel.endLine).append(" -->\n\n");
                sb.append("```").append(sel.language != null ? sel.language : "").append("\n");
                sb.append(sel.selectedText);
                sb.append("\n```\n");

                Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Failed to write selection file", e);
            }
        });
    }

    private void notify(String message, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("PiSelection")
                    .createNotification("Pi Selection", message, type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void dispose() {
        running = false;
        scheduler.shutdown();
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (pushServer != null) {
            try {
                pushServer.stop(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
