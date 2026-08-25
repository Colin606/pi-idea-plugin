package com.fa.piidea.mcp;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * 插件文案资源 bundle，跟随 IDE 界面语言。
 */
public final class PiSelectionBundle extends DynamicBundle {

    public static final String BUNDLE_FQN = "messages.PiSelectionBundle";
    private static final PiSelectionBundle INSTANCE = new PiSelectionBundle();

    private PiSelectionBundle() {
        super(BUNDLE_FQN);
    }

    public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE_FQN) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
