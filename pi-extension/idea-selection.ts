// @ts-nocheck
// 本文件作为独立扩展在 pi（全局安装）环境中运行，不依赖本仓库的 node_modules；
// 类型来自 @earendil-works/pi-coding-agent 全局安装，IDE 本地解析不到，故忽略检查。
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { basename } from "node:path";
import { homedir } from "node:os";
import { join } from "node:path";
import { readdirSync, readFileSync, realpathSync } from "node:fs";

/**
 * pi-idea-extension
 * 与 IDEA 插件（pi-idea-plugin）配合，机制对标 Claude Code IDE 集成：
 * 1. 通过 ~/.pi/ide/*.lock 发现每个 IDEA 项目窗口的选区服务（多 IDE 隔离，端口自动分配），
 *    按 cwd 匹配所属项目，取不到时退到最近活跃的实例；
 * 2. 提交 prompt 时通过 HTTP 实时拉取当前选区（拉模式，读的是内存实时状态，
 *    天然不会过期/残留），注入 <idea-selection> 上下文；
 * 3. 同一选区只注入一次（内容比对跳过），新会话自动重置；
 * 4. TUI 编辑区上方以 widget 实时显示当前选中（WebSocket 推送）。
 */

const LOCK_DIR = join(homedir(), ".pi", "ide");
/** 与插件版本同步递增，插件用它判断是否需要更新已部署的扩展 */
const EXTENSION_VERSION = "1.3.3";
const BASE_HTTP_PORT = 19232;
const BASE_WS_PORT = 19233;
const WIDGET_ID = "idea-selection";
const RECONNECT_MS = 3000;
/** 锁文件心跳间隔 5s，超过 15s 未更新视为实例已死 */
const LOCK_STALE_MS = 15000;

interface SelectionPayload {
  type: string;
  file_path: string;
  file_name: string;
  selected_text: string;
  start_line: number;
  end_line: number;
  language: string | null;
  project_path?: string | null;
}

interface IdeInstance {
  httpPort: number;
  wsPort: number;
  projectPath: string | null;
  projectName: string | null;
  updatedAt: number;
}

/** 单条引用最多展开的行数，防止一次注入撑爆上下文 */
const MAX_EXPAND_LINES = 400;
/** 单次提交最多展开的引用条数 */
const MAX_EXPAND_REFS = 20;
const REF_RE = /@((?:[A-Za-z]:)?[/\\][^\s@]*?)#L(\d+)(?:-(\d+))?/g;
const REF_TEST = /@(?:[A-Za-z]:)?[/\\][^\s@]*?#L\d+(?:-\d+)?/;

/** 把提交文本里的 @/path/File.java#L16-23 展开为真实代码（原引用保留）。 */
function expandReferences(text: string): string {
  let count = 0;
  return text.replace(REF_RE, (whole, path: string, s: string, maybeE: string | undefined) => {
    if (++count > MAX_EXPAND_REFS) return whole;
    const start = Number(s);
    const end = maybeE ? Number(maybeE) : start;
    if (!(start >= 1 && end >= start && end - start + 1 <= MAX_EXPAND_LINES)) return whole;
    let content: string;
    try {
      const lines = readFileSync(path, "utf8").split(/\r?\n/);
      if (end > lines.length) return whole;
      content = lines.slice(start - 1, end).join("\n");
    } catch {
      return whole; // 文件读不到（不存在/权限），保留原引用
    }
    const ext = path.lastIndexOf(".") > path.lastIndexOf("/") ? path.slice(path.lastIndexOf(".") + 1) : "";
    return `${whole}\n\`\`\`${ext}\n${content}\n\`\`\``;
  });
}

/** 扫描 ~/.pi/ide/*.lock，过滤掉心跳过期的实例。 */
function discoverInstances(): IdeInstance[] {
  let files: string[];
  try {
    files = readdirSync(LOCK_DIR).filter((f) => f.endsWith(".lock"));
  } catch {
    return []; // 目录不存在 = 插件没装 / 没有 IDE 开着
  }
  const now = Date.now();
  const instances: IdeInstance[] = [];
  for (const f of files) {
    try {
      const lock = JSON.parse(readFileSync(join(LOCK_DIR, f), "utf8"));
      const updatedAt = Number(lock.updatedAt) || 0;
      if (now - updatedAt > LOCK_STALE_MS) continue; // 死实例
      instances.push({
        httpPort: Number(lock.httpPort) || 0,
        wsPort: Number(lock.wsPort) || 0,
        projectPath: lock.projectPath ?? null,
        projectName: lock.projectName ?? null,
        updatedAt,
      });
    } catch {
      // 单个坏锁文件跳过
    }
  }
  return instances.filter((i) => i.httpPort > 0 && i.wsPort > 0);
}

function norm(p: string | null): string | null {
  if (!p) return null;
  const real = (() => {
    try {
      return realpathSync(p);
    } catch {
      return p;
    }
  })();
  return real.toLowerCase().replace(/[/\\]+$/, "");
}

