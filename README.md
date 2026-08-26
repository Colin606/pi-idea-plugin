# pi-idea-plugin

<p align="center"><img src="logo.png" width="160" alt="Pi Selection logo"></p>

**English** | [简体中文](README.zh-CN.md)

An IntelliJ IDEA plugin that feeds your **current editor selection** to the [Pi coding agent](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) in real time — select code, ask in Pi, and the selection is already in context. No copy-pasting.

The injection model mirrors Claude Code's IDE integration: **pull-based, live state**.

## How it works

```
IDEA selection (per project window)
   │ SelectionListener (event-source editor, 300ms debounce, project-scoped)
   ▼
PiWebSocketServer (one instance per project, in-memory only)
   ├─ Lock   ~/.pi/ide/ide-<port>.lock          port + project registration (5s heartbeat)
   ├─ HTTP  http://127.0.0.1:19232+2i/api/selection   query current selection (Pi extension pulls at prompt time)
   ├─ HTTP  http://127.0.0.1:19232+2i/api/health      health check
   └─ WS    ws://127.0.0.1:19233+2i                   instant push for Pi's UI widget
```

Selection data: file path / file name / selected text / line & column range / language / caret position.

The service listens on `127.0.0.1` only - nothing is exposed to the network.

**Multi-IDE isolation:** every project window gets its own service on its own port pair
(starting at 19232/19233, +2 per window). The Pi extension scans `~/.pi/ide/*.lock`,
picks the instance whose project contains its working directory, and falls back to the
most recently active one. Selections from one project never leak into another.

## Install

### 1. IDEA plugin

Install from JetBrains Marketplace ("Pi Agent Selection"), or build from source:

```bash
git clone https://github.com/Colin606/pi-idea-plugin.git
cd pi-idea-plugin
./gradlew buildPlugin
```

The artifact is `build/distributions/pi-idea-plugin-1.2.0.zip` - IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk.

Requirements: JDK 21, IDEA 2024.2+ (`sinceBuild` 242).

### 2. Pi-side extension (installed automatically)

Since 1.2.0 the plugin bundles the Pi-side extension: on IDE startup it is deployed/updated to `~/.pi/agent/extensions/idea-selection.ts` (previous copy backed up as `.bak` on upgrade). Just restart your pi session.

Manual install still works if you prefer — source lives at [`pi-extension/idea-selection.ts`](pi-extension/idea-selection.ts):

```bash
mkdir -p ~/.pi/agent/extensions
cp pi-extension/idea-selection.ts ~/.pi/agent/extensions/
# note: the plugin won't overwrite a manually installed file of the same version
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
# Port pairs auto-allocate per project window: HTTP 19232+2i / WS 19233+2i (see PiWebSocketServer)
# Registration locks live in ~/.pi/ide/ and expire after ~15s without heartbeat
```

## License

MIT
