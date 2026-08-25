package com.fa.piidea;

import com.fa.piidea.mcp.PiWebSocketServer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

/**
 * IDEA 项目打开后自动启动 Pi 选区推送服务。
 */
public class PiStartupActivity implements StartupActivity.Background {

    @Override
    public void runActivity(@NotNull Project project) {
        PiWebSocketServer server = new PiWebSocketServer(project);
        Disposer.register(project, server);
        server.start();
    }
}