/** 按 cwd 匹配项目；匹配不上取最近活跃的（单 IDE 场景零配置直连）。 */
function pickInstance(cwd: string): IdeInstance | undefined {
  const instances = discoverInstances();
  if (instances.length === 0) return undefined;
  const target = norm(cwd);
  // 前缀匹配：pi 的 cwd 可能是项目的子目录
  const match = instances.find((i) => {
    const p = norm(i.projectPath);
    return !!p && !!target && (p === target || target.startsWith(p + "/") || target.startsWith(p + "\\"));
  });
  if (match) return match;
  // 回退：最近心跳的实例
  return instances.sort((a, b) => b.updatedAt - a.updatedAt)[0];
}

/** 选区数据转 markdown。 */
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
async function fetchSelection(cwd: string): Promise<SelectionPayload | null | undefined> {
  const inst = pickInstance(cwd);
  if (!inst) return undefined;
  try {
    const res = await fetch(`http://127.0.0.1:${inst.httpPort}/api/selection`, {
      signal: AbortSignal.timeout(500),
    });
    if (!res.ok) return undefined;
    const data = await res.json();
    return data && data.type === "selection_changed" ? data : null;
  } catch {
    return undefined;
  }
}

export default function (pi: ExtensionAPI) {
  let ws: WebSocket | undefined;
  let wsPort: number | undefined;
  let reconnectTimer: NodeJS.Timeout | undefined;
  let shuttingDown = false;
  let widgetCtx: any;
  // 上次已注入的内容（会话级）：相同选区跳过，避免重复占用上下文
  let lastInjected: string | undefined;

  const setWidgetLines = (lines: string[]) => {
    widgetCtx?.ui.setWidget(WIDGET_ID, lines);
  };

  const sendActivity = () => {
    try {
      ws?.send(JSON.stringify({ type: "activity", pid: process.pid, ts: Date.now() }));
    } catch {
      // 未连接时忽略
    }
  };

  const connect = (cwd: string) => {
    if (shuttingDown) return;
    const inst = pickInstance(cwd);
    // 没有实例（插件没装/IDE 全关了）或端口变了才重连；否则保持现连接
    if (!inst) {
      scheduleReconnect(cwd);
      return;
    }
    if (ws && wsPort === inst.wsPort) return;
    try { ws?.close(); } catch { /* ignore */ }
    ws = undefined;
    wsPort = inst.wsPort;
    try {
      ws = new WebSocket(`ws://127.0.0.1:${inst.wsPort}`);
    } catch {
      scheduleReconnect(cwd);
      return;
    }
    ws.onopen = () => {
      sendActivity(); // 新会话/重连即活跃（"最后打开的"优先）
    };
    ws.onmessage = (ev: MessageEvent) => {
      try {
        const data = JSON.parse(String(ev.data));
        if (data?.type === "selection_changed") {
          setWidgetLines([summarize(data)]);
          return;
        }
        // IDEA "发送到 Pi"：把引用直接粘进输入框，用户在旁边打说明。
        // 用 setEditorText(getEditorText() + ref) 而非 pasteToEditor：
        // pasteToEditor 在部分终端下不触发重绘（需滚动才可见），setEditorText 走完整 UI 更新路径。
        if (data?.type === "pin" && typeof data.reference === "string") {
          const ui = widgetCtx?.ui;
          if (!ui) return;
          try {
            const existing = ui.getEditorText() ?? "";
            const sep = existing.length > 0 && !/\s$/.test(existing) ? "\n" : "";
            ui.setEditorText(existing + sep + data.reference + " ");
          } catch {
            ui.pasteToEditor(data.reference + " "); // 兼容回退
          }
          return;
        }
      } catch {
        // 非法消息忽略
      }
    };
    ws.onerror = () => {
      try { ws?.close(); } catch { /* ignore */ }
    };
    ws.onclose = () => {
      ws = undefined;
      wsPort = undefined;
      if (!shuttingDown) scheduleReconnect(cwd);
    };
  };

  const scheduleReconnect = (cwd: string) => {
    if (shuttingDown || reconnectTimer) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = undefined;
      connect(cwd);
    }, RECONNECT_MS);
  };

  // 每次用户提交 prompt 前：拉取当前选区，未注入过的才注入（一次选中一次注入）
  pi.on("before_agent_start", async (_event, _ctx) => {
    const sel = await fetchSelection(process.cwd());
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

  // 提交时把文本里的 @/path#L16-23 引用展开为真实代码（"发送到 Pi" 粘进输入框的引用由此生效）
  pi.on("input", async (event, _ctx) => {
    if (event.source === "interactive") sendActivity(); // 用户在这个会话打字提交 = 活跃
    if (event.source !== "interactive") return { action: "continue" };
    if (!REF_TEST.test(event.text)) return { action: "continue" };
    const expanded = expandReferences(event.text);
    if (expanded === event.text) return { action: "continue" };
    return { action: "transform", text: expanded };
  });

  pi.on("session_start", async (_event, ctx) => {
    shuttingDown = false;
    lastInjected = undefined; // 新会话重置：允许重新注入
    connect(process.cwd());
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
    wsPort = undefined;
    widgetCtx = undefined;
  });

  // 手动查看当前选区与注入状态
  pi.registerCommand("selection", {
    description: "查看 IDEA 当前选区及注入状态",
    handler: async (_args, ctx) => {
      const sel = await fetchSelection(process.cwd());
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
