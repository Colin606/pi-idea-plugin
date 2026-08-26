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
const EXTENSION_VERSION = "1.2.0";
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
