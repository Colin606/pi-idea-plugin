import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { existsSync, readFileSync, statSync, watch, type FSWatcher } from "node:fs";
import { homedir } from "node:os";
import { basename, join } from "node:path";

/**
 * pi-idea-extension
 * IDEA 插件（pi-idea-plugin）会把用户当前选中的代码写入 ~/.pi/selection.md，
 * 本扩展：
 * 1. 在用户提交 prompt 时以 <idea-selection> 标记注入上下文；
 * 2. 在 TUI 编辑区上方以 widget 实时显示当前选中的文件与行号。
 */

const SELECTION_FILE = join(homedir(), ".pi", "selection.md");
const WIDGET_ID = "idea-selection";
// 超过 24 小时的选中视为过期，避免注入陈旧内容
const MAX_AGE_MS = 24 * 60 * 60 * 1000;

function readSelection(): string | null {
  try {
    if (!existsSync(SELECTION_FILE)) return null;
    if (Date.now() - statSync(SELECTION_FILE).mtimeMs > MAX_AGE_MS) return null;
    const content = readFileSync(SELECTION_FILE, "utf8").trim();
    return content || null;
  } catch {
    return null;
  }
}

// 从 selection.md 头部注释提取文件与行号，生成一行摘要
function summarize(selection: string): string {
  const fileMatch = selection.match(/<!-- File: (.+?) -->/);
  const lineMatch = selection.match(/<!-- Lines: (.+?) -->/);
  const file = fileMatch ? basename(fileMatch[1].trim()) : "unknown";
  const lines = lineMatch ? lineMatch[1].trim() : "?";
  return `IDEA selection: ${file} (${lines})`;
}

export default function (pi: ExtensionAPI) {
  let watcher: FSWatcher | undefined;
  let debounceTimer: NodeJS.Timeout | undefined;

  // 每次用户提交 prompt 前，注入 IDEA 当前选中的代码
  pi.on("before_agent_start", async (_event, _ctx) => {
    const selection = readSelection();
    if (!selection) return;
    return {
      message: {
        customType: "idea-selection",
        content: `<idea-selection>\n${selection}\n</idea-selection>`,
        display: false,
      },
    };
  });

  // TUI 下监听 selection.md 变化，在编辑区上方实时显示当前选中
  pi.on("session_start", async (_event, ctx) => {
    if (ctx.mode !== "tui") return;

    const refreshWidget = () => {
      const selection = readSelection();
      ctx.ui.setWidget(WIDGET_ID, selection ? [summarize(selection)] : []);
    };

    refreshWidget();
    try {
      watcher = watch(SELECTION_FILE, () => {
        // 防抖，IDEA 写入可能触发多次事件
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(refreshWidget, 150);
      });
    } catch {
      // 文件不存在等情况，widget 保持初始状态即可
    }
  });

  pi.on("session_shutdown", async () => {
    watcher?.close();
    watcher = undefined;
    clearTimeout(debounceTimer);
  });

  // 手动查看当前选中内容
  pi.registerCommand("selection", {
    description: "查看 IDEA 当前注入的选中代码",
    handler: async (_args, ctx) => {
      const selection = readSelection();
      ctx.ui.notify(selection ? selection.slice(0, 800) : "（无 IDEA 选中内容）", "info");
    },
  });
}
