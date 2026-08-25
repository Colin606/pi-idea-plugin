package com.fa.piidea;

import com.fa.piidea.mcp.PiServerHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * IDEA 项目打开后确保 Pi 选区服务已启动（单例，多项目共享，见 PiServerHolder）。
 */
public class PiStartupActivity implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        PiServerHolder.get(project);
        return Unit.INSTANCE;
    }
}
