// @ts-nocheck
// 本文件作为独立扩展在 pi（全局安装）环境中运行，不依赖本仓库的 node_modules；
// 类型来自 @earendil-works/pi-coding-agent 全局安装，IDE 本地解析不到，故忽略检查。
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { basename } from "node:path";

/**
 * pi-idea-extension
 * 与 IDEA 插件（pi-idea-plugin）配合，机制对标 Claude Code IDE 集成：
 * 1. 提交 prompt 时通过 HTTP 实时拉取当前选区（拉模式，读的是内存实时状态，
 *    天然不会过期/残留），注入 <idea-selection> 上下文；
 * 2. 同一选区只注入一次（内容比对跳过），新会话自动重置；
 * 3. TUI 编辑区上方以 widget 实时显示当前选中（WebSocket 推送）。
 */

const SELECTION_API = "http://127.0.0.1:19232/api/selection";
const WS_URL = "ws://127.0.0.1:19233";
const WIDGET_ID = "idea-selection";
const RECONNECT_MS = 3000;

interface SelectionPayload {
  type: string;
  file_path: string;
  file_name: string;
  selected_text: string;
  start_line: number;
  end_line: number;
  language: string | null;
}

/** 选区数据转 markdown（与旧 selection.md 格式一致）。 */
function toMarkdown(s: SelectionPayload): string {
  return [
    `<!-- File: ${s.file_path} -->`,
    `<!-- Lines: ${s.start_line}-${s.end_line} -->`,
    "",
    "```" + (s.language ?? ""),
    s.selected_text,
    "```",
  ].join("\n");
}

function summarize(s: SelectionPayload): string {
  return `IDEA selection: ${basename(s.file_path)} (${s.start_line}-${s.end_line})`;
}

/**
 * 拉取当前选区。
 * 返回 undefined = 插件不可达；null = 无选区；payload = 当前选区。
 */
async function fetchSelection(): Promise<SelectionPayload | null | undefined> {
  try {
    const res = await fetch(SELECTION_API, { signal: AbortSignal.timeout(500) });
    if (!res.ok) return undefined;
    const data = await res.json();
    return data && data.type === "selection_changed" ? data : null;
  } catch {
    return undefined;
  }
}

export default function (pi: ExtensionAPI) {
  let ws: WebSocket | undefined;
  let reconnectTimer: NodeJS.Timeout | undefined;
  let shuttingDown = false;
  let widgetCtx: any;
  // 上次已注入的内容（会话级）：相同选区跳过，避免重复占用上下文
  let lastInjected: string | undefined;

  const setWidgetLines = (lines: string[]) => {
    widgetCtx?.ui.setWidget(WIDGET_ID, lines);
  };

  const connect = () => {
    if (shuttingDown || ws) return;
    try {
      ws = new WebSocket(WS_URL);
    } catch {
      scheduleReconnect();
      return;
    }
    ws.onmessage = (ev: MessageEvent) => {
      try {
        const data = JSON.parse(String(ev.data));
        setWidgetLines(data?.type === "selection_changed" ? [summarize(data)] : []);
      } catch {
        // 非法消息忽略
      }
    };
    ws.onerror = () => {
      try { ws?.close(); } catch { /* ignore */ }
    };
    ws.onclose = () => {
      ws = undefined;
      if (!shuttingDown) scheduleReconnect();
    };
  };

  const scheduleReconnect = () => {
    if (shuttingDown || reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined;
      connect();
    }, RECONNECT_MS);
  };

  // 每次用户提交 prompt 前：拉取当前选区，未注入过的才注入（一次选中一次注入）
  pi.on("before_agent_start", async (_event, _ctx) => {
    const sel = await fetchSelection();
    if (!sel) return;
    const content = toMarkdown(sel);
    if (content === lastInjected) return;
    lastInjected = content;
    return {
      message: {
        customType: "idea-selection",
        content: `<idea-selection>\n${content}\n</idea-selection>`,
        display: false,
      },
    };
  });

  pi.on("session_start", async (_event, ctx) => {
    shuttingDown = false;
    lastInjected = undefined; // 新会话重置：允许重新注入
    connect();
    if (ctx.mode === "tui") {
      widgetCtx = ctx;
      setWidgetLines([]);
    }
  });

  pi.on("session_shutdown", async () => {
    shuttingDown = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
    try { ws?.close(); } catch { /* ignore */ }
    ws = undefined;
    widgetCtx = undefined;
  });

  // 手动查看当前选区与注入状态
  pi.registerCommand("selection", {
    description: "查看 IDEA 当前选区及注入状态",
    handler: async (_args, ctx) => {
      const sel = await fetchSelection();
      if (sel === undefined) {
        ctx.ui.notify("IDEA 插件不可达（IDEA 未启动或插件未安装？）", "warn");
        return;
      }
      if (!sel) {
        ctx.ui.notify("（无 IDEA 选中内容）", "info");
        return;
      }
      const content = toMarkdown(sel);
      const state = content === lastInjected ? "已注入过，相同选区后续跳过" : "将在下次提问时注入";
      ctx.ui.notify(`[${state}]\n${content.slice(0, 800)}`, "info");
    },
  });
}
