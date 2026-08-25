# pi-idea-plugin

<p align="center"><img src="logo.png" width="160" alt="Pi Selection logo"></p>

**English** | [简体中文](README.zh-CN.md)

An IntelliJ IDEA plugin that feeds your **current editor selection** to the [Pi coding agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) in real time — select code, ask in Pi, and the selection is already in context. No copy-pasting.

The injection model mirrors Claude Code's IDE integration: **pull-based, live state**.

## How it works

```
IDEA selection
   │ SelectionListener (event-source editor, 300ms debounce)
   ▼
PiWebSocketServer (application-level singleton, in-memory only)
   ├─ HTTP  http://127.0.0.1:19232/api/selection   query current selection (Pi extension pulls at prompt time)
   ├─ HTTP  http://127.0.0.1:19232/api/health      health check
   └─ WS    ws://127.0.0.1:19233                   instant push for Pi's UI widget
```

Selection data: file path / file name / selected text / line & column range / language / caret position.

The service listens on `127.0.0.1` only — nothing is exposed to the network.

## Install

### 1. IDEA plugin

Install from JetBrains Marketplace ("Pi Selection"), or build from source:

```bash
git clone https://github.com/Colin606/pi-idea-plugin.git
cd pi-idea-plugin
./gradlew buildPlugin
```

The artifact is `build/distributions/pi-idea-plugin-1.0.0.zip` — IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk.

Requirements: JDK 21, IDEA 2024.2+ (`sinceBuild` 242).

### 2. Pi-side extension

The plugin only exposes the selection state; Pi needs a companion extension that pulls it and injects `<idea-selection>` context on each prompt.

This repo ships one: [`pi-extension/idea-selection.ts`](pi-extension/idea-selection.ts). Install:

```bash
# 1. Copy to the pi global extensions directory
mkdir -p ~/.pi/agent/extensions
cp pi-extension/idea-selection.ts ~/.pi/agent/extensions/

# 2. Restart your pi session
```

What the extension provides:

| Capability | Description |
|------------|-------------|
| Context injection | Pulls the current selection over HTTP at prompt time and injects it as `<idea-selection>` |
| One-shot injection | The same selection is injected only once (content comparison); a new selection is picked up automatically |
| TUI widget | Shows `IDEA selection: Xxx.java (lines)` above the input, refreshed via WebSocket push |
| Deselect stops injection | Live state: no selection in IDEA → nothing injected |
| Manual check | `/selection` command shows the current selection and its injection state |

> Note: if IDEA is not running, the extension silently skips — Pi works normally without it.

## Usage

1. Open any project in IDEA (the service starts automatically)
2. Select some code
3. Ask in Pi — "what's wrong with my selection?" — the selection is already in context

## Ports (optional)

```bash
# HTTP query port defaults to 19232, WS push port to 19233 (see PiWebSocketServer)
```

## License

MIT
