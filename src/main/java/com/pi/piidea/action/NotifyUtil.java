package com.pi.piidea.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** 通知工具，统一走 PiSelection 组。 */
final class NotifyUtil {

    private NotifyUtil() {
    }

    static void info(@NotNull Project project, @NotNull String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("PiSelection")
                .createNotification("Pi Agent Selection", message, NotificationType.INFORMATION)
                .notify(project);
    }
}
