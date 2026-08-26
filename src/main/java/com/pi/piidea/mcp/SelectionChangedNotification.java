package com.pi.piidea.mcp;

import com.google.gson.annotations.SerializedName;

/**
 * 选区变更通知，通过 WebSocket 推送给 Pi。
 */
public class SelectionChangedNotification {

    @SerializedName("type")
    public String type = "selection_changed";

    @SerializedName("file_path")
    public String filePath;

    @SerializedName("file_name")
    public String fileName;

    @SerializedName("selected_text")
    public String selectedText;

    @SerializedName("start_line")
    public int startLine;

    @SerializedName("end_line")
    public int endLine;

    @SerializedName("start_column")
    public int startColumn;

    @SerializedName("end_column")
    public int endColumn;

    @SerializedName("language")
    public String language;

    @SerializedName("project_path")
    public String projectPath;

    @SerializedName("cursor_line")
    public int cursorLine;

    @SerializedName("cursor_column")
    public int cursorColumn;
}
