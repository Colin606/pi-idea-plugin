package com.pi.piidea.mcp;

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
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/**
 * 本地选区服务（每个项目窗口一个实例，端口自动分配，见 PiStartupActivity）。
 *
 * - 监听本项目编辑器的选区变化：事件发生时即从事件源编辑器采集（分屏/多项目不会取错），
 *   300ms 防抖后更新内存状态并推送；只跟踪本项目窗口的编辑器，多项目/多 IDE 完全隔离
 * - 端口：从 19232/19233 起，每个项目窗口占用一对（HTTP 19232+2i / WS 19233+2i），
 *   并在 ~/.pi/ide/ 下写锁文件注册端口与项目路径（心跳 5s），Pi 扩展按 cwd 匹配项目
 * - HTTP /api/selection 实时查询当前选区、/api/health 健康检查
 * - WS   选区变化即时推送（Pi 扩展 widget 实时显示）
 */
public class PiWebSocketServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(PiWebSocketServer.class);
    private static final int BASE_HTTP_PORT = 19232;
    private static final int BASE_PUSH_PORT = 19233;
    /** 最多支持 10 个项目窗口并行（两个 IDE 进程各开多项目也够用） */
    private static final int MAX_PORT_ATTEMPTS = 10;
    private static final Path LOCK_DIR = Paths.get(System.getProperty("user.home"), ".pi", "ide");
    private static final long HEARTBEAT_MS = 5000L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Project project;
    private final ScheduledExecutorService scheduler;
    private final SelectionListener selectionListener;
    private final ExecutorService httpExecutor;
    private HttpServer httpServer;
    private PiPushServer pushServer;
    private volatile boolean running = false;
    private int port = -1;
    private int pushPort = -1;
    private Path lockFile;
    private ScheduledFuture<?> heartbeat;

    private volatile SelectionChangedNotification currentSelection;
    private volatile SelectionChangedNotification pendingSelection;
    private volatile boolean hasPending = false;
    private volatile ScheduledFuture<?> pendingUpdate;

    private static final Key<PiWebSocketServer> INSTANCE_KEY = Key.create("PiWebSocketServer");

    /** 本项目窗口的服务实例（启动成功后可取，供 action 发送引用）。 */
    public static @Nullable PiWebSocketServer getInstance(@Nullable Project project) {
        return project == null ? null : project.getUserData(INSTANCE_KEY);
    }

    public PiWebSocketServer(@NotNull Project project) {
        this.project = project;
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
                // 只跟踪本项目窗口的编辑器，其他项目窗口的事件交给各自的实例
                Project p = e.getEditor().getProject();
                if (p != null && p != PiWebSocketServer.this.project) return;
                scheduleSelectionUpdate(e.getEditor());
            }
        };
    }

    public boolean isRunning() {
        return running;
    }

    /** 当前 HTTP 端口（未启动为 -1，用于日志/诊断）。 */
    public int getPort() {
        return port;
    }

    public boolean start() {
        if (running) return true;
        synchronized (PiWebSocketServer.class) {
            if (running) return true;
            for (int i = 0; i < MAX_PORT_ATTEMPTS; i++) {
                int httpPort = BASE_HTTP_PORT + i * 2;
                int wsPort = BASE_PUSH_PORT + i * 2;
                if (!tryStart(httpPort, wsPort)) continue;
                port = httpPort;
                pushPort = wsPort;
                lockFile = LOCK_DIR.resolve("ide-" + httpPort + ".lock");
                heartbeat = scheduler.scheduleAtFixedRate(this::writeLock, 0, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                running = true;
                project.putUserData(INSTANCE_KEY, this);
                LOG.info("Pi selection server started for project " + project.getName()
                        + ", HTTP " + port + " / WS " + pushPort + " (" + lockFile + ")");
                return true;
            }
        }
        String range = BASE_HTTP_PORT + "-" + (BASE_HTTP_PORT + (MAX_PORT_ATTEMPTS - 1) * 2);
        LOG.warn("Pi selection server failed to start: no free port in range " + range);
        notifyError(PiSelectionBundle.message("server.start.failed", "no free port in " + range));
        return false;
    }

    /** 尝试占用一对端口并启动两个服务，任一失败则回滚并返回 false。 */
    private boolean tryStart(int httpPort, int wsPort) {
        if (!portAvailable(httpPort) || !portAvailable(wsPort)) return false;
        HttpServer http = null;
        PiPushServer push = null;
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 0);
            http.setExecutor(httpExecutor);

            // 查询当前选区
            http.createContext("/api/selection", exchange -> {
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
            http.createContext("/api/health", exchange -> {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("status", "ok");
                resp.put("port", httpPort);
                resp.put("wsPort", wsPort);
                resp.put("projectPath", project.getBasePath());
                byte[] body = GSON.toJson(resp).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });

            http.start();
            push = new PiPushServer(wsPort);
            push.start();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Pi selection server failed to bind " + httpPort + "/" + wsPort + ": " + e.getMessage());
            if (push != null) {
                try { push.stop(100); } catch (Exception ignored) { }
            }
            if (http != null) {
                http.stop(0);
            }
            return false;
        }
        httpServer = http;
        pushServer = push;

        // 服务全部就绪后再注册选区监听，避免启动失败时残留监听器
        EditorFactory.getInstance().getEventMulticaster().addSelectionListener(selectionListener, this);
        return true;
    }

    /** 把引用粘贴进 pi 输入框（见 SendToPiAction）。 @return false = 服务未启动或 pi 未连接。 */
    public boolean sendPinToPi(@NotNull String reference) {
        PiPushServer push = pushServer;
        return running && push != null && push.broadcastPin(reference);
    }

    /** 端口预检：能绑定即视为空闲（本机场景，竞态窗口极小）。 */
    private static boolean portAvailable(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 写注册锁文件（心跳），Pi 扩展靠它发现本实例。 */
    private void writeLock() {
        if (!running) return;
        try {
            Files.createDirectories(LOCK_DIR);
            Map<String, Object> lock = new LinkedHashMap<>();
            lock.put("version", 1);
            lock.put("httpPort", port);
            lock.put("wsPort", pushPort);
            lock.put("projectPath", project.getBasePath());
            lock.put("projectName", project.getName());
            lock.put("pid", ProcessHandle.current().pid());
            lock.put("updatedAt", System.currentTimeMillis());
            Path tmp = lockFile.resolveSibling(lockFile.getFileName() + ".tmp");
            Files.write(tmp, GSON.toJson(lock).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, lockFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, lockFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.warn("Failed to write Pi lock file: " + e.getMessage());
        }
    }

    /**
     * 事件发生时立即采集（事件源编辑器，延迟后编辑器可能已切换），
     * 300ms 防抖只作用于状态更新与推送。
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
        boolean hasSelection = text != null && !text.isEmpty();
        String selectedText = hasSelection ? text : "";

        SelectionChangedNotification n = new SelectionChangedNotification();
        n.filePath = file.getPath();
        n.fileName = file.getName();
        n.selectedText = selectedText;
        n.language = file.getExtension();
        n.projectPath = project.getBasePath();

        int caretOffset = editor.getCaretModel().getOffset();
        n.cursorLine = doc.getLineNumber(caretOffset) + 1;
        n.cursorColumn = caretOffset - doc.getLineStartOffset(n.cursorLine - 1) + 1;

        // 无选区（光标点击）：行号退化为光标行，仍上报文件级上下文
        n.startLine = hasSelection ? doc.getLineNumber(sel.getSelectionStart()) + 1 : n.cursorLine;
        n.endLine = hasSelection ? doc.getLineNumber(sel.getSelectionEnd()) + 1 : n.cursorLine;
        n.startColumn = hasSelection ? sel.getSelectionStart() - doc.getLineStartOffset(n.startLine - 1) + 1 : n.cursorColumn;
        n.endColumn = hasSelection ? sel.getSelectionEnd() - doc.getLineStartOffset(n.endLine - 1) + 1 : n.cursorColumn;
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
                    .createNotification("Pi Agent Selection", message, NotificationType.ERROR)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void dispose() {
        running = false;
        project.putUserData(INSTANCE_KEY, null);
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
        if (lockFile != null) {
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException e) {
                LOG.warn("Failed to delete Pi lock file: " + e.getMessage());
            }
        }
    }
}
