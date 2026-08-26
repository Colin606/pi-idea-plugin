package com.pi.piidea.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 推送服务（端口 19233）。
 *
 * Pi 扩展通过 WebSocket 长连接订阅选区变化，
 * IDEA 选区一变就立即推送，无需轮询（对标 Claude Code 插件的推送机制）。
 */
public class PiPushServer extends WebSocketServer {

    private static final Logger LOG = Logger.getInstance(PiPushServer.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String NO_SELECTION = "{\"type\":\"no_selection\"}";

    private volatile SelectionChangedNotification lastSelection;

    /** 每个连接（pi 会话）的最近活跃时间：新连接、提交 prompt 时刷新。 */
    private final Map<WebSocket, Long> lastActivity = new ConcurrentHashMap<>();

    public PiPushServer(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setReuseAddr(true);
    }

    /** 选区变化时调用，立即推送给所有连接的 Pi 客户端。 */
    public void broadcastSelection(@Nullable SelectionChangedNotification sel) {
        lastSelection = sel;
        if (getConnections().isEmpty()) return;
        String message = sel != null ? GSON.toJson(sel) : NO_SELECTION;
        broadcast(message);
    }

    /**
     * 把引用粘进最近活跃会话的 pi 输入框（单连接直发；多连接时只发最近活跃/最近打开的）。
     * @return false = 无连接（pi 未运行）。
     */
    public boolean broadcastPin(@NotNull String reference) {
        WebSocket best = null;
        long bestTs = Long.MIN_VALUE;
        for (WebSocket conn : getConnections()) {
            long ts = lastActivity.getOrDefault(conn, 0L);
            if (ts > bestTs) {
                bestTs = ts;
                best = conn;
            }
        }
        if (best == null) return false;
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("type", "pin");
        msg.put("reference", reference);
        best.send(GSON.toJson(msg));
        return true;
    }

    @Override
    public void onOpen(@NotNull WebSocket conn, @NotNull ClientHandshake handshake) {
        LOG.info("Pi client connected: " + conn.getRemoteSocketAddress());
        lastActivity.put(conn, System.currentTimeMillis()); // 新打开的会话优先
        // 新客户端连接时，立即发送当前选区
        SelectionChangedNotification sel = lastSelection;
        conn.send(sel != null ? GSON.toJson(sel) : NO_SELECTION);
    }

    @Override
    public void onClose(@NotNull WebSocket conn, int code, String reason, boolean remote) {
        lastActivity.remove(conn);
        LOG.info("Pi client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(@NotNull WebSocket conn, @NotNull String message) {
        // 活跃上报：{"type":"activity"}（会话启动 / 用户提交 prompt）
        try {
            Map<?, ?> m = GSON.fromJson(message, Map.class);
            if (m != null && "activity".equals(m.get("type"))) {
                lastActivity.put(conn, System.currentTimeMillis());
            }
        } catch (Exception ignored) {
            // 非法消息忽略（如 ping）
        }
    }

    @Override
    public void onError(@NotNull WebSocket conn, @NotNull Exception ex) {
        LOG.warn("Pi push server error", ex);
    }

    @Override
    public void onStart() {
        LOG.info("Pi push WebSocket server started on port " + getPort());
    }
}
