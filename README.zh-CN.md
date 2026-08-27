# pi-idea-plugin

<p align="center"><img src="logo.png" width="160" alt="Pi Agent Selection logo"></p>

[English](README.md) | **简体中文**

IDEA 插件：把编辑器中**当前选中的代码**实时提供给 [Pi coding agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)，选中即上下文，无需复制粘贴。

机制对标 Claude Code 的 IDE 集成：**拉模式、实时状态**。

## 工作原理

```
IDEA 选中代码（每个项目窗口独立）
   │ SelectionListener（事件源编辑器，300ms 防抖，按项目过滤）
   ▼
PiWebSocketServer（每项目一个实例，仅存内存）
   ├─ Lock   ~/.pi/ide/ide-<port>.lock          端口+项目注册（5s 心跳）
   ├─ HTTP  http://127.0.0.1:19232+2i/api/selection   实时查询当前选区（Pi 扩展提交 prompt 时拉取）
   ├─ HTTP  http://127.0.0.1:19232+2i/api/health      健康检查
   └─ WS    ws://127.0.0.1:19233+2i                   选区变化即时推送（Pi 扩展 widget 实时显示）
```

选区数据：文件路径/文件名/选中内容/起止行列/语言/光标位置。

服务仅监听 127.0.0.1，不对外网暴露。

**多 IDE 隔离**：每个项目窗口独立服务、独立端口对（从 19232/19233 起，每窗口 +2）。
Pi 扩展扫描 `~/.pi/ide/*.lock`，优先选 cwd 所属项目的实例，匹配不上退到最近活跃实例。
不同项目之间的选区互不串扰。

## 安装

### 1. IDEA 插件

从 JetBrains Marketplace 搜索 "Pi Agent Selection" 安装，或源码构建：

```bash
git clone https://github.com/Colin606/pi-idea-plugin.git
cd pi-idea-plugin
./gradlew buildPlugin
```

产物在 `build/distributions/pi-idea-plugin-1.2.0.zip`，IDEA -> Settings -> Plugins -> ⚙ -> Install Plugin from Disk。

要求：JDK 21，IDEA 2024.2+（sinceBuild 242）。

### 2. Pi 侧扩展（自动安装，无需手动）

插件 1.2.0 起内置 Pi 侧扩展：IDEA 启动时自动安装/更新到 `~/.pi/agent/extensions/idea-selection.ts`（升级时旧版备份为 `.bak`），重启 pi 会话即生效。

想手动装（或预览源码）也可以，仓库源码在 [`pi-extension/idea-selection.ts`](pi-extension/idea-selection.ts)：

```bash
mkdir -p ~/.pi/agent/extensions
cp pi-extension/idea-selection.ts ~/.pi/agent/extensions/
# 重启 pi 会话生效；注意手动装后插件不会再覆盖同版本文件
```

扩展提供的能力：

| 能力 | 说明 |
|------|------|
| 上下文注入 | 提交 prompt 时 HTTP 实时拉取当前选区，以 `<idea-selection>` 标记注入，模型直接可见 |
| 一次选中一次注入 | 同一选区只注入第一条 prompt（内容比对跳过），换选区自动注入新内容，新会话重置 |
| TUI 实时 widget | 编辑区上方显示 `IDEA selection: Xxx.java (起-止行)`，WS 推送实时刷新 |
| 取消选区即失效 | 拉的是实时状态，IDEA 里取消选中后不再注入 |
| 手动查看 | pi 内执行 `/selection` 命令查看当前选区及注入状态 |

> 注：IDEA 未启动时扩展静默跳过，不影响 pi 正常使用。

## 使用

1. IDEA 打开任意项目（插件随项目启动自动起服务）
2. 选中一段代码
3. 在 Pi 里直接提问——"我选的代码有什么问题"——选区已自动在上下文里

## 配置端口（可选）

```bash
# 端口对按项目窗口自动分配：HTTP 19232+2i / WS 19233+2i（见 PiWebSocketServer）
# 注册锁文件在 ~/.pi/ide/，心跳停止 ~15s 后自动过期
```

## License

MIT
