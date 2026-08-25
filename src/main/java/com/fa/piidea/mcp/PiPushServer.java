package com.fa.piidea.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.diagnostic.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;

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

    @Override
    public void onOpen(@NotNull WebSocket conn, @NotNull ClientHandshake handshake) {
        LOG.info("Pi client connected: " + conn.getRemoteSocketAddress());
        // 新客户端连接时，立即发送当前选区
        SelectionChangedNotification sel = lastSelection;
        conn.send(sel != null ? GSON.toJson(sel) : NO_SELECTION);
    }

    @Override
    public void onClose(@NotNull WebSocket conn, int code, String reason, boolean remote) {
        LOG.info("Pi client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(@NotNull WebSocket conn, @NotNull String message) {
        // 客户端消息忽略（如 {"type":"ping"}）
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
