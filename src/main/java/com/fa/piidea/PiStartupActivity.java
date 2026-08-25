package com.fa.piidea;

import com.fa.piidea.mcp.PiWebSocketServer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * IDEA 项目打开后启动本项目的 Pi 选区服务（每项目一个实例，端口自动分配，
 * 多项目窗口/多 IDE 进程互相隔离，见 PiWebSocketServer）。
 */
public class PiStartupActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        PiWebSocketServer server = new PiWebSocketServer(project);
        if (server.start()) {
            Disposer.register(project, server); // 项目关闭即停服务、释放端口、删锁文件
        }
        // 启动失败（端口耗尽等）已通知用户；重开项目时会重试
        return Unit.INSTANCE;
    }
}
