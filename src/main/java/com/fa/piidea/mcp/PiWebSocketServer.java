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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/**
 * 本地选区服务（单例，见 PiServerHolder）。
 *
 * - 监听编辑器选区变化：事件发生时即从事件源编辑器采集（分屏/多项目不会取错），
 *   300ms 防抖后更新内存状态并推送；选区状态只存内存，Pi 扩展提交 prompt 时
 *   实时拉取（拉模式，对标 Claude Code IDE 集成），无文件、无过期问题
 * - HTTP :19232/api/selection 实时查询当前选区、/api/health 健康检查
 * - WS   :19233 选区变化即时推送（Pi 扩展 widget 实时显示）
 */
public class PiWebSocketServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(PiWebSocketServer.class);
    private static final int DEFAULT_PORT = 19232;
    private static final int DEFAULT_PUSH_PORT = 19233;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Project project; // 仅用于启动失败通知
    private final int port;
    private final ScheduledExecutorService scheduler;
    private final SelectionListener selectionListener;
    private final ExecutorService httpExecutor;
    private HttpServer httpServer;
    private PiPushServer pushServer;
    private volatile boolean running = false;

    private volatile SelectionChangedNotification currentSelection;
    private volatile SelectionChangedNotification pendingSelection;
    private volatile boolean hasPending = false;
    private volatile ScheduledFuture<?> pendingUpdate;

    public PiWebSocketServer(@Nullable Project project) {
        this.project = project;
        this.port = DEFAULT_PORT;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pi-Selection-Server");
            t.setDaemon(true);
            return t;
        });
        this.httpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Pi-Http-Server");
            t.setDaemon(true);
            return t;
        });

        this.selectionListener = new SelectionListener() {
            @Override
            public void selectionChanged(@NotNull SelectionEvent e) {
                scheduleSelectionUpdate(e.getEditor());
            }
        };
    }

    public boolean isRunning() {
        return running;
    }

    public boolean start() {
        if (running) return true;

        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            httpServer.setExecutor(httpExecutor);

            // 查询当前选区
            httpServer.createContext("/api/selection", exchange -> {
                SelectionChangedNotification sel = currentSelection;
                byte[] body = (sel != null ? GSON.toJson(sel) : "{\"type\":\"no_selection\"}")
                        .getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });

            // 健康检查
            httpServer.createContext("/api/health", exchange -> {
                byte[] body = ("{\"status\":\"ok\",\"port\":" + port + "}")
                        .getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });

            httpServer.start();

            // WebSocket 推送服务（真推送，无需轮询）
            pushServer = new PiPushServer(DEFAULT_PUSH_PORT);
            pushServer.start();

            // 服务全部就绪后再注册选区监听，避免启动失败时残留监听器
            EditorFactory.getInstance().getEventMulticaster().addSelectionListener(selectionListener, this);

            running = true;
            LOG.info("Pi selection server started, HTTP " + port + " / WS " + DEFAULT_PUSH_PORT);
            return true;
        } catch (IOException e) {
            LOG.error("Failed to start Pi selection server", e);
            notifyError(PiSelectionBundle.message("server.start.failed", e.getMessage()));
            return false;
        }
    }

    /**
     * 事件发生时立即采集（事件源编辑器，延迟后编辑器可能已切换），
     * 300ms 防抖只作用于落盘与推送。
     */
    private void scheduleSelectionUpdate(@NotNull Editor editor) {
        ApplicationManager.getApplication().runReadAction(() -> {
            pendingSelection = capture(editor);
            hasPending = true;
        });
        ScheduledFuture<?> pending = pendingUpdate;
        if (pending != null) {
            pending.cancel(false);
        }
        pendingUpdate = scheduler.schedule(this::flush, 300, TimeUnit.MILLISECONDS);
    }

    @Nullable
    private SelectionChangedNotification capture(@NotNull Editor editor) {
        if (editor.isDisposed()) return null;
        Document doc = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(doc);
        if (file == null || !file.isValid()) return null;

        SelectionModel sel = editor.getSelectionModel();
        String text = sel.getSelectedText();
        if (text == null || text.isEmpty()) return null;

        SelectionChangedNotification n = new SelectionChangedNotification();
        n.filePath = file.getPath();
        n.fileName = file.getName();
        n.selectedText = text;
        n.language = file.getExtension();

        int caretOffset = editor.getCaretModel().getOffset();
        n.cursorLine = doc.getLineNumber(caretOffset) + 1;
        n.cursorColumn = caretOffset - doc.getLineStartOffset(n.cursorLine - 1) + 1;

        n.startLine = doc.getLineNumber(sel.getSelectionStart()) + 1;
        n.endLine = doc.getLineNumber(sel.getSelectionEnd()) + 1;
        n.startColumn = sel.getSelectionStart() - doc.getLineStartOffset(n.startLine - 1) + 1;
        n.endColumn = sel.getSelectionEnd() - doc.getLineStartOffset(n.endLine - 1) + 1;
        return n;
    }

    private void flush() {
        if (!hasPending) return;
        hasPending = false;
        SelectionChangedNotification n = pendingSelection;
        pendingSelection = null;

        currentSelection = n;
        PiPushServer push = pushServer;
        if (push != null) {
            push.broadcastSelection(n); // n 为 null 时推送 no_selection
        }
    }

    private void notifyError(String message) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("PiSelection")
                    .createNotification("Pi Selection", message, NotificationType.ERROR)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void dispose() {
        running = false;
        scheduler.shutdownNow();
        if (httpServer != null) {
            httpServer.stop(0);
        }
        httpExecutor.shutdown();
        if (pushServer != null) {
            try {
                pushServer.stop(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
