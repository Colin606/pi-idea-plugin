package com.pi.piidea.mcp;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动部署 Pi 侧扩展：插件内置 pi-extension/idea-selection.ts（Gradle 打包时拷入
 * jar 的 /pi/ 目录），IDE 启动时比对版本后安装/更新到 ~/.pi/agent/extensions/。
 *
 * - 目标不存在或版本不同 -> 写入（旧文件备份为 .bak，防止用户改动丢失）
 * - 版本一致 -> 跳过，零打扰
 * - 部署后重启 pi 生效
 */
public final class PiExtensionInstaller {

    private static final Logger LOG = Logger.getInstance(PiExtensionInstaller.class);
    private static final Pattern VERSION = Pattern.compile("EXTENSION_VERSION\\s*=\\s*\"([^\"]+)\"");
    private static final String RESOURCE = "/pi/idea-selection.ts";

    private PiExtensionInstaller() {
    }

    /**
     * @return true = 本次实际写入（首次安装或升级）
     */
    public static boolean ensureInstalled(@Nullable Project project) {
        try {
            String bundled = readBundled();
            if (bundled == null) {
                LOG.warn("Bundled pi extension resource missing: " + RESOURCE);
                return false;
            }
            String bundledVersion = extractVersion(bundled);
            if (bundledVersion == null) {
                LOG.warn("Bundled pi extension has no EXTENSION_VERSION marker");
                return false;
            }

            Path dir = Paths.get(System.getProperty("user.home"), ".pi", "agent", "extensions");
            Path target = dir.resolve("idea-selection.ts");

            if (Files.exists(target)) {
                String existing = Files.readString(target, StandardCharsets.UTF_8);
                if (bundledVersion.equals(extractVersion(existing))) {
                    return false; // 已是最新
                }
                Files.createDirectories(dir);
                Files.copy(target, dir.resolve("idea-selection.ts.bak"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(target, bundled, StandardCharsets.UTF_8);
                notify(project, PiSelectionBundle.message("extension.updated", dir), NotificationType.INFORMATION);
                LOG.info("Pi extension updated to " + bundledVersion + " at " + target);
                return true;
            }

            Files.createDirectories(dir);
            Files.writeString(target, bundled, StandardCharsets.UTF_8);
            notify(project, PiSelectionBundle.message("extension.installed", dir), NotificationType.INFORMATION);
            LOG.info("Pi extension " + bundledVersion + " installed to " + target);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to deploy pi extension", e);
            notify(project, PiSelectionBundle.message("extension.deploy.failed", e.getMessage()), NotificationType.WARNING);
            return false;
        }
    }

    @Nullable
    private static String readBundled() throws IOException {
        try (InputStream in = PiExtensionInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nullable
    private static String extractVersion(@NotNull String content) {
        Matcher m = VERSION.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private static void notify(@Nullable Project project, String message, NotificationType type) {
        try {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("PiSelection")
                    .createNotification("Pi Agent Selection", message, type)
                    .notify(project);
        } catch (Exception ignored) {
        }
    }
}
