package com.fa.piidea.mcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 推送服务单例：多项目窗口共享同一实例，避免端口冲突与重复启动。
 * 注册到 application 级 Disposable，随 IDE 退出销毁。
 */
public final class PiServerHolder {

    private static final Logger LOG = Logger.getInstance(PiServerHolder.class);
    private static volatile PiWebSocketServer instance;

    private PiServerHolder() {
    }

    @NotNull
    public static PiWebSocketServer get(@Nullable Project project) {
        PiWebSocketServer server = instance;
        if (server != null && server.isRunning()) return server;
        synchronized (PiServerHolder.class) {
            server = instance;
            if (server != null && server.isRunning()) return server;
            server = new PiWebSocketServer(project);
            if (server.start()) {
                Disposer.register(ApplicationManager.getApplication(), server);
                instance = server;
            } else {
                // 启动失败（如端口被占）不缓存，下次打开项目重试
                LOG.warn("Pi selection server failed to start; will retry on next project open");
            }
            return server;
        }
    }
}
